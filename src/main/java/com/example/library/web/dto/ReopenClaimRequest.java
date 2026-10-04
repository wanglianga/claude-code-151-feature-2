package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 读者提交新证据后馆员重开工单（DISPUTED → OPEN）继续核查 */
public record ReopenClaimRequest(
        @NotBlank String staff,
        String note
) {}
