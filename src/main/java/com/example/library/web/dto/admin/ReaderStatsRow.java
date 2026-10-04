package com.example.library.web.dto.admin;

public record ReaderStatsRow(
        String cardNo,
        String name,
        long activeHolds,
        long activeLoans,
        long overdueLoans,
        long openClaims,
        long openFines,
        double openFineAmount,
        int creditScore
) {}
