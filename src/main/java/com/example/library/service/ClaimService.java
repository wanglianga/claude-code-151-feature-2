package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.web.ApiException;
import com.example.library.web.dto.ClaimDetailView;
import com.example.library.web.dto.ClaimView;
import com.example.library.web.dto.EvidenceView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * “已还未入库”申诉核查。
 *
 * 读者收到逾期提醒但称已在自助机还书，馆员依次核查：
 * 还书机日志（扫描失败/书箱卡滞）→ 监控编号 → 书箱清点 → 馆员入库记录。
 *
 * 规则：
 * - 受理即把借阅置 CLAIMED_RETURNED，逾期罚款暂缓增长（不减免），借阅信用不立即恢复；
 * - 自助机扫描失败但书已找到（或馆方确认漏入库）：撤销罚款、修正库存、修复信用；
 * - 书未找到：保持争议（DISPUTED），读者可继续举证，馆方重新核查；
 * - 终判读者责任：罚款按原到期日计算（暂缓天数照常计入）并按丢书生成赔书单。
 */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final ClaimEvidenceRepository evidences;
    private final KioskLogRepository kioskLogs;
    private final LoanRepository loans;
    private final ItemRepository items;
    private final LibraryRepository libraries;
    private final FineRepository fines;
    private final FineService fineService;
    private final CreditService credit;
    private final HoldService holdService;
    private final NotificationService notifications;
    private final PolicyService policy;
    private final AppClock clock;

    public ClaimService(ClaimRepository claims, ClaimEvidenceRepository evidences,
                        KioskLogRepository kioskLogs, LoanRepository loans, ItemRepository items,
                        LibraryRepository libraries, FineRepository fines, FineService fineService,
                        CreditService credit, HoldService holdService,
                        NotificationService notifications, PolicyService policy, AppClock clock) {
        this.claims = claims;
        this.evidences = evidences;
        this.kioskLogs = kioskLogs;
        this.loans = loans;
        this.items = items;
        this.libraries = libraries;
        this.fines = fines;
        this.fineService = fineService;
        this.credit = credit;
        this.holdService = holdService;
        this.notifications = notifications;
        this.policy = policy;
        this.clock = clock;
    }

    private static final List<ClaimStatus> ACTIVE_STATUSES = List.of(ClaimStatus.OPEN, ClaimStatus.DISPUTED);

    // ---------------- 读者侧：受理与举证 ----------------

    /** 读者发起“已还未入库”申诉 */
    @Transactional
    public Claim openClaim(Long loanId, String kioskCode, Instant claimedReturnedAt, String note) {
        Loan loan = loans.findById(loanId)
                .orElseThrow(() -> ApiException.notFound("借阅单不存在: " + loanId));
        if (loan.getStatus() != LoanStatus.ACTIVE) {
            throw ApiException.conflict("借阅单状态为 " + loan.getStatus() + "，不能提交已还未入库申诉");
        }
        claims.findFirstByLoanIdAndStatusIn(loanId, ACTIVE_STATUSES).ifPresent(c -> {
            throw ApiException.conflict("该借阅单已有进行中的申诉工单 #" + c.getId()
                    + "（状态 " + c.getStatus() + "）");
        });

        Instant now = clock.now();
        Claim c = new Claim();
        c.setLoan(loan);
        c.setReader(loan.getReader());
        c.setStatus(ClaimStatus.OPEN);
        c.setKioskCode(kioskCode);
        c.setClaimedReturnedAt(claimedReturnedAt);
        c.setHeldAt(now);
        c.setNote(note);
        c.setCreatedAt(now);
        claims.save(c);

        loan.setStatus(LoanStatus.CLAIMED_RETURNED);
        fineService.freezeOverdueGrowth(loan);

        notifications.notify(loan.getReader(), "CLAIM", "已还未入库申诉已受理",
                "您关于《" + loan.getItem().getBib().getTitle() + "》（条码 "
                        + loan.getItem().getBarcode() + "）的已还申诉已受理，工单号 #" + c.getId()
                        + "。馆员将核查自助机日志、监控、书箱清点与入库记录；核查期间逾期罚款暂缓增长，"
                        + "借阅信用暂不恢复，请耐心等待核查结果。");
        return c;
    }

    /**
     * 读者继续提供证据。申诉处于 DISPUTED（初次核查未找到）时，
     * 新证据使工单回到 OPEN 由馆方重新核查。
     */
    @Transactional
    public ClaimEvidence addReaderEvidence(Long claimId, String kind, String content, String refNo) {
        Claim c = mustActiveClaim(claimId);
        EvidenceKind k = parseKind(kind, EvidenceKind.READER);
        ClaimEvidence e = saveEvidence(c, k, EvidenceSource.READER, content, refNo,
                null, c.getReader().getCardNo());
        if (c.getStatus() == ClaimStatus.DISPUTED) {
            c.setStatus(ClaimStatus.OPEN);
            saveEvidence(c, EvidenceKind.REOPEN, EvidenceSource.READER,
                    "读者补充新证据，申请重新核查", null, null, c.getReader().getCardNo());
            notifications.notify(c.getReader(), "CLAIM", "申诉已重新进入核查",
                    "您对工单 #" + c.getId() + " 补充的证据已收到，馆方将重新核查。");
        }
        return e;
    }

    // ---------------- 馆员侧：核查证据链 ----------------

    /** 馆员登记核查证据（KIOSK_LOG / CAMERA / BIN_COUNT / CHECKIN_RECORD） */
    @Transactional
    public ClaimEvidence addStaffEvidence(Long claimId, String kind, String content, String refNo, String staff) {
        if (staff == null || staff.isBlank()) {
            throw ApiException.unprocessable("登记核查证据必须填写操作馆员");
        }
        Claim c = mustActiveClaim(claimId);
        EvidenceKind k = parseKind(kind, null);
        if (k == EvidenceKind.READER) {
            throw ApiException.unprocessable("READER 类证据仅由读者提交，馆员请登记核查类证据");
        }
        if (c.getStatus() == ClaimStatus.DISPUTED) {
            c.setStatus(ClaimStatus.OPEN); // 馆员重启核查
        }
        return saveEvidence(c, k, EvidenceSource.STAFF, content, refNo, staff, staff);
    }

    /** 把某条自助机日志直接登记为机日志证据 */
    @Transactional
    public ClaimEvidence attachKioskLog(Long claimId, Long kioskLogId, String staff) {
        KioskLog log = kioskLogs.findById(kioskLogId)
                .orElseThrow(() -> ApiException.notFound("还书机日志不存在: " + kioskLogId));
        String content = log.getEventType() + "：" + log.getKioskCode() + " 于 " + log.getOccurredAt()
                + (log.getDetail() == null ? "" : "（" + log.getDetail() + "）");
        return addStaffEvidence(claimId, EvidenceKind.KIOSK_LOG.name(), content,
                "kioskLog#" + kioskLogId, staff);
    }

    // ---------------- 馆员侧：三种结案 / 一种保持争议 ----------------

    /**
     * 自助机扫描失败但书已在书箱/架上找到：撤销逾期罚款、补办入库修正库存、修复借阅信用。
     */
    @Transactional
    public Claim resolveScanFailFound(Long claimId, String foundLibraryCode, String staff,
                                      Long kioskLogId, String cameraRef, String binCountNote) {
        Claim c = mustActiveClaim(claimId);
        requireStaff(staff);
        Library lib = mustLibrary(foundLibraryCode);
        Loan loan = c.getLoan();
        Item item = items.lockById(loan.getItem().getId()).orElseThrow();
        Instant now = clock.now();

        if (kioskLogId != null) {
            KioskLog log = kioskLogs.findById(kioskLogId)
                    .orElseThrow(() -> ApiException.notFound("还书机日志不存在: " + kioskLogId));
            saveEvidence(c, EvidenceKind.KIOSK_LOG, EvidenceSource.STAFF,
                    log.getEventType() + "：" + log.getKioskCode() + " 于 " + log.getOccurredAt()
                            + (log.getDetail() == null ? "" : "（" + log.getDetail() + "）"),
                    "kioskLog#" + kioskLogId, staff, staff);
        }
        if (cameraRef != null && !cameraRef.isBlank()) {
            saveEvidence(c, EvidenceKind.CAMERA, EvidenceSource.STAFF,
                    "监控片段 " + cameraRef + " 显示读者在自助机完成还书投递动作",
                    cameraRef, staff, staff);
        }
        saveEvidence(c, EvidenceKind.BIN_COUNT, EvidenceSource.STAFF,
                "书箱清点找到条码 " + item.getBarcode()
                        + (binCountNote == null || binCountNote.isBlank() ? "" : "：" + binCountNote),
                null, staff, staff);
        saveEvidence(c, EvidenceKind.CHECKIN_RECORD, EvidenceSource.STAFF,
                "确认自助机扫描失败导致漏入库，图书已在" + lib.getName() + "补办入库", null, staff, staff);

        closeAsLibraryResponsible(c, ClaimStatus.FOUND, lib, now, staff,
                "自助机扫描失败但书已找到，逾期为设备/入库流程原因");
        notifications.notify(c.getReader(), "CLAIM", "核查结果：申诉成立，书已找到并入库",
                "您声明已还的《" + item.getBib().getTitle() + "》经核查为自助机扫描失败、图书已在书箱中找到，"
                        + "现已补办入库，逾期罚款已撤销，借阅信用记录已修复，感谢您的配合。");
        holdService.reallocate(item.getId(), lib);
        return c;
    }

    /**
     * 初步核查未找到：不判读者责任，保持争议状态，等待读者继续举证；
     * 罚款继续暂缓，信用继续暂扣。
     */
    @Transactional
    public Claim keepDisputed(Long claimId, String staff, String note) {
        Claim c = mustActiveClaim(claimId);
        requireStaff(staff);
        Loan loan = c.getLoan();
        saveEvidence(c, EvidenceKind.BIN_COUNT, EvidenceSource.STAFF,
                "书箱清点与在馆核查暂未找到条码 " + loan.getItem().getBarcode()
                        + (note == null || note.isBlank() ? "" : "：" + note),
                null, staff, staff);
        c.setStatus(ClaimStatus.DISPUTED);
        c.setStaff(staff);
        c.setNote(append(c.getNote(), "核查暂未找到，保持争议：" + note));
        notifications.notify(c.getReader(), "CLAIM", "核查进展：暂未找到，请继续提供证据",
                "工单 #" + c.getId() + " 经核查自助机日志、监控与书箱清点暂未找到您声明已还的《"
                        + loan.getItem().getBib().getTitle()
                        + "》。争议状态将继续保留，您可补充还书时间、监控线索等证据，馆方会重新核查；"
                        + "在此之前逾期罚款继续暂缓增长、借阅信用暂不恢复。若最终确认读者责任，"
                        + "罚款将按原到期日 " + loan.getDueDate() + " 计算。");
        return c;
    }

    /**
     * 馆方确认漏入库（如监控证实已投入还书箱但馆员处置失误）：
     * 罚款豁免、图书入库流转、信用记录同步修复。
     */
    @Transactional
    public Claim confirmLibraryMissedCheckin(Long claimId, String foundLibraryCode, String staff, String reason) {
        Claim c = mustActiveClaim(claimId);
        requireStaff(staff);
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("确认馆方漏入库必须填写理由/核查依据");
        }
        Library lib = mustLibrary(foundLibraryCode);
        Loan loan = c.getLoan();
        Item item = items.lockById(loan.getItem().getId()).orElseThrow();
        Instant now = clock.now();

        saveEvidence(c, EvidenceKind.CHECKIN_RECORD, EvidenceSource.STAFF,
                "馆员补办入库记录：" + reason, null, staff, staff);
        closeAsLibraryResponsible(c, ClaimStatus.WAIVED, lib, now, staff,
                "馆方确认漏入库：" + reason);
        notifications.notify(c.getReader(), "CLAIM", "核查结果：申诉成立，馆方漏入库",
                "您声明已还的《" + item.getBib().getTitle() + "》经核查确认为馆方入库流程遗漏，"
                        + "图书已入库流转，相关罚款已豁免，借阅信用记录已修复，给您带来不便敬请谅解。");
        holdService.reallocate(item.getId(), lib);
        return c;
    }

    /**
     * 终判读者责任（核查期满、读者未能进一步举证）：
     * 图书置 LOST，逾期罚款解除暂缓并<b>按原到期日</b>补算，生成赔书单，信用不恢复。
     */
    @Transactional
    public Claim upholdReaderResponsible(Long claimId, String staff, String reason) {
        Claim c = mustActiveClaim(claimId);
        requireStaff(staff);
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("终判必须填写理由");
        }
        Loan loan = c.getLoan();
        Item item = items.lockById(loan.getItem().getId()).orElseThrow();
        Instant now = clock.now();

        saveEvidence(c, EvidenceKind.BIN_COUNT, EvidenceSource.STAFF,
                "终核：书箱、在馆馆藏与入库记录均未找到条码 " + item.getBarcode() + "；" + reason,
                null, staff, staff);

        c.setStatus(ClaimStatus.NOT_FOUND);
        c.setOutcome(ClaimOutcome.READER_RESPONSIBLE);
        c.setStaff(staff);
        c.setResolvedAt(now);
        c.setNote(append(c.getNote(), "终判读者责任：" + reason));

        loan.setStatus(LoanStatus.LOST);
        item.setStatus(ItemStatus.LOST);

        // 罚款解除暂缓，按原到期日补算申诉期间的金额
        Fine overdue = fineService.resumeOverdueFromOriginalDueDate(loan);
        // 赔书价
        fineService.create(loan.getReader(), FineType.LOST, ChargedTo.READER, policy.feeReplacement(),
                "《" + item.getBib().getTitle() + "》条码 " + item.getBarcode()
                        + " 已还未入库申诉终判读者责任，图书未找到，按丢书赔书",
                "fee.replacement=" + FineService.money(policy.feeReplacement())
                        + " 元/册（如事后找到可凭工单号 #" + c.getId() + " 申诉退还）",
                loan, null);
        credit.recordLostPenalty(loan, c);

        notifications.notify(loan.getReader(), "FINE_OPEN", "核查结果：确认读者责任",
                "工单 #" + c.getId() + " 终核未找到《" + item.getBib().getTitle() + "》，"
                        + "逾期罚款已按原到期日 " + loan.getDueDate() + " 结算"
                        + (overdue == null ? "" : "（当前金额 " + overdue.getAmount() + " 元）")
                        + "，并生成赔书单；相关信用扣减不予恢复。如日后图书找到，可凭工单号申诉。");
        return c;
    }

    /**
     * 图书在正常还书核验中出现（读者确已还，系漏入库）：自动结案为 FOUND。
     * 不触碰库存/在馆分配（由 {@code LoanService.returnBook} 主流程统一处理）。
     */
    @Transactional
    public Claim autoCloseOnPhysicalReturn(Loan loan, Library lib) {
        Claim c = claims.findFirstByLoanIdAndStatusIn(loan.getId(), ACTIVE_STATUSES).orElse(null);
        if (c == null) {
            return null;
        }
        Instant now = clock.now();
        saveEvidence(c, EvidenceKind.CHECKIN_RECORD, EvidenceSource.STAFF,
                "图书在" + lib.getName() + "正常还书核验中出现，确认为漏入库，补办入库",
                null, "还书核验", "还书核验");
        closeAsLibraryResponsible(c, ClaimStatus.FOUND, lib, now, "还书核验",
                "图书实际归还入库，逾期为入库流程延误");
        notifications.notify(c.getReader(), "CLAIM", "核查结果：书已找到并入库",
                "您声明已还的《" + loan.getItem().getBib().getTitle() + "》已在还书核验中找到并入库，"
                        + "逾期罚款已撤销，借阅信用记录已修复。");
        return c;
    }

    // ---------------- 内部辅助 ----------------

    /** 馆方责任结案的公共动作：借阅归还、罚款撤销/豁免、信用修复 */
    private void closeAsLibraryResponsible(Claim c, ClaimStatus status, Library lib, Instant now,
                                           String staff, String note) {
        Loan loan = c.getLoan();
        c.setStatus(status);
        c.setOutcome(ClaimOutcome.LIBRARY_MISSED_CHECKIN);
        c.setStaff(staff);
        c.setResolvedAt(now);
        c.setNote(append(c.getNote(), note));

        if (loan.getStatus() == LoanStatus.CLAIMED_RETURNED) {
            loan.setStatus(LoanStatus.RETURNED);
            loan.setReturnedAt(c.getClaimedReturnedAt() != null ? c.getClaimedReturnedAt() : now);
            loan.setReturnedLibrary(lib);
        }
        loan.getItem().setCurrentLibrary(lib);

        fines.findByLoanId(loan.getId()).stream()
                .filter(f -> f.getType() == FineType.OVERDUE && f.getStatus() != FineStatus.PAID
                        && f.getStatus() != FineStatus.WAIVED)
                .forEach(f -> fineService.adjust(f.getId(), staff, "WAIVE", null,
                        "已还未入库申诉成立（工单 #" + c.getId() + "）：" + note));
        credit.repairForClaim(c, "申诉工单 #" + c.getId() + " 成立（" + note + "），修复此前逾期信用扣减");
    }

    private ClaimEvidence saveEvidence(Claim c, EvidenceKind kind, EvidenceSource source, String content,
                                       String refNo, String staff, String createdBy) {
        if (content == null || content.isBlank()) {
            throw ApiException.unprocessable("证据内容不能为空");
        }
        ClaimEvidence e = new ClaimEvidence();
        e.setClaim(c);
        e.setKind(kind);
        e.setSource(source);
        e.setContent(content);
        e.setRefNo(refNo);
        e.setStaff(staff);
        e.setCreatedBy(createdBy);
        e.setCreatedAt(clock.now());
        return evidences.save(e);
    }

    private Claim mustActiveClaim(Long claimId) {
        Claim c = claims.findById(claimId)
                .orElseThrow(() -> ApiException.notFound("申诉工单不存在: " + claimId));
        if (!ACTIVE_STATUSES.contains(c.getStatus())) {
            throw ApiException.conflict("工单已结案：" + c.getStatus());
        }
        return c;
    }

    private Library mustLibrary(String code) {
        return libraries.findByCode(code)
                .orElseThrow(() -> ApiException.notFound("分馆不存在: " + code));
    }

    private void requireStaff(String staff) {
        if (staff == null || staff.isBlank()) {
            throw ApiException.unprocessable("必须填写操作馆员");
        }
    }

    private EvidenceKind parseKind(String kind, EvidenceKind fallback) {
        if (kind == null || kind.isBlank()) {
            if (fallback != null) return fallback;
            throw ApiException.unprocessable("证据类型 kind 不能为空");
        }
        try {
            return EvidenceKind.valueOf(kind.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw ApiException.unprocessable("未知证据类型: " + kind);
        }
    }

    private static String append(String old, String part) {
        if (old == null || old.isBlank()) return part;
        return old + "；" + part;
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public Claim get(Long id) {
        return claims.findById(id).orElseThrow(() -> ApiException.notFound("申诉工单不存在: " + id));
    }

    // 实体返回值在事务提交后会脱离持久化上下文（open-in-view=false），
    // 故凡需读取借阅/馆藏懒加载关联的出参统一在本类事务内转为视图。

    @Transactional
    public ClaimView openClaimView(Long loanId, String kioskCode, Instant claimedReturnedAt, String note) {
        return com.example.library.web.Views.claim(openClaim(loanId, kioskCode, claimedReturnedAt, note));
    }

    @Transactional
    public ClaimView resolveScanFailFoundView(Long claimId, String foundLibraryCode, String staff,
                                              Long kioskLogId, String cameraRef, String binCountNote) {
        return com.example.library.web.Views.claim(resolveScanFailFound(claimId, foundLibraryCode, staff,
                kioskLogId, cameraRef, binCountNote));
    }

    @Transactional
    public ClaimView keepDisputedView(Long claimId, String staff, String note) {
        return com.example.library.web.Views.claim(keepDisputed(claimId, staff, note));
    }

    @Transactional
    public ClaimView confirmLibraryMissedCheckinView(Long claimId, String foundLibraryCode,
                                                     String staff, String reason) {
        return com.example.library.web.Views.claim(
                confirmLibraryMissedCheckin(claimId, foundLibraryCode, staff, reason));
    }

    @Transactional
    public ClaimView upholdReaderResponsibleView(Long claimId, String staff, String reason) {
        return com.example.library.web.Views.claim(upholdReaderResponsible(claimId, staff, reason));
    }

    @Transactional(readOnly = true)
    public List<Claim> activeClaims() {
        return claims.findByStatusInOrderByCreatedAt(ACTIVE_STATUSES);
    }

    @Transactional(readOnly = true)
    public List<ClaimView> activeClaimViews() {
        return activeClaims().stream().map(com.example.library.web.Views::claim).toList();
    }

    @Transactional(readOnly = true)
    public List<ClaimView> claimViewsForReader(String cardNo) {
        return claims.findByReaderCardNoOrderByCreatedAtDesc(cardNo).stream()
                .map(com.example.library.web.Views::claim).toList();
    }

    @Transactional(readOnly = true)
    public ClaimDetailView detailView(Long id) {
        Claim c = get(id);
        List<EvidenceView> evs = evidences.findByClaimIdOrderByCreatedAt(id).stream()
                .map(EvidenceView::from).toList();
        return ClaimDetailView.of(com.example.library.web.Views.claim(c), evs);
    }
}
