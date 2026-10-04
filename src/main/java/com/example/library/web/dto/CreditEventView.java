package com.example.library.web.dto;

import java.time.Instant;

public record CreditEventView(
        Long id,
        String type,
        int points,
        String reason,
        String staff,
        Long loanId,
        Long claimId,
        Instant createdAt
) {}
