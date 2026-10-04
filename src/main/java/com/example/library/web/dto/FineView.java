package com.example.library.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record FineView(
        Long id,
        String readerCardNo,
        Long loanId,
        Long taskId,
        String type,
        String chargedTo,
        BigDecimal amount,
        String status,
        String reason,
        String ruleBasis,
        Instant createdAt,
        Instant resolvedAt,
        boolean frozen,
        Long frozenClaimId
) {}
