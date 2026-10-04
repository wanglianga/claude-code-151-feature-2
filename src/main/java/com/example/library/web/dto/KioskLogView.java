package com.example.library.web.dto;

import java.time.Instant;

public record KioskLogView(
        Long id,
        String machineCode,
        String libraryCode,
        String itemBarcode,
        String result,
        Instant occurredAt,
        String binNo,
        String cameraRef,
        String note
) {}
