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
        /** 申诉核查期间暂缓日增长 */
        boolean growthHold,
        String reason,
        String ruleBasis,
        Instant createdAt,
        Instant resolvedAt
) {}
