package com.example.library.service;

import com.example.library.domain.KioskEventType;
import com.example.library.domain.KioskLog;
import com.example.library.repo.KioskLogRepository;
import com.example.library.web.ApiException;
import com.example.library.web.dto.KioskLogView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 自助还书机日志上报与核查查询 */
@Service
public class KioskService {

    private final KioskLogRepository logs;
    private final AppClock clock;

    public KioskService(KioskLogRepository logs, AppClock clock) {
        this.logs = logs;
        this.clock = clock;
    }

    @Transactional
    public KioskLog ingest(String libraryCode, String kioskCode, String eventType,
                           String itemBarcode, String readerCardNo, Instant occurredAt, String detail) {
        KioskEventType type;
        try {
            type = KioskEventType.valueOf(eventType.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw ApiException.unprocessable("未知还书机事件类型: " + eventType
                    + "（支持 SCAN_FAIL / BIN_JAM / OK）");
        }
        KioskLog l = new KioskLog();
        l.setLibraryCode(libraryCode);
        l.setKioskCode(kioskCode);
        l.setEventType(type);
        l.setItemBarcode(itemBarcode);
        l.setReaderCardNo(readerCardNo);
        l.setOccurredAt(occurredAt != null ? occurredAt : clock.now());
        l.setDetail(detail);
        return logs.save(l);
    }

    @Transactional(readOnly = true)
    public List<KioskLogView> query(String barcode, String libraryCode, Instant from, Instant to) {
        java.util.stream.Stream<KioskLog> stream;
        if (barcode != null && !barcode.isBlank()) {
            stream = logs.findByItemBarcodeOrderByOccurredAtDesc(barcode).stream();
        } else if (from != null && to != null) {
            stream = logs.findByOccurredAtBetweenOrderByOccurredAtDesc(from, to).stream();
        } else {
            stream = logs.findAll().stream()
                    .sorted(java.util.Comparator.comparing(KioskLog::getOccurredAt).reversed());
        }
        if (libraryCode != null && !libraryCode.isBlank()) {
            stream = stream.filter(l -> libraryCode.equals(l.getLibraryCode()));
        }
        return stream.map(KioskLogView::from).toList();
    }
}
