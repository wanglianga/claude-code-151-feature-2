package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 馆员录入单项核查结论。
 * result：MATCH 对得上 / NOT_FOUND 无记录 / NA 不适用。
 * logId：还书机核查对得上时关联的自助机日志 ID；ref：监控点位/录像编号。
 */
public record ClaimCheckRequest(
        @NotBlank String staff,
        @NotBlank String result,
        Long logId,
        String ref,
        String note
) {}
