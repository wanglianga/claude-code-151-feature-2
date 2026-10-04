package com.example.library.web.dto;

import java.time.Instant;

/**
 * 读者“已还未入库”申诉请求。
 *
 * @param kioskCode         读者申报使用的自助还书机编号（可空）
 * @param claimedReturnedAt 读者申报的还书时刻（可空）
 * @param note              情况说明
 */
public record ClaimRequest(
        String kioskCode,
        Instant claimedReturnedAt,
        String note
) {}
