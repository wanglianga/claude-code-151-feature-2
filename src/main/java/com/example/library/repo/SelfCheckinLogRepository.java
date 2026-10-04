package com.example.library.repo;

import com.example.library.domain.SelfCheckinLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SelfCheckinLogRepository extends JpaRepository<SelfCheckinLog, Long> {

    List<SelfCheckinLog> findByItemBarcodeAndOccurredAtBetweenOrderByOccurredAt(
            String barcode, Instant from, Instant to);

    List<SelfCheckinLog> findByLibraryIdAndOccurredAtBetweenOrderByOccurredAt(
            Long libraryId, Instant from, Instant to);
}
