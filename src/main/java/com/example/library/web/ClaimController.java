package com.example.library.web;

import com.example.library.service.LoanService;
import com.example.library.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/claims")
@Tag(name = "5. “已还未入库”申诉",
        description = "读者收到逾期提醒但称已在自助机还书：核查还书机日志/监控编号/书箱清点/馆员入库记录；"
                + "处理期间逾期罚款冻结、信用不立即恢复；结案按读者责任或馆方漏入库分别处理")
public class ClaimController {

    private final LoanService loans;

    public ClaimController(LoanService loans) {
        this.loans = loans;
    }

    @PostMapping("/loans/{loanId}")
    @Operation(summary = "读者申诉已还未入库：受理即冻结逾期罚款（不撤销、不增长），借阅进争议、信用暂不恢复")
    public ClaimView claim(@PathVariable Long loanId, @RequestBody(required = false) ClaimOpenRequest req) {
        return loans.claimView(loanId,
                req == null ? null : req.note(),
                req == null ? null : req.machineCode());
    }

    @GetMapping("/open")
    @Operation(summary = "（馆员）待处理工单：核查中 OPEN 与争议保持 DISPUTED")
    public List<ClaimView> open() {
        return loans.openClaimViews();
    }

    @GetMapping("/readers/{cardNo}")
    @Operation(summary = "读者本人的全部申诉工单")
    public List<ClaimView> mine(@PathVariable String cardNo) {
        return loans.claimViewsForReader(cardNo);
    }

    @GetMapping("/{claimId}")
    @Operation(summary = "申诉工单详情（含四项核查结论、冻结金额、最终责任方）")
    public ClaimView detail(@PathVariable Long claimId) {
        return loans.claimDetailView(claimId);
    }

    @GetMapping("/{claimId}/evidence")
    @Operation(summary = "申诉证据链（读者举证 + 馆员核查记录，按时间排序）")
    public List<EvidenceView> evidence(@PathVariable Long claimId) {
        return loans.evidenceViews(claimId);
    }

    @PostMapping("/{claimId}/evidence")
    @Operation(summary = "读者继续提供证据（OPEN/DISPUTED 均可；书未找到争议保持时可继续举证）")
    public ClaimView addEvidence(@PathVariable Long claimId, @Valid @RequestBody EvidenceRequest req) {
        return loans.readerEvidenceView(claimId, req.kind(), req.content());
    }

    @PostMapping("/{claimId}/staff-evidence")
    @Operation(summary = "（馆员）补充核查工作记录")
    public ClaimView addStaffEvidence(@PathVariable Long claimId, @Valid @RequestBody StaffEvidenceRequest req) {
        return loans.staffEvidenceView(claimId, req.staff(), req.kind(), req.content());
    }

    @PostMapping("/{claimId}/checks/{channel}")
    @Operation(summary = "（馆员）录入四项核查之一：channel=KIOSK 还书机日志 / CAMERA 监控编号 / "
            + "BIN 书箱清点 / SHELF 馆员入库记录；result=MATCH/NOT_FOUND/NA")
    public ClaimView check(
            @PathVariable Long claimId,
            @Parameter(description = "KIOSK / CAMERA / BIN / SHELF") @PathVariable("channel") String channel,
            @Valid @RequestBody ClaimCheckRequest req) {
        return loans.recordCheckView(claimId, channel, req.staff(), req.result(),
                req.logId(), req.ref(), req.note());
    }

    @PostMapping("/{claimId}/scan-fail-found")
    @Operation(summary = "（馆员）自助机扫描失败但书已在书箱/架上找到：撤罚、修正库存、借阅结案、信用修复")
    public ClaimView scanFailFound(@PathVariable Long claimId, @Valid @RequestBody ScanFailFoundRequest req) {
        return loans.scanFailFoundView(claimId, req.staff(), req.foundLibraryCode(), req.binNo(), req.note());
    }

    @PostMapping("/{claimId}/not-found")
    @Operation(summary = "（馆员）首轮核查未找到：不判赔，争议保持 DISPUTED，罚款继续冻结，等待读者新证据")
    public ClaimView notFoundKeepDispute(@PathVariable Long claimId, @Valid @RequestBody KeepDisputedRequest req) {
        return loans.keepDisputedView(claimId, req.staff(), req.note());
    }

    @PostMapping("/{claimId}/reopen")
    @Operation(summary = "（馆员）读者提交新证据后重开工单（DISPUTED → OPEN）继续核查")
    public ClaimView reopen(@PathVariable Long claimId, @Valid @RequestBody ReopenClaimRequest req) {
        return loans.reopenView(claimId, req.staff(), req.note());
    }

    @PostMapping("/{claimId}/reader-liable")
    @Operation(summary = "（馆员）最终确认读者责任：bookFound=true 书已还回则逾期按原到期日计、信用不恢复；"
            + "bookFound=false 则按丢书赔书并扣信用")
    public ClaimView readerLiable(@PathVariable Long claimId, @Valid @RequestBody ReaderLiableRequest req) {
        return loans.readerLiableView(claimId, req.staff(), req.bookFound(),
                req.foundLibraryCode(), req.reason());
    }

    @PostMapping("/{claimId}/library-liable")
    @Operation(summary = "（馆员）最终确认馆方漏入库/设备故障：撤罚、入库流转、信用记录同案修复")
    public ClaimView libraryLiable(@PathVariable Long claimId, @Valid @RequestBody LibraryLiableRequest req) {
        return loans.libraryLiableView(claimId, req.staff(), req.foundLibraryCode(), req.reason());
    }

    @PostMapping("/{claimId}/found")
    @Operation(summary = "（馆员，兼容）在馆找到补录入库：按馆方流程原因结案、撤罚、修复信用")
    public ClaimView found(@PathVariable Long claimId, @Valid @RequestBody ResolveClaimRequest req) {
        return loans.resolveFoundView(claimId, req.foundLibraryCode(), req.staff());
    }

    @PostMapping("/{claimId}/waive")
    @Operation(summary = "（馆员，兼容）馆方豁免：必填理由，图书入库流转、撤罚、信用修复")
    public ClaimView waive(@PathVariable Long claimId, @Valid @RequestBody ResolveClaimRequest req) {
        return loans.waiveView(claimId, req.staff(), req.reason(), req.foundLibraryCode());
    }
}
