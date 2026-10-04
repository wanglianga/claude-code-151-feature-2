package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * 自助还书机日志录入（设备/运维上报）。
 * result=SCAN_FAILED 时条码可能无法解析（itemBarcode 可空），但书箱号、监控编号保留，供申诉比对。
 */
public record KioskLogRequest(
        @NotBlank String machineCode,
        @NotBlank String libraryCode,
        String itemBarcode,
        @NotBlank String result,
        Instant occurredAt,
        String binNo,
        String cameraRef,
        String note
) {}
