package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 读者补充证据（如还书凭条照片编号、同行人、具体投书时间） */
public record EvidenceRequest(
        @NotBlank String kind,
        @NotBlank String content
) {}
