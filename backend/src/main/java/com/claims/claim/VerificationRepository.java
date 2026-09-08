package com.claims.claim;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationRepository extends JpaRepository<Verification, Long> {

    List<Verification> findByClaimIdOrderByIdAsc(Long claimId);

    Optional<Verification> findByIdAndClaimId(Long id, Long claimId);
}
