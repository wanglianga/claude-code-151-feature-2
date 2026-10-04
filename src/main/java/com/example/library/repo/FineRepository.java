package com.example.library.repo;

import com.example.library.domain.Fine;
import com.example.library.domain.FineStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FineRepository extends JpaRepository<Fine, Long> {
    List<Fine> findByReaderCardNoOrderByCreatedAtDesc(String cardNo);

    List<Fine> findByLoanId(Long loanId);

    List<Fine> findByTaskId(Long taskId);

    List<Fine> findByStatusOrderByCreatedAt(FineStatus status);

    List<Fine> findByFrozenClaimId(Long frozenClaimId);
}
