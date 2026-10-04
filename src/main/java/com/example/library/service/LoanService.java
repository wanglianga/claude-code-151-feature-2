package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.web.ApiException;
import com.example.library.web.Views;
import com.example.library.web.dto.LoanView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 借阅生命周期：借出、应还日期、续借资格（次数上限 + 排队冲突）、
 * 还书核验（条码/馆藏状态/污损/缺页/附件）、逾期与赔书罚款、
 * “声称已还”争议处理。
 */
@Service
public class LoanService {

    private final LoanRepository loans;
    private final ItemRepository items;
    private final ReaderRepository readers;
    private final LibraryRepository libraries;
    private final HoldRepository holds;
    private final PolicyService policy;
    private final FineService fines;
    private final HoldService holdService;
    private final ClaimService claims;
    private final NotificationService notifications;
    private final AppClock clock;

    public LoanService(LoanRepository loans, ItemRepository items, ReaderRepository readers,
                       LibraryRepository libraries, HoldRepository holds,
                       PolicyService policy, FineService fines, HoldService holdService,
                       ClaimService claims,
                       NotificationService notifications, AppClock clock) {
        this.loans = loans;
        this.items = items;
        this.readers = readers;
        this.libraries = libraries;
        this.holds = holds;
        this.policy = policy;
        this.fines = fines;
        this.holdService = holdService;
        this.claims = claims;
        this.notifications = notifications;
        this.clock = clock;
    }

    public record RenewCheck(boolean renewable, String reason, String ruleBasis) {}

    @Transactional(readOnly = true)
    public RenewCheck renewEligibility(Loan loan) {
        if (loan.getStatus() != LoanStatus.ACTIVE && loan.getStatus() != LoanStatus.CLAIMED_RETURNED) {
            return new RenewCheck(false, "借阅单状态为 " + loan.getStatus(), null);
        }
        if (loan.getStatus() == LoanStatus.CLAIMED_RETURNED) {
            return new RenewCheck(false, "该借阅单存在“声称已还”争议，待馆方核查", null);
        }
        if (loan.getRenewCount() >= policy.renewLimit()) {
            return new RenewCheck(false,
                    "已达续借次数上限 " + policy.renewLimit() + "（热门书不得被长期独占）",
                    "loan.renewLimit=" + policy.renewLimit());
        }
        long waiting = holds.findActiveByBib(loan.getItem().getBib().getId(),
                CatalogService.ACTIVE_HOLD_STATUSES).size();
        if (waiting > 0) {
            return new RenewCheck(false,
                    "该书当前有 " + waiting + " 位读者在预约排队，热门书有排队时不可续借",
                    "排队冲突优先：等候中的 " + waiting + " 个预约优先获得该书");
        }
        return new RenewCheck(true, null,
                "续借后应还日期顺延 " + policy.loanPeriodDays() + " 天；剩余续借次数 "
                        + (policy.renewLimit() - loan.getRenewCount() - 1));
    }

    @Transactional
    public Loan checkout(String cardNo, String barcode, String libraryCode) {
        Reader reader = readers.findByCardNo(cardNo)
                .orElseThrow(() -> ApiException.notFound("读者证不存在: " + cardNo));
        if (!"ACTIVE".equals(reader.getStatus())) {
            throw ApiException.unprocessable("读者证已停用");
        }
        Item item = items.findByBarcode(barcode)
                .orElseThrow(() -> ApiException.notFound("馆藏条码不存在: " + barcode));
        holdService.lockActiveSet(item.getBib().getId());
        items.lockById(item.getId());
        Library lib = libraries.findByCode(libraryCode)
                .orElseThrow(() -> ApiException.notFound("分馆不存在: " + libraryCode));

        long overdueCount = loans.countOverdueForReader(reader.getId(), LoanStatus.ACTIVE, clock.now())
                + loans.countOverdueForReader(reader.getId(), LoanStatus.CLAIMED_RETURNED, clock.now());
        if (overdueCount > 0) {
            throw ApiException.unprocessable("您有 " + overdueCount + " 册逾期未还（含已还未入库申诉核查中）图书，"
                    + "请先归还或等待申诉核查结论后再借书");
        }

        Instant due = clock.now().plus(policy.loanPeriodDays(), ChronoUnit.DAYS);
        if (lib.isTemporaryClosed()) {
            due = due.plus(policy.closureGraceHours(), ChronoUnit.HOURS);
        }

        if (item.getStatus() == ItemStatus.ON_HOLD) {
            Hold mine = holds.findActiveByBib(item.getBib().getId(), CatalogService.ACTIVE_HOLD_STATUSES).stream()
                    .filter(h -> h.getStatus() == HoldStatus.READY
                            && h.getItem() != null && h.getItem().getId().equals(item.getId())
                            && h.getReader().getId().equals(reader.getId()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.conflict(
                            "条码 " + barcode + " 在预约架上，属于其他读者的预约，不能直接借出"));
            mine.setStatus(HoldStatus.BORROWED);
            mine.setQueuePosition(null);
        } else if (item.getStatus() != ItemStatus.AVAILABLE) {
            throw ApiException.conflict("条码 " + barcode + " 当前状态为 " + item.getStatus() + "，不可借出");
        }

        Loan loan = new Loan();
        loan.setReader(reader);
        loan.setItem(item);
        loan.setCheckoutLibrary(lib);
        loan.setCheckedOutAt(clock.now());
        loan.setDueDate(due);
        loan.setStatus(LoanStatus.ACTIVE);
        loans.save(loan);

        item.setStatus(ItemStatus.CHECKED_OUT);
        item.setCurrentLibrary(lib);
        return loan;
    }

    @Transactional
    public Loan renew(Long loanId) {
        Loan loan = loans.findById(loanId)
                .orElseThrow(() -> ApiException.notFound("借阅单不存在: " + loanId));
        RenewCheck check = renewEligibility(loan);
        if (!check.renewable()) {
            throw ApiException.unprocessable("不能续借：" + check.reason());
        }
        loan.setRenewCount(loan.getRenewCount() + 1);
        loan.setDueDate(loan.getDueDate().plus(policy.loanPeriodDays(), ChronoUnit.DAYS));
        return loan;
    }

    /** 还书核验结果（工作人员录入） */
    public record ReturnAssessment(boolean damaged, boolean missingPages, boolean accessoryMissing,
                                   String note) {}

    @Transactional
    public Loan returnBook(String barcode, String returnLibraryCode, ReturnAssessment assessment) {
        Item item = items.findByBarcode(barcode)
                .orElseThrow(() -> ApiException.notFound("馆藏条码不存在: " + barcode));
        // 全局加锁顺序：预约集 → 馆藏行
        holdService.lockActiveSet(item.getBib().getId());
        items.lockById(item.getId());
        Loan loan = loans.findFirstByItemIdAndStatusOrderByCheckedOutAtDesc(item.getId(), LoanStatus.ACTIVE)
                .or(() -> loans.findFirstByItemIdAndStatusOrderByCheckedOutAtDesc(
                        item.getId(), LoanStatus.CLAIMED_RETURNED))
                .orElseThrow(() -> ApiException.conflict("条码 " + barcode + " 没有进行中的借阅记录"));
        Library lib = libraries.findByCode(returnLibraryCode)
                .orElseThrow(() -> ApiException.notFound("还书分馆不存在: " + returnLibraryCode));

        Instant now = clock.now();
        loan.setStatus(LoanStatus.RETURNED);
        loan.setReturnedAt(now);
        loan.setReturnedLibrary(lib);
        loan.setConditionNote(assessment.note());
        item.setCurrentLibrary(lib);

        // 逾期罚款结算（金额封顶，保留 OPEN 供读者缴纳/馆员调整）
        if (now.isAfter(loan.getDueDate())) {
            fines.refreshOverdueFine(loan);
        }

        boolean unfit = assessment.damaged() || assessment.missingPages() || assessment.accessoryMissing();
        if (assessment.damaged()) {
            item.setCondition("DAMAGED");
            applyDamageFine(loan, FineType.DAMAGE, policy.feeDamage(),
                    "污损", "fee.damage=" + FineService.money(policy.feeDamage()) + " 元/册");
        }
        if (assessment.missingPages()) {
            item.setMissingPages(true);
            item.setCondition("DAMAGED");
            applyDamageFine(loan, FineType.MISSING_PAGES, policy.feeMissingPages(),
                    "缺页", "fee.missingPages=" + FineService.money(policy.feeMissingPages()) + " 元/册");
        }
        if (assessment.accessoryMissing()) {
            applyDamageFine(loan, FineType.ACCESSORY, policy.feeAccessory(),
                    "缺附件", "fee.accessory=" + FineService.money(policy.feeAccessory()) + " 元/件");
        }

        // 关联的“已还未入库”申诉在图书实际核验出现时自动结案（馆方责任：撤罚 + 修信用）
        claims.autoCloseOnPhysicalReturn(loan, lib);

        if (unfit) {
            // 污损/缺页图书不参与再分配，转维修/注销评估
            item.setStatus(ItemStatus.DAMAGED);
        } else {
            // 完好图书：优先满足该书目排队中的预约（可能继续跨馆调拨），否则在还书馆上架
            holdService.reallocate(item.getId(), lib);
        }
        return loan;
    }

    private void applyDamageFine(Loan loan, FineType type, double amount, String label, String basis) {
        fines.create(loan.getReader(), type, ChargedTo.READER, amount,
                "《" + loan.getItem().getBib().getTitle() + "》条码 " + loan.getItem().getBarcode()
                        + " 还书核验发现" + label,
                basis, loan, null);
        notifications.notify(loan.getReader(), "FINE_OPEN", "赔偿通知",
                "您归还的《" + loan.getItem().getBib().getTitle() + "》核验发现" + label
                        + "，按规则需赔偿 " + FineService.money(amount) + " 元。如有异议可到馆申诉。");
    }

    // ---------------- “已还未入库”申诉由 ClaimService 负责 ----------------

    @Transactional(readOnly = true)
    public List<LoanView> activeLoanViews(String cardNo) {
        return activeForReader(cardNo).stream().map(Views::loan).toList();
    }

    @Transactional
    public LoanView checkoutView(String cardNo, String barcode, String libraryCode) {
        return Views.loan(checkout(cardNo, barcode, libraryCode));
    }

    @Transactional
    public LoanView renewView(Long loanId) {
        return Views.loan(renew(loanId));
    }

    @Transactional
    public LoanView returnView(String barcode, String libraryCode, ReturnAssessment assessment) {
        return Views.loan(returnBook(barcode, libraryCode, assessment));
    }

    @Transactional(readOnly = true)
    public List<Loan> activeForReader(String cardNo) {
        return loans.findByReaderCardNoAndStatusOrderByCheckedOutAtDesc(cardNo, LoanStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public LoanView loanView(Long id) {
        return Views.loan(get(id));
    }

    @Transactional(readOnly = true)
    public Loan get(Long id) {
        return loans.findById(id).orElseThrow(() -> ApiException.notFound("借阅单不存在: " + id));
    }

    public Instant dueDate(Loan loan) {
        return loan.getDueDate();
    }
}
