package com.example.library.repo;

import com.example.library.domain.CreditRecord;
import com.example.library.domain.CreditType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditRecordRepository extends JpaRepository<CreditRecord, Long> {
    List<CreditRecord> findByReaderIdOrderByCreatedAtDesc(Long readerId);

    boolean existsByReaderIdAndLoanIdAndType(Long readerId, Long loanId, CreditType type);
}
