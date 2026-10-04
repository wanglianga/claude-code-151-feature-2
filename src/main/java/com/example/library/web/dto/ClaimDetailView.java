package com.example.library.web.dto;

import java.util.List;

/** 申诉工单详情：工单视图 + 完整证据链 */
public record ClaimDetailView(
        ClaimView claim,
        List<EvidenceView> evidences
) {
    public static ClaimDetailView of(ClaimView claim, List<EvidenceView> evidences) {
        return new ClaimDetailView(claim, evidences);
    }
}
