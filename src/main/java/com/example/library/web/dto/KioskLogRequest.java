package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/** 自助还书机设备上报日志（SCAN_FAIL / BIN_JAM / OK） */
public record KioskLogRequest(
        @NotBlank String libraryCode,
        @NotBlank String kioskCode,
        @NotBlank String eventType,
        String itemBarcode,
        String readerCardNo,
        /** 缺省取服务端当前时间 */
        Instant occurredAt,
        String detail
) {}
