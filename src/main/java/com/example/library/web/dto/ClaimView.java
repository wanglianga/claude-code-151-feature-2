package com.example.library.web.dto;

import java.time.Instant;

public record ClaimView(
        Long id,
        Long loanId,
        String readerCardNo,
        String itemBarcode,
        String title,
        String status,
        String kioskCode,
        Instant claimedReturnedAt,
        Instant heldAt,
        String outcome,
        Boolean creditRepaired,
        String note,
        String staff,
        Instant createdAt,
        Instant resolvedAt
) {}
