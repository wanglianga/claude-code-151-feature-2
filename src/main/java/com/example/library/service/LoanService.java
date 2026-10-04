package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.web.ApiException;
import com.example.library.web.Views;
import com.example.library.web.dto.ClaimView;
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
    private final ClaimRepository claims;
    private final ClaimEvidenceRepository evidenceRepo;
    private final PolicyService policy;
    private final FineService fines;
    private final HoldService holdService;
    private final NotificationService notifications;
    private final CreditService credit;
    private final SelfCheckinService kiosk;
    private final AppClock clock;

    public LoanService(LoanRepository loans, ItemRepository items, ReaderRepository readers,
                       LibraryRepository libraries, HoldRepository holds, ClaimRepository claims,
                       ClaimEvidenceRepository evidenceRepo,
                       PolicyService policy, FineService fines, HoldService holdService,
                       NotificationService notifications, CreditService credit,
                       SelfCheckinService kiosk, AppClock clock) {
        this.loans = loans;
        this.items = items;
        this.readers = readers;
        this.libraries = libraries;
        this.holds = holds;
        this.claims = claims;
        this.evidenceRepo = evidenceRepo;
        this.policy = policy;
        this.fines = fines;
        this.holdService = holdService;
        this.notifications = notifications;
        this.credit = credit;
        this.kiosk = kiosk;
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

        long overdueCount = loans.countOverdueForReader(reader.getId(), LoanStatus.ACTIVE, clock.now());
        if (overdueCount > 0) {
            throw ApiException.unprocessable("您有 " + overdueCount + " 册逾期未还图书，请先归还后再借书");
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

        // 关联的待处理“已还未入库”工单：图书实际还回，按馆方流程原因结案并撤罚、修复信用
        claims.findFirstByLoanIdAndStatusIn(loan.getId(), List.of(ClaimStatus.OPEN, ClaimStatus.DISPUTED))
                .ifPresent(c -> closeLibraryFault(c, lib, "还书台",
                        "图书由读者实际归还入库，原争议按馆方流程原因结案"));

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

    // ---------------- “声称已还”争议 ----------------

    /**
     * 读者就逾期提醒申诉“已在自助机还书但未入库”。
     * 受理后：借阅进入争议状态；该借阅的逾期罚款立即冻结（不撤销、不增长、不催收）；
     * 借阅信用不立即恢复（此前逾期扣分保持，结案时再按责任方处理）。
     */
    @Transactional
    public Claim claimReturned(Long loanId, String note, String machineCode) {
        Loan loan = loans.findById(loanId)
                .orElseThrow(() -> ApiException.notFound("借阅单不存在: " + loanId));
        if (loan.getStatus() != LoanStatus.ACTIVE) {
            throw ApiException.conflict("借阅单状态为 " + loan.getStatus() + "，不能提交已还未入库申诉");
        }
        claims.findFirstByLoanIdAndStatusIn(loanId, List.of(ClaimStatus.OPEN, ClaimStatus.DISPUTED))
                .ifPresent(c -> {
                    throw ApiException.conflict("该借阅单已有进行中的申诉工单 #" + c.getId()
                            + (c.getStatus() == ClaimStatus.DISPUTED ? "（争议保持中，可继续补充证据）" : ""));
                });
        Instant now = clock.now();
        Claim c = new Claim();
        c.setLoan(loan);
        c.setReader(loan.getReader());
        c.setStatus(ClaimStatus.OPEN);
        c.setNote(note);
        c.setMachineCode(machineCode);
        c.setCreatedAt(now);
        c.setFrozenAt(now);
        claims.save(c);

        // 冻结已有逾期罚款（若读者在逾期多日后才申诉，冻结金额保留，争议期间不再增长）
        fines.freezeOverdueForClaim(loan, c.getId());

        loan.setStatus(LoanStatus.CLAIMED_RETURNED);
        if (note != null && !note.isBlank()) {
            addEvidence(c, "READER", "OTHER", note, null);
        }
        notifications.notify(loan.getReader(), "CLAIM", "已还未入库申诉已受理",
                "我们已受理您关于《" + loan.getItem().getBib().getTitle() + "》的已还申诉，"
                        + "将核查看还书机日志、监控、书箱清点与馆员入库记录。核查期间逾期罚款暂缓增长，"
                        + "但借阅信用暂不恢复，待结案后按责任方处理。");
        return c;
    }

    /** 读者在申诉处理期间继续补充证据（OPEN / DISPUTED 均可） */
    @Transactional
    public Claim addReaderEvidence(Long claimId, String kind, String content) {
        Claim c = claims.findById(claimId)
                .orElseThrow(() -> ApiException.notFound("申诉工单不存在: " + claimId));
        if (c.getStatus() != ClaimStatus.OPEN && c.getStatus() != ClaimStatus.DISPUTED) {
            throw ApiException.conflict("工单已结案：" + c.getStatus() + "，不能补充证据");
        }
        addEvidence(c, "READER", kind, content, null);
        return c;
    }

    /** 馆员录入工作记录（核查过程留痕，不改变四项结论字段） */
    @Transactional
    public Claim addStaffEvidence(Long claimId, String staff, String kind, String content) {
        Claim c = claims.findById(claimId)
                .orElseThrow(() -> ApiException.notFound("申诉工单不存在: " + claimId));
        if (isClosed(c)) {
            throw ApiException.conflict("工单已结案：" + c.getStatus());
        }
        addEvidence(c, "STAFF", kind, content, staff);
        return c;
    }

    private void addEvidence(Claim c, String source, String kind, String content, String staff) {
        ClaimEvidence e = new ClaimEvidence();
        e.setClaim(c);
        e.setSource(source);
        e.setKind(kind);
        e.setContent(content);
        e.setStaff(staff);
        e.setCreatedAt(clock.now());
        evidenceRepo.save(e);
    }

    /**
     * 馆员录入四类核查之一的结论：KIOSK 还书机日志 / CAMERA 监控编号 /
     * BIN 书箱清点 / SHELF 馆员入库记录。result=MATCH / NOT_FOUND / NA。
     */
    @Transactional
    public Claim recordCheck(Long claimId, String channel, String staff, String result,
                             Long logId, String ref, String note) {
        Claim c = mustActiveClaim(claimId);
        ClaimCheck check = parseCheck(result);
        Instant now = clock.now();
        String label;
        switch (channel) {
            case "KIOSK" -> {
                c.setKioskCheck(check);
                c.setKioskNote(join(c.getKioskNote(), note));
                if (check == ClaimCheck.MATCH) {
                    SelfCheckinLog log = logId != null ? kiosk.mustLog(logId)
                            : kiosk.query(c.getLoan().getItem().getBarcode(), null,
                                    c.getCreatedAt().minus(2, ChronoUnit.DAYS), now)
                            .stream().findFirst().orElse(null);
                    if (log != null) {
                        c.setKioskLog(log);
                        if (log.getCameraRef() != null && c.getCameraRef() == null) {
                            c.setCameraRef(log.getCameraRef());
                        }
                    }
                }
                label = "还书机日志";
            }
            case "CAMERA" -> {
                c.setCameraCheck(check);
                if (ref != null && !ref.isBlank()) {
                    c.setCameraRef(ref);
                }
                c.setCameraNote(join(c.getCameraNote(), note));
                label = "监控编号";
            }
            case "BIN" -> {
                c.setBinCheck(check);
                c.setBinCountAt(now);
                c.setBinNote(join(c.getBinNote(), note));
                label = "书箱清点";
            }
            case "SHELF" -> {
                c.setShelfCheck(check);
                c.setShelfRecordedAt(now);
                c.setShelfNote(join(c.getShelfNote(), note));
                label = "馆员入库记录";
            }
            default -> throw ApiException.unprocessable("未知核查环节: " + channel
                    + "（支持 KIOSK/CAMERA/BIN/SHELF）");
        }
        c.setStaff(staff);
        addEvidence(c, "STAFF", channel, label + "核查结论：" + check
                + (ref != null && !ref.isBlank() ? "（编号 " + ref + "）" : "")
                + (note != null && !note.isBlank() ? "；" + note : ""), staff);
        return c;
    }

    private static ClaimCheck parseCheck(String result) {
        try {
            ClaimCheck check = ClaimCheck.valueOf(result);
            if (check == ClaimCheck.PENDING) {
                throw new IllegalArgumentException();
            }
            return check;
        } catch (IllegalArgumentException ex) {
            throw ApiException.unprocessable("result 仅支持 MATCH / NOT_FOUND / NA");
        }
    }

    private static String join(String old, String add) {
        if (add == null || add.isBlank()) {
            return old;
        }
        return old == null || old.isBlank() ? add : old + "；" + add;
    }

    /**
     * 自助机扫描失败但书已在书箱清点/架上找到：属馆方设备/入库流程问题。
     * 罚款撤销、库存修正（图书入 foundLibrary 并重新流转）、借阅结案、信用同案修复。
     */
    @Transactional
    public Claim resolveScanFailFound(Long claimId, String staff, String foundLibraryCode,
                                      String binNo, String note) {
        Claim c = mustActiveClaim(claimId);
        c.setKioskCheck(ClaimCheck.MATCH);
        c.setKioskNote(join(c.getKioskNote(),
                "自助机扫描失败（SCAN_FAILED），读者已投书" + (binNo != null ? "，书箱 " + binNo : "")));
        c.setBinCheck(ClaimCheck.MATCH);
        c.setBinCountAt(clock.now());
        if (binNo != null && !binNo.isBlank()) {
            c.setBinNote(join(c.getBinNote(), "在书箱 " + binNo + " 清点找到该书"));
        }
        if (note != null && !note.isBlank()) {
            c.setBinNote(join(c.getBinNote(), note));
        }
        addEvidence(c, "STAFF", "BIN",
                "书箱清点找到条码 " + c.getLoan().getItem().getBarcode()
                        + (binNo != null ? "（书箱 " + binNo + "）" : "")
                        + "，确认系自助机扫描失败导致未入库", staff);
        Library lib = libraries.findByCode(foundLibraryCode)
                .orElseThrow(() -> ApiException.notFound("分馆不存在: " + foundLibraryCode));
        closeLibraryFault(c, lib, staff,
                "自助机扫描失败但图书已找到，属馆方设备/入库流程问题");
        return c;
    }

    /**
     * 首轮核查未找到书：不立即判赔，争议保持（DISPUTED）。
     * 借阅仍处争议状态、罚款继续冻结；读者可继续提供证据，馆方据新证据重开工单。
     */
    @Transactional
    public Claim keepDisputed(Long claimId, String staff, String note) {
        Claim c = mustActiveClaim(claimId);
        c.setStatus(ClaimStatus.DISPUTED);
        c.setStaff(staff);
        if (note != null && !note.isBlank()) {
            c.setNote(join(c.getNote(), "首轮核查未找到：" + note));
            addEvidence(c, "STAFF", "OTHER", "首轮核查未找到，争议保持：" + note, staff);
        }
        notifications.notify(c.getReader(), "CLAIM", "核查进展：暂未找到，争议保持",
                "您声明已还的《" + c.getLoan().getItem().getBib().getTitle()
                        + "》首轮核查暂未找到。申诉保持争议状态，逾期罚款继续暂缓增长，"
                        + "您可以继续提交还书时间、还书机编号、凭条或同行人等证据，馆方将再次核查。");
        return c;
    }

    /** 读者提交新证据后，馆员把 DISPUTED 工单重开为 OPEN 继续核查 */
    @Transactional
    public Claim reopenClaim(Long claimId, String staff, String note) {
        Claim c = claims.findById(claimId)
                .orElseThrow(() -> ApiException.notFound("申诉工单不存在: " + claimId));
        if (c.getStatus() != ClaimStatus.DISPUTED) {
            throw ApiException.conflict("仅“争议保持”工单可重开，当前状态：" + c.getStatus());
        }
        c.setStatus(ClaimStatus.OPEN);
        c.setStaff(staff);
        if (note != null && !note.isBlank()) {
            addEvidence(c, "STAFF", "OTHER", "依读者新证据重开核查：" + note, staff);
        }
        notifications.notify(c.getReader(), "CLAIM", "申诉已重新核查",
                "您关于《" + c.getLoan().getItem().getBib().getTitle()
                        + "》的新证据已受理，馆方重新启动核查，期间罚款继续暂缓增长。");
        return c;
    }

    /**
     * 最终确认读者责任。
     * bookFound=true：书已找到归还——借阅结案，逾期罚款一律按原到期日起算（不豁免处理期），
     *                 逾期信用扣分维持/补记，信用不因申诉恢复。
     * bookFound=false：确认丢书——借阅置 LOST、馆藏置 LOST，按赔书价赔偿并计丢书信用扣分。
     */
    @Transactional
    public Claim resolveReaderLiable(Long claimId, String staff, boolean bookFound,
                                     String foundLibraryCode, String reason) {
        Claim c = mustActiveOrDisputed(claimId);
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("定责必须填写理由");
        }
        Loan loan = c.getLoan();
        Instant now = clock.now();

        if (bookFound) {
            Library lib = libraries.findByCode(foundLibraryCode)
                .orElseThrow(() -> ApiException.notFound("分馆不存在: " + foundLibraryCode));
            Item item = items.lockById(loan.getItem().getId()).orElseThrow();
            loan.setStatus(LoanStatus.RETURNED);
            loan.setReturnedAt(now);
            loan.setReturnedLibrary(lib);
            item.setCurrentLibrary(lib);

            // 逾期罚款按原到期日重新核算（处理期不免计）；确有逾期才维持/补记逾期扣分，信用不恢复
            Fine settled = fines.settleReaderLiableOverdue(loan, staff, now, reason);
            if (settled != null && settled.getStatus() == FineStatus.OPEN) {
                credit.recordOverdueOnce(loan,
                        "申诉 #" + c.getId() + " 最终确认读者责任，逾期信用扣分维持", staff);
            }

            c.setStatus(ClaimStatus.READER_LIABLE);
            c.setLiability("READER");
            c.setStaff(staff);
            c.setResolvedAt(now);
            c.setClosedAt(now);
            c.setNote(join(c.getNote(), "最终读者责任（书已还回）：" + reason));
            holdService.reallocate(item.getId(), lib);
            notifications.notify(loan.getReader(), "CLAIM", "核查结果：确认读者责任",
                    "您声明已还的《" + item.getBib().getTitle() + "》经核查确认还书延误由读者承担，"
                            + "逾期罚款按原到期日 " + loan.getDueDate() + " 计算，借阅信用不予恢复。");
        } else {
            Item item = items.lockById(loan.getItem().getId()).orElseThrow();
            loan.setStatus(LoanStatus.LOST);
            item.setStatus(ItemStatus.LOST);
            // 冻结的逾期单解冻（赔书单另行生成，逾期部分仍按原到期日保留）
            Fine settledOverdue = fines.settleReaderLiableOverdue(loan, staff, now, reason);
            if (settledOverdue != null && settledOverdue.getStatus() == FineStatus.OPEN) {
                credit.recordOverdueOnce(loan,
                        "申诉 #" + c.getId() + " 最终确认读者责任，逾期信用扣分维持", staff);
            }
            fines.create(loan.getReader(), FineType.LOST, ChargedTo.READER, policy.feeReplacement(),
                    "《" + item.getBib().getTitle() + "》条码 " + item.getBarcode()
                            + " 申诉 #" + c.getId() + " 最终确认读者责任且图书未找到，按丢书赔书",
                    "fee.replacement=" + FineService.money(policy.feeReplacement()) + " 元/册",
                    loan, null);
            credit.recordLostOnce(loan, c.getId(),
                    "申诉 #" + c.getId() + " 最终确认读者丢书", staff);

            c.setStatus(ClaimStatus.READER_LIABLE);
            c.setLiability("READER");
            c.setStaff(staff);
            c.setResolvedAt(now);
            c.setClosedAt(now);
            c.setNote(join(c.getNote(), "最终读者责任（图书未找到）：" + reason));
            notifications.notify(loan.getReader(), "FINE_OPEN", "核查结果：确认读者责任，图书未找到",
                    "您声明已还的《" + item.getBib().getTitle() + "》经最终核查确认由读者承担，"
                            + "需按丢书赔偿 " + FineService.money(policy.feeReplacement())
                            + " 元，并计借阅信用扣分。");
        }
        return c;
    }

    /**
     * 最终确认馆方漏入库/设备故障：图书入库正常流转、读者侧未结罚款全部撤销、
     * 该借阅此前的信用扣分同案修复（信用恢复）。
     */
    @Transactional
    public Claim resolveLibraryLiable(Long claimId, String staff, String foundLibraryCode, String reason) {
        Claim c = mustActiveOrDisputed(claimId);
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("定责必须填写理由");
        }
        Library lib = libraries.findByCode(foundLibraryCode)
                .orElseThrow(() -> ApiException.notFound("分馆不存在: " + foundLibraryCode));
        c.setShelfCheck(ClaimCheck.NOT_FOUND);
        c.setShelfNote(join(c.getShelfNote(), "馆员入库记录缺失：" + reason));
        addEvidence(c, "STAFF", "SHELF", "馆员入库记录核查无该书入库登记：" + reason, staff);
        closeLibraryFault(c, lib, staff, reason);
        return c;
    }

    /** 馆方责任结案公共逻辑：入库修正库存、撤罚、信用修复 */
    private void closeLibraryFault(Claim c, Library lib, String staff, String reason) {
        Loan loan = c.getLoan();
        Item item = items.lockById(loan.getItem().getId()).orElseThrow();
        Instant now = clock.now();

        c.setStatus(ClaimStatus.LIBRARY_LIABLE);
        c.setLiability("LIBRARY");
        c.setStaff(staff);
        c.setResolvedAt(now);
        c.setClosedAt(now);
        c.setNote(join(c.getNote(), "确认馆方责任：" + reason));

        loan.setStatus(LoanStatus.RETURNED);
        loan.setReturnedAt(now);
        loan.setReturnedLibrary(lib);
        item.setCurrentLibrary(lib);

        fines.waiveAllForLoanOnLibraryFault(loan, staff, "已还未入库申诉 #" + c.getId() + "：" + reason);
        int repaired = credit.repairForLoan(loan, c.getId(), staff);

        notifications.notify(loan.getReader(), "CLAIM", "核查结果：馆方责任，罚款撤销、信用修复",
                "您声明已还的《" + item.getBib().getTitle() + "》经核查为馆方"
                        + (reason.contains("扫描") ? "设备扫描" : "入库")
                        + "原因所致，逾期/相关罚款已撤销，库存已更正"
                        + (repaired > 0 ? "，相关借阅信用扣分（" + repaired + " 分）已修复" : "")
                        + "。给您带来不便，敬请谅解。");
        holdService.reallocate(item.getId(), lib);
    }

    /** 兼容旧“在架找到”入口：按馆方流程原因结案（撤罚、入库、修复信用） */
    @Transactional
    public Claim resolveClaimFound(Long claimId, String foundLibraryCode, String staff) {
        Claim c = mustActiveClaim(claimId);
        c.setShelfCheck(ClaimCheck.MATCH);
        c.setShelfRecordedAt(clock.now());
        addEvidence(c, "STAFF", "SHELF", "馆员在馆找到该书并补录入库", staff);
        Library lib = libraries.findByCode(foundLibraryCode)
                .orElseThrow(() -> ApiException.notFound("分馆不存在: " + foundLibraryCode));
        closeLibraryFault(c, lib, staff, "馆员在馆找到，属馆方上架/入库流程延误");
        return c;
    }

    /** 馆方豁免结案（保留旧流程）：图书按馆藏正常流转、未结罚款豁免、信用修复 */
    @Transactional
    public Claim waiveClaim(Long claimId, String staff, String reason, String foundLibraryCode) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("豁免必须填写理由");
        }
        Claim c = mustActiveOrDisputed(claimId);
        Library lib = libraries.findByCode(foundLibraryCode)
                .orElseThrow(() -> ApiException.notFound("分馆不存在: " + foundLibraryCode));
        Loan loan = c.getLoan();
        Item item = items.lockById(loan.getItem().getId()).orElseThrow();
        Instant now = clock.now();

        c.setStatus(ClaimStatus.WAIVED);
        c.setLiability("LIBRARY");
        c.setStaff(staff);
        c.setResolvedAt(now);
        c.setClosedAt(now);
        c.setNote(join(c.getNote(), "馆方豁免：" + reason));
        loan.setStatus(LoanStatus.RETURNED);
        loan.setReturnedAt(now);
        loan.setReturnedLibrary(lib);
        item.setCurrentLibrary(lib);

        fines.waiveAllForLoanOnLibraryFault(loan, staff, "申诉 #" + c.getId() + " 馆方豁免：" + reason);
        credit.repairForLoan(loan, c.getId(), staff);
        holdService.reallocate(item.getId(), lib);
        return c;
    }

    private boolean isClosed(Claim c) {
        return c.getStatus() != ClaimStatus.OPEN && c.getStatus() != ClaimStatus.DISPUTED;
    }

    private Claim mustActiveClaim(Long claimId) {
        Claim c = claims.findById(claimId)
                .orElseThrow(() -> ApiException.notFound("申诉工单不存在: " + claimId));
        if (c.getStatus() != ClaimStatus.OPEN) {
            throw ApiException.conflict(c.getStatus() == ClaimStatus.DISPUTED
                    ? "工单处于争议保持状态，请先依新证据重开（reopen）再录入核查"
                    : "工单已结案：" + c.getStatus());
        }
        return c;
    }

    private Claim mustActiveOrDisputed(Long claimId) {
        Claim c = claims.findById(claimId)
                .orElseThrow(() -> ApiException.notFound("申诉工单不存在: " + claimId));
        if (c.getStatus() != ClaimStatus.OPEN && c.getStatus() != ClaimStatus.DISPUTED) {
            throw ApiException.conflict("工单已结案：" + c.getStatus());
        }
        return c;
    }

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

    @Transactional
    public ClaimView claimView(Long loanId, String note) {
        return claimView(loanId, note, null);
    }

    @Transactional
    public ClaimView claimView(Long loanId, String note, String machineCode) {
        return toView(claimReturned(loanId, note, machineCode));
    }

    @Transactional
    public ClaimView readerEvidenceView(Long claimId, String kind, String content) {
        return toView(addReaderEvidence(claimId, kind, content));
    }

    @Transactional
    public ClaimView staffEvidenceView(Long claimId, String staff, String kind, String content) {
        return toView(addStaffEvidence(claimId, staff, kind, content));
    }

    @Transactional
    public ClaimView recordCheckView(Long claimId, String channel, String staff, String result,
                                     Long logId, String ref, String note) {
        return toView(recordCheck(claimId, channel, staff, result, logId, ref, note));
    }

    @Transactional
    public ClaimView scanFailFoundView(Long claimId, String staff, String code, String binNo, String note) {
        return toView(resolveScanFailFound(claimId, staff, code, binNo, note));
    }

    @Transactional
    public ClaimView keepDisputedView(Long claimId, String staff, String note) {
        return toView(keepDisputed(claimId, staff, note));
    }

    @Transactional
    public ClaimView reopenView(Long claimId, String staff, String note) {
        return toView(reopenClaim(claimId, staff, note));
    }

    @Transactional
    public ClaimView readerLiableView(Long claimId, String staff, boolean bookFound,
                                     String code, String reason) {
        return toView(resolveReaderLiable(claimId, staff, bookFound, code, reason));
    }

    @Transactional
    public ClaimView libraryLiableView(Long claimId, String staff, String code, String reason) {
        return toView(resolveLibraryLiable(claimId, staff, code, reason));
    }

    @Transactional
    public ClaimView resolveFoundView(Long claimId, String code, String staff) {
        return toView(resolveClaimFound(claimId, code, staff));
    }

    @Transactional
    public ClaimView waiveView(Long claimId, String staff, String reason, String code) {
        return toView(waiveClaim(claimId, staff, reason, code));
    }

    @Transactional(readOnly = true)
    public ClaimView claimDetailView(Long claimId) {
        Claim c = claims.findById(claimId)
                .orElseThrow(() -> ApiException.notFound("申诉工单不存在: " + claimId));
        return toView(c);
    }

    @Transactional(readOnly = true)
    public List<com.example.library.web.dto.EvidenceView> evidenceViews(Long claimId) {
        if (claims.findById(claimId).isEmpty()) {
            throw ApiException.notFound("申诉工单不存在: " + claimId);
        }
        return evidenceRepo.findByClaimIdOrderByCreatedAt(claimId).stream()
                .map(Views::evidence).toList();
    }

    @Transactional(readOnly = true)
    public List<Claim> claimsForReader(String cardNo) {
        Reader reader = readers.findByCardNo(cardNo)
                .orElseThrow(() -> ApiException.notFound("读者证不存在: " + cardNo));
        return claims.findAll().stream()
                .filter(c -> c.getReader().getId().equals(reader.getId()))
                .sorted(java.util.Comparator.comparing(Claim::getCreatedAt).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClaimView> claimViewsForReader(String cardNo) {
        return claimsForReader(cardNo).stream().map(this::toView).toList();
    }

    private ClaimView toView(Claim c) {
        Fine frozen = fines.forLoan(c.getLoan().getId()).stream()
                .filter(f -> f.getFrozenClaimId() != null && f.getFrozenClaimId().equals(c.getId()))
                .findFirst().orElse(null);
        return Views.claim(c, frozen);
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

    @Transactional(readOnly = true)
    public List<Claim> openClaims() {
        return claims.findByStatusOrderByCreatedAt(ClaimStatus.OPEN);
    }

    /** 馆员待处理：核查中（OPEN）与争议保持（DISPUTED）工单 */
    @Transactional(readOnly = true)
    public List<ClaimView> openClaimViews() {
        return claims.findByStatusInOrderByCreatedAt(List.of(ClaimStatus.OPEN, ClaimStatus.DISPUTED))
                .stream().map(this::toView).toList();
    }

    public Instant dueDate(Loan loan) {
        return loan.getDueDate();
    }

    /**
     * 日终批处理：对 ACTIVE 借阅刷新逾期罚款/发逾期与到期前提醒。
     * 争议中（CLAIMED_RETURNED）的借阅跳过——罚款已冻结、不催收、信用不变化。
     * 由定时任务与手动触发端点共用。
     */
    @Transactional
    public int runDailyOverdueJob() {
        int touched = 0;
        for (Loan l : loans.findByStatusIn(List.of(LoanStatus.ACTIVE))) {
            String barcode = l.getItem().getBarcode();
            Instant now = clock.now();
            if (l.getDueDate().isBefore(now)) {
                fines.refreshOverdueFine(l);
                if (!notifications.alreadySentWith(l.getReader().getId(), "OVERDUE", barcode)) {
                    notifications.notify(l.getReader(), "OVERDUE", "图书逾期提醒",
                            "您借阅的《" + l.getItem().getBib().getTitle() + "》（条码 " + barcode
                                    + "）已超过应还日期 " + l.getDueDate() + "，请尽快归还，罚款按日累计。"
                                    + "如您已在自助机还书但收到本提醒，可提交“已还未入库”申诉，核查期间罚款暂缓增长。");
                }
                touched++;
            } else if (!l.getDueDate().isAfter(now.plus(1, ChronoUnit.DAYS))
                    && !notifications.alreadySentWith(l.getReader().getId(), "DUE_REMINDER", barcode)) {
                notifications.notify(l.getReader(), "DUE_REMINDER", "图书即将到期",
                        "您借阅的《" + l.getItem().getBib().getTitle() + "》（条码 " + barcode
                                + "）将于 " + l.getDueDate() + " 到期，可在无排队冲突时续借。");
            }
        }
        return touched;
    }
}
