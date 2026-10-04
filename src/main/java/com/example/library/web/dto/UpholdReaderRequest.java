package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 终判读者责任 */
public record UpholdReaderRequest(
        @NotBlank String staff,
        @NotBlank String reason
) {}
