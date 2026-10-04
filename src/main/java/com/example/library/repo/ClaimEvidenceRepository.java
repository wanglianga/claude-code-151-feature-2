package com.example.library.repo;

import com.example.library.domain.ClaimEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClaimEvidenceRepository extends JpaRepository<ClaimEvidence, Long> {
    List<ClaimEvidence> findByClaimIdOrderByCreatedAt(Long claimId);
}
