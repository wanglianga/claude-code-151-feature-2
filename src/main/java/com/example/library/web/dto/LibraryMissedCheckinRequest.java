package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 馆方确认漏入库结案 */
public record LibraryMissedCheckinRequest(
        @NotBlank String staff,
        @NotBlank String foundLibraryCode,
        @NotBlank String reason
) {}
