package com.example.library.web;

import com.example.library.service.ClaimService;
import com.example.library.web.Views;
import com.example.library.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/claims")
@Tag(name = "5. “已还未入库”申诉",
        description = "读者收到逾期提醒但称已在自助机还书：馆员核查还书机日志、监控编号、书箱清点与馆员入库记录；"
                + "核查期间罚款暂缓增长、信用不立即恢复")
public class ClaimController {

    private final ClaimService claims;

    public ClaimController(ClaimService claims) {
        this.claims = claims;
    }

    // ---------------- 读者侧 ----------------

    @PostMapping("/loans/{loanId}")
    @Operation(summary = "读者发起已还未入库申诉：借阅进入争议状态，逾期罚款暂缓增长（不减免），信用暂不恢复")
    public ClaimView open(@PathVariable Long loanId, @RequestBody(required = false) ClaimRequest req) {
        if (req == null) {
            return claims.openClaimView(loanId, null, null, null);
        }
        return claims.openClaimView(loanId, req.kioskCode(), req.claimedReturnedAt(), req.note());
    }

    @PostMapping("/{claimId}/evidences")
    @Operation(summary = "读者继续提供证据；申诉处于 DISPUTED（未找到保持争议）时新证据使工单重新核查")
    public EvidenceView addEvidence(@PathVariable Long claimId,
                                    @Valid @RequestBody ReaderEvidenceRequest req) {
        return EvidenceView.from(claims.addReaderEvidence(
                claimId, req.kind(), req.content(), req.refNo()));
    }

    @GetMapping("/readers/{cardNo}")
    @Operation(summary = "读者查看自己的申诉工单")
    public List<ClaimView> mine(@PathVariable String cardNo) {
        return claims.claimViewsForReader(cardNo);
    }

    @GetMapping("/{claimId}")
    @Operation(summary = "申诉工单详情（含完整证据链：机日志/监控/书箱清点/入库记录/读者举证）")
    public ClaimDetailView detail(@PathVariable Long claimId) {
        return claims.detailView(claimId);
    }

    // ---------------- 馆员侧 ----------------

    @GetMapping("/open")
    @Operation(summary = "（馆员）待处理申诉工单（OPEN 核查中 + DISPUTED 保持争议）")
    public List<ClaimView> open() {
        return claims.activeClaimViews();
    }

    @PostMapping("/{claimId}/evidences/staff")
    @Operation(summary = "（馆员）登记核查证据：KIOSK_LOG 机日志 / CAMERA 监控编号 / BIN_COUNT 书箱清点 / CHECKIN_RECORD 入库记录")
    public EvidenceView addStaffEvidence(@PathVariable Long claimId,
                                         @Valid @RequestBody StaffEvidenceRequest req) {
        return EvidenceView.from(claims.addStaffEvidence(
                claimId, req.kind(), req.content(), req.refNo(), req.staff()));
    }

    @PostMapping("/{claimId}/kiosk-logs/{kioskLogId}")
    @Operation(summary = "（馆员）把指定还书机日志登记为该申诉的机日志证据")
    public EvidenceView attachKioskLog(@PathVariable Long claimId, @PathVariable Long kioskLogId,
                                       @Valid @RequestBody StaffActionRequest req) {
        return EvidenceView.from(claims.attachKioskLog(claimId, kioskLogId, req.staff()));
    }

    @PostMapping("/{claimId}/scan-fail-found")
    @Operation(summary = "（馆员）自助机扫描失败但书已找到：登记证据链，撤销逾期罚款、补办入库修正库存、修复信用")
    public ClaimView scanFailFound(@PathVariable Long claimId,
                                   @Valid @RequestBody ScanFailFoundRequest req) {
        return claims.resolveScanFailFoundView(claimId, req.foundLibraryCode(), req.staff(),
                req.kioskLogId(), req.cameraRef(), req.binCountNote());
    }

    @PostMapping("/{claimId}/keep-disputed")
    @Operation(summary = "（馆员）暂未找到：不判读者责任，保持争议 DISPUTED，读者可继续举证；罚款继续暂缓、信用继续暂扣")
    public ClaimView keepDisputed(@PathVariable Long claimId,
                                  @Valid @RequestBody KeepDisputedRequest req) {
        return claims.keepDisputedView(claimId, req.staff(), req.note());
    }

    @PostMapping("/{claimId}/library-missed-checkin")
    @Operation(summary = "（馆员）确认馆方漏入库：豁免罚款、图书入库流转、信用记录同步修复")
    public ClaimView libraryMissed(@PathVariable Long claimId,
                                   @Valid @RequestBody LibraryMissedCheckinRequest req) {
        return claims.confirmLibraryMissedCheckinView(
                claimId, req.foundLibraryCode(), req.staff(), req.reason());
    }

    @PostMapping("/{claimId}/uphold-reader")
    @Operation(summary = "（馆员）终判读者责任：图书置 LOST，逾期罚款解除暂缓并按原到期日补算，生成赔书单，信用不恢复")
    public ClaimView upholdReader(@PathVariable Long claimId,
                                  @Valid @RequestBody UpholdReaderRequest req) {
        return claims.upholdReaderResponsibleView(claimId, req.staff(), req.reason());
    }
}
