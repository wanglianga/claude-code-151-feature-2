package com.example.library.web.dto;

import java.time.Instant;

public record EvidenceView(
        Long id,
        Long claimId,
        String source,
        String kind,
        String content,
        String staff,
        Instant createdAt
) {}
