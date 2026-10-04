package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 馆员补充核查证据/工作记录 */
public record StaffEvidenceRequest(
        @NotBlank String staff,
        @NotBlank String kind,
        @NotBlank String content
) {}
