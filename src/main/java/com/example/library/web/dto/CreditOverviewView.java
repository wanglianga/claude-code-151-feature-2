package com.example.library.web.dto;

import java.util.List;

/** 读者借阅信用概览：当前总分 + 变动流水 */
public record CreditOverviewView(
        String cardNo,
        String name,
        int score,
        List<CreditRecordView> records
) {}
