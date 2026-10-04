package com.example.library.domain;

/** 申诉单项核查结论：待核 / 对得上（有记录）/ 对不上（无记录）/ 不适用 */
public enum ClaimCheck {
    PENDING, MATCH, NOT_FOUND, NA
}
