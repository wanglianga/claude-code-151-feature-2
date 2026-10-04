package com.example.library.web.dto;

import java.time.Instant;

import com.example.library.domain.ClaimEvidence;

public record EvidenceView(
        Long id,
        String kind,
        String source,
        String content,
        String refNo,
        String staff,
        String createdBy,
        Instant createdAt
) {
    public static EvidenceView from(ClaimEvidence e) {
        return new EvidenceView(e.getId(), e.getKind().name(), e.getSource().name(),
                e.getContent(), e.getRefNo(), e.getStaff(), e.getCreatedBy(), e.getCreatedAt());
    }
}
