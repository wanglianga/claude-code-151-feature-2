package com.example.library.service;

import com.example.library.domain.Library;
import com.example.library.domain.SelfCheckinLog;
import com.example.library.repo.LibraryRepository;
import com.example.library.repo.SelfCheckinLogRepository;
import com.example.library.web.ApiException;
import com.example.library.web.Views;
import com.example.library.web.dto.KioskLogView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 自助还书机日志：投书即记录，扫描失败也留痕（书落书箱、待馆员清点）。
 * 申诉核查时按条码 + 时间窗或按分馆调取。
 */
@Service
public class SelfCheckinService {

    private final SelfCheckinLogRepository logs;
    private final LibraryRepository libraries;
    private final AppClock clock;

    public SelfCheckinService(SelfCheckinLogRepository logs, LibraryRepository libraries, AppClock clock) {
        this.logs = logs;
        this.libraries = libraries;
        this.clock = clock;
    }

    @Transactional
    public SelfCheckinLog record(String machineCode, String libraryCode, String barcode,
                                 String result, Instant occurredAt, String binNo,
                                 String cameraRef, String note) {
        if (!"SCAN_SUCCESS".equals(result) && !"SCAN_FAILED".equals(result)) {
            throw ApiException.unprocessable("result 仅支持 SCAN_SUCCESS / SCAN_FAILED");
        }
        Library lib = libraries.findByCode(libraryCode)
                .orElseThrow(() -> ApiException.notFound("分馆不存在: " + libraryCode));
        SelfCheckinLog l = new SelfCheckinLog();
        l.setMachineCode(machineCode);
        l.setLibrary(lib);
        l.setItemBarcode(barcode);
        l.setResult(result);
        l.setOccurredAt(occurredAt != null ? occurredAt : clock.now());
        l.setBinNo(binNo);
        l.setCameraRef(cameraRef);
        l.setNote(note);
        l.setCreatedAt(clock.now());
        return logs.save(l);
    }

    @Transactional(readOnly = true)
    public List<SelfCheckinLog> query(String barcode, String libraryCode, Instant from, Instant to) {
        Instant end = to != null ? to : clock.now();
        Instant start = from != null ? from : end.minus(30, ChronoUnit.DAYS);
        if (barcode != null && !barcode.isBlank()) {
            return logs.findByItemBarcodeAndOccurredAtBetweenOrderByOccurredAt(barcode, start, end);
        }
        if (libraryCode != null && !libraryCode.isBlank()) {
            Library lib = libraries.findByCode(libraryCode)
                    .orElseThrow(() -> ApiException.notFound("分馆不存在: " + libraryCode));
            return logs.findByLibraryIdAndOccurredAtBetweenOrderByOccurredAt(lib.getId(), start, end);
        }
        throw ApiException.unprocessable("查询需提供 barcode 或 libraryCode");
    }

    @Transactional(readOnly = true)
    public SelfCheckinLog mustLog(Long id) {
        return logs.findById(id).orElseThrow(() -> ApiException.notFound("还书机日志不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<KioskLogView> views(String barcode, String libraryCode, Instant from, Instant to) {
        return query(barcode, libraryCode, from, to).stream().map(Views::kioskLog).toList();
    }
}
