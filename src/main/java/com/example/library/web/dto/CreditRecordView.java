package com.example.library.web.dto;

import java.time.Instant;

import com.example.library.domain.CreditRecord;

public record CreditRecordView(
        Long id,
        String type,
        int change,
        Long loanId,
        Long claimId,
        String reason,
        Instant createdAt
) {
    public static CreditRecordView from(CreditRecord r) {
        return new CreditRecordView(r.getId(), r.getType().name(), r.getChange(),
                r.getLoan() == null ? null : r.getLoan().getId(),
                r.getClaim() == null ? null : r.getClaim().getId(),
                r.getReason(), r.getCreatedAt());
    }
}
