package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 馆员登记核查证据（还书机日志/监控编号/书箱清点/入库记录） */
public record StaffEvidenceRequest(
        @NotBlank String staff,
        /** KIOSK_LOG / CAMERA / BIN_COUNT / CHECKIN_RECORD */
        @NotBlank String kind,
        @NotBlank String content,
        String refNo
) {}
