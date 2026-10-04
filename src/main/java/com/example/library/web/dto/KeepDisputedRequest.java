package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 首轮核查未找到书：不立即判赔，争议保持（DISPUTED），请读者继续提供证据 */
public record KeepDisputedRequest(
        @NotBlank String staff,
        String note
) {}
