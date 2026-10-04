package com.example.library.repo;

import com.example.library.domain.Claim;
import com.example.library.domain.ClaimStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClaimRepository extends JpaRepository<Claim, Long> {
    Optional<Claim> findFirstByLoanIdAndStatusIn(Long loanId, Collection<ClaimStatus> statuses);

    Optional<Claim> findFirstByLoanIdAndStatus(Long loanId, ClaimStatus status);

    List<Claim> findByStatusOrderByCreatedAt(ClaimStatus status);

    List<Claim> findByStatusInOrderByCreatedAt(Collection<ClaimStatus> statuses);
}
