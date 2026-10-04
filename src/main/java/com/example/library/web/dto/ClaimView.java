package com.example.library.web.dto;

import java.time.Instant;

/**
 * “已还未入库”申诉工单视图，含四类核查结论（还书机日志/监控/书箱清点/入库记录）、
 * 罚款冻结时刻与最终责任方。
 */
public record ClaimView(
        Long id,
        Long loanId,
        String readerCardNo,
        String itemBarcode,
        String title,
        String status,
        String note,
        String staff,
        Instant createdAt,
        Instant resolvedAt,

        String machineCode,

        String kioskCheck,
        Long kioskLogId,
        String kioskNote,
        String cameraRef,
        String cameraCheck,
        String cameraNote,
        String binCheck,
        Instant binCountAt,
        String binNote,
        String shelfCheck,
        Instant shelfRecordedAt,
        String shelfNote,

        Instant frozenAt,
        String liability,
        Instant closedAt,

        String loanStatus,
        Instant dueDate,
        boolean fineFrozen,
        java.math.BigDecimal frozenFineAmount
) {}
