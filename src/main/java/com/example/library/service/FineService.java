package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.FineAdjustmentRepository;
import com.example.library.repo.FineRepository;
import com.example.library.web.ApiException;
import com.example.library.web.Views;
import com.example.library.web.dto.FineView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class FineService {

    private final FineRepository fines;
    private final FineAdjustmentRepository adjustments;
    private final PolicyService policy;
    private final AppClock clock;
    private final NotificationService notifications;
    private final CreditService credit;

    public FineService(FineRepository fines, FineAdjustmentRepository adjustments,
                       PolicyService policy, AppClock clock, NotificationService notifications,
                       CreditService credit) {
        this.fines = fines;
        this.adjustments = adjustments;
        this.policy = policy;
        this.clock = clock;
        this.notifications = notifications;
        this.credit = credit;
    }

    public static String money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public Fine create(Reader reader, FineType type, ChargedTo chargedTo, double amount,
                       String reason, String ruleBasis, Loan loan, TransferTask task) {
        Fine f = new Fine();
        f.setReader(reader);
        f.setType(type);
        f.setChargedTo(chargedTo);
        f.setAmount(BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP));
        f.setStatus(FineStatus.OPEN);
        f.setReason(reason);
        f.setRuleBasis(ruleBasis);
        f.setLoan(loan);
        f.setTask(task);
        f.setCreatedAt(clock.now());
        return fines.save(f);
    }

    /** 逾期天数（满 24 小时计 1 天） */
    public long overdueDays(Instant dueDate, Instant at) {
        if (!at.isAfter(dueDate)) return 0;
        return Duration.between(dueDate, at).toDays();
    }

    public double overdueAmount(long days) {
        return Math.min(policy.fineCap(), days * policy.fineDaily());
    }

    public String overdueBasis() {
        return "逾期罚款 = min(fine.cap=" + money(policy.fineCap())
                + ", 逾期满整天数 × fine.daily=" + money(policy.fineDaily()) + ")";
    }

    /**
     * 根据当前时间刷新某笔借阅的逾期罚款（一笔借阅始终只挂一条 OPEN 的 OVERDUE 罚款，
     * 金额随天数增长直到上限），供定时任务与还书结算共用。
     * 争议申诉中（CLAIMED_RETURNED）或已被申诉冻结的罚款不增长。
     */
    @Transactional
    public Fine refreshOverdueFine(Loan loan) {
        Instant now = clock.now();
        long days = overdueDays(loan.getDueDate(), now);
        List<Fine> existing = fines.findByLoanId(loan.getId()).stream()
                .filter(f -> f.getType() == FineType.OVERDUE && f.getStatus() != FineStatus.PAID
                        && f.getStatus() != FineStatus.WAIVED)
                .toList();
        // 已冻结：金额、状态保持受理时刻的值，争议期间不增长
        Fine frozen = existing.stream().filter(f -> f.getFrozenClaimId() != null).findFirst().orElse(null);
        if (frozen != null) {
            return frozen;
        }
        if (days <= 0) {
            return null;
        }
        // 争议中的借阅不再新增逾期罚款（罚款暂缓增长）
        if (loan.getStatus() == LoanStatus.CLAIMED_RETURNED) {
            return existing.isEmpty() ? null : existing.get(0);
        }
        double amount = overdueAmount(days);
        String reason = "《" + loan.getItem().getBib().getTitle() + "》条码 " + loan.getItem().getBarcode()
                + " 已逾期 " + days + " 天（应还 " + loan.getDueDate() + "）";
        if (existing.isEmpty()) {
            Fine created = create(loan.getReader(), FineType.OVERDUE, ChargedTo.READER, amount,
                    reason, overdueBasis(), loan, null);
            // 逾期责任成立即计一次借阅信用扣减（同一借阅只扣一次）；
            // 此后若经申诉确认馆方漏入库，由 CreditService 同案修复。
            credit.recordOverdueOnce(loan, "借阅逾期（应还 " + loan.getDueDate() + "）", "system");
            return created;
        }
        Fine f = existing.get(0);
        f.setAmount(BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP));
        f.setReason(reason);
        return f;
    }

    @Transactional
    public Fine pay(Long fineId) {
        Fine f = fines.findById(fineId).orElseThrow(() -> ApiException.notFound("罚款单不存在: " + fineId));
        if (f.getStatus() == FineStatus.PAID) {
            throw ApiException.conflict("罚款单已缴清");
        }
        if (f.getStatus() == FineStatus.WAIVED) {
            throw ApiException.conflict("罚款单已豁免，无需缴纳");
        }
        f.setStatus(FineStatus.PAID);
        f.setResolvedAt(clock.now());
        return f;
    }

    /**
     * 馆员人工调减/豁免：必须填写理由，系统记录原值、新值、操作人与规则依据，
     * 并通知读者。调整结果对读者账单与后续统计即时生效。
     */
    @Transactional
    public Fine adjust(Long fineId, String staff, String action, Double newAmount, String reason) {
        return adjust(fineId, staff, action, newAmount, reason, true);
    }

    /** 内部入口：系统结案撤罚（如馆方漏入库）可跳过读者通知重复推送 */
    @Transactional
    public Fine adjust(Long fineId, String staff, String action, Double newAmount, String reason,
                       boolean notifyReader) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("人工调整必须填写理由");
        }
        Fine f = fines.findById(fineId).orElseThrow(() -> ApiException.notFound("罚款单不存在: " + fineId));
        if (f.getStatus() == FineStatus.PAID) {
            throw ApiException.conflict("已缴清罚款不能调整，如需更正请走退款流程");
        }
        BigDecimal old = f.getAmount();
        BigDecimal target;
        if ("WAIVE".equals(action)) {
            target = BigDecimal.ZERO;
            f.setStatus(FineStatus.WAIVED);
        } else if ("REDUCE".equals(action)) {
            if (newAmount == null || newAmount < 0 || newAmount >= old.doubleValue()) {
                throw ApiException.unprocessable("调减金额必须大于等于 0 且小于当前金额 " + old);
            }
            target = BigDecimal.valueOf(newAmount).setScale(2, RoundingMode.HALF_UP);
            f.setStatus(FineStatus.REDUCED);
        } else {
            throw ApiException.unprocessable("action 仅支持 REDUCE / WAIVE");
        }
        f.setAmount(target);
        f.setResolvedAt(clock.now());

        FineAdjustment a = new FineAdjustment();
        a.setFine(f);
        a.setStaff(staff);
        a.setAction(action);
        a.setOldAmount(old);
        a.setNewAmount(target);
        a.setReason(reason);
        a.setRuleBasis(f.getRuleBasis());
        a.setCreatedAt(clock.now());
        adjustments.save(a);

        if (notifyReader && f.getReader() != null) {
            notifications.notify(f.getReader(), "FINE_ADJUSTED", "罚款调整通知",
                    "您关于「" + (f.getLoan() != null ? f.getLoan().getItem().getBib().getTitle() : f.getType())
                            + "」的罚款已由馆员 " + staff + " "
                            + ("WAIVE".equals(action) ? "豁免" : "调减") + "：" + old + " → " + target
                            + " 元。理由：" + reason);
        }
        return f;
    }

    // ---------------- “已还未入库”申诉：罚款冻结与结案结算 ----------------

    /**
     * 受理申诉时冻结该借阅的逾期罚款：已有 OPEN 逾期单则按当前金额挂起（不撤销、不增长）；
     * 尚无逾期单则不新建——争议期间暂缓增长。返回被冻结的罚款（可能为 null）。
     */
    @Transactional
    public Fine freezeOverdueForClaim(Loan loan, Long claimId) {
        Instant now = clock.now();
        Fine f = fines.findByLoanId(loan.getId()).stream()
                .filter(x -> x.getType() == FineType.OVERDUE
                        && x.getStatus() != FineStatus.PAID && x.getStatus() != FineStatus.WAIVED)
                .findFirst().orElse(null);
        if (f != null && f.getFrozenClaimId() == null) {
            f.setFrozenClaimId(claimId);
            f.setFrozenAt(now);
        }
        return f;
    }

    /** 馆方责任（自助机扫描失败后找到 / 漏入库）：撤销该借阅全部读者侧未结罚款 */
    @Transactional
    public List<Fine> waiveAllForLoanOnLibraryFault(Loan loan, String staff, String reason) {
        List<Fine> waived = new java.util.ArrayList<>();
        for (Fine f : fines.findByLoanId(loan.getId())) {
            if (f.getChargedTo() == ChargedTo.READER
                    && f.getStatus() != FineStatus.PAID && f.getStatus() != FineStatus.WAIVED) {
                f.setFrozenClaimId(null);
                f.setFrozenAt(null);
                waived.add(adjust(f.getId(), staff, "WAIVE", null, reason, false));
            }
        }
        return waived;
    }

    /**
     * 最终确认读者责任：解冻逾期罚款，并一律按“原到期日 → 现在”重新核算金额
     * （申诉处理期不计豁免，若实际已还则由还书时间封顶），状态恢复为 OPEN。
     */
    @Transactional
    public Fine settleReaderLiableOverdue(Loan loan, String staff, Instant returnedAt, String reason) {
        Fine f = fines.findByLoanId(loan.getId()).stream()
                .filter(x -> x.getType() == FineType.OVERDUE
                        && x.getStatus() != FineStatus.PAID && x.getStatus() != FineStatus.WAIVED)
                .findFirst().orElse(null);
        Instant endAt = returnedAt != null && returnedAt.isBefore(clock.now()) ? returnedAt : clock.now();
        long days = overdueDays(loan.getDueDate(), endAt);
        double amount = overdueAmount(days);
        if (f == null) {
            if (amount <= 0) {
                return null;
            }
            Fine created = create(loan.getReader(), FineType.OVERDUE, ChargedTo.READER, amount,
                    "《" + loan.getItem().getBib().getTitle() + "》最终确认读者责任，逾期 " + days
                            + " 天，自原到期日起算", overdueBasis(), loan, null);
            return created;
        }
        f.setFrozenClaimId(null);
        f.setFrozenAt(null);
        if (days <= 0) {
            // 原到期日至今未构成逾期：冻结单撤罚
            f.setFrozenClaimId(null);
            f.setFrozenAt(null);
            return adjust(f.getId(), staff, "WAIVE", null,
                    "最终核查未构成逾期（原到期日 " + loan.getDueDate() + "）", false);
        }
        f.setStatus(FineStatus.OPEN);
        f.setAmount(BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP));
        f.setResolvedAt(null);
        f.setReason("《" + loan.getItem().getBib().getTitle() + "》申诉结案确认读者责任：按原到期日 "
                + loan.getDueDate() + " 计算，逾期 " + days + " 天。" + reason);
        return f;
    }

    @Transactional(readOnly = true)
    public List<Fine> forReader(String cardNo) {
        return fines.findByReaderCardNoOrderByCreatedAtDesc(cardNo);
    }

    @Transactional(readOnly = true)
    public List<Fine> forLoan(Long loanId) {
        return fines.findByLoanId(loanId);
    }

    @Transactional(readOnly = true)
    public List<FineView> readerViews(String cardNo) {
        return forReader(cardNo).stream().map(Views::fine).toList();
    }

    @Transactional(readOnly = true)
    public FineView fineView(Long id) {
        return Views.fine(fines.findById(id)
                .orElseThrow(() -> ApiException.notFound("罚款单不存在: " + id)));
    }

    @Transactional
    public FineView payView(Long fineId) {
        return Views.fine(pay(fineId));
    }

    @Transactional
    public FineView adjustView(Long fineId, String staff, String action, Double newAmount, String reason) {
        return Views.fine(adjust(fineId, staff, action, newAmount, reason));
    }

    @Transactional(readOnly = true)
    public List<FineAdjustment> adjustmentHistory(Long fineId) {
        return adjustments.findByFineIdOrderByCreatedAt(fineId);
    }
}
