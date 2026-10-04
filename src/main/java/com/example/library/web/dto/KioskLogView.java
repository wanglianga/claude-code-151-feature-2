package com.example.library.web.dto;

import java.time.Instant;

import com.example.library.domain.KioskLog;

public record KioskLogView(
        Long id,
        String libraryCode,
        String kioskCode,
        String eventType,
        String itemBarcode,
        String readerCardNo,
        Instant occurredAt,
        String detail
) {
    public static KioskLogView from(KioskLog l) {
        return new KioskLogView(l.getId(), l.getLibraryCode(), l.getKioskCode(),
                l.getEventType().name(), l.getItemBarcode(), l.getReaderCardNo(),
                l.getOccurredAt(), l.getDetail());
    }
}
