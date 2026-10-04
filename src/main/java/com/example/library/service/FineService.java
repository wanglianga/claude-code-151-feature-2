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
     * 申诉核查期间（growth_hold=true）金额冻结，不随天数增长——暂缓不等于减免。
     */
    @Transactional
    public Fine refreshOverdueFine(Loan loan) {
        return refreshOverdueFine(loan, loan.getDueDate());
    }

    /**
     * 按指定起算日刷新逾期金额。读者责任终判时以<b>原到期日</b>起算
     * （申诉暂缓的天数照常计入），不因争议而减免。
     */
    @Transactional
    public Fine refreshOverdueFine(Loan loan, Instant basisDueDate) {
        Instant now = clock.now();
        long days = overdueDays(basisDueDate, now);
        List<Fine> existing = fines.findByLoanId(loan.getId()).stream()
                .filter(f -> f.getType() == FineType.OVERDUE && f.getStatus() != FineStatus.PAID
                        && f.getStatus() != FineStatus.WAIVED)
                .toList();
        if (days <= 0) {
            return null;
        }
        double amount = overdueAmount(days);
        String reason = "《" + loan.getItem().getBib().getTitle() + "》条码 " + loan.getItem().getBarcode()
                + " 已逾期 " + days + " 天（应还 " + loan.getDueDate() + "）";
        if (existing.isEmpty()) {
            Fine f = create(loan.getReader(), FineType.OVERDUE, ChargedTo.READER, amount,
                    reason, overdueBasis(), loan, null);
            credit.recordOverduePenaltyIfNeeded(loan);
            return f;
        }
        Fine f = existing.get(0);
        if (f.isGrowthHold()) {
            return f; // 申诉核查期间：金额暂缓增长
        }
        f.setAmount(BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP));
        f.setReason(reason);
        return f;
    }

    /**
     * 受理“已还未入库”申诉：冻结该借阅逾期罚款的日增长。
     * 若此时逾期罚款尚未生成（读者在每日批处理前申诉），按当前逾期天数先建单再冻结，
     * 保证暂缓金额可向读者明示。
     */
    @Transactional
    public void freezeOverdueGrowth(Loan loan) {
        List<Fine> overdue = fines.findByLoanId(loan.getId()).stream()
                .filter(f -> f.getType() == FineType.OVERDUE
                        && (f.getStatus() == FineStatus.OPEN || f.getStatus() == FineStatus.REDUCED))
                .toList();
        if (overdue.isEmpty()) {
            Fine f = refreshOverdueFine(loan);
            if (f != null) {
                f.setGrowthHold(true);
            }
            return;
        }
        overdue.forEach(f -> f.setGrowthHold(true));
    }

    /** 解除暂缓：后续日累计恢复 */
    @Transactional
    public void unfreezeOverdueGrowth(Loan loan) {
        fines.findByLoanId(loan.getId()).stream()
                .filter(f -> f.getType() == FineType.OVERDUE)
                .forEach(f -> f.setGrowthHold(false));
    }

    /** 读者责任终判：解除暂缓并按原到期日补算申诉期间暂缓增长的金额 */
    @Transactional
    public Fine resumeOverdueFromOriginalDueDate(Loan loan) {
        unfreezeOverdueGrowth(loan);
        return refreshOverdueFine(loan, loan.getDueDate());
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
        f.setGrowthHold(false); // 人工调整即结案，不再受申诉暂缓控制
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

        if (f.getReader() != null) {
            notifications.notify(f.getReader(), "FINE_ADJUSTED", "罚款调整通知",
                    "您关于「" + (f.getLoan() != null ? f.getLoan().getItem().getBib().getTitle() : f.getType())
                            + "」的罚款已由馆员 " + staff + " "
                            + ("WAIVE".equals(action) ? "豁免" : "调减") + "：" + old + " → " + target
                            + " 元。理由：" + reason);
        }
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
