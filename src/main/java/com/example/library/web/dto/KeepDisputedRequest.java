package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 核查未找到、保持争议（读者可继续举证） */
public record KeepDisputedRequest(
        @NotBlank String staff,
        String note
) {}
