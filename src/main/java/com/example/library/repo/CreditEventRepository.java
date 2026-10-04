package com.example.library.repo;

import com.example.library.domain.CreditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditEventRepository extends JpaRepository<CreditEvent, Long> {
    List<CreditEvent> findByReaderIdOrderByCreatedAtDesc(Long readerId);

    List<CreditEvent> findByReaderCardNoOrderByCreatedAtDesc(String cardNo);
}
