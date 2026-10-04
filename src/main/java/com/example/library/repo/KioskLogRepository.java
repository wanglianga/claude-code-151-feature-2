package com.example.library.repo;

import com.example.library.domain.KioskLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface KioskLogRepository extends JpaRepository<KioskLog, Long> {

    /** 按时间窗（与可选条码/机台）核查自助机日志 */
    List<KioskLog> findByOccurredAtBetweenOrderByOccurredAtDesc(Instant from, Instant to);

    List<KioskLog> findByItemBarcodeOrderByOccurredAtDesc(String barcode);

    List<KioskLog> findByKioskCodeAndOccurredAtBetweenOrderByOccurredAtDesc(
            String kioskCode, Instant from, Instant to);
}
