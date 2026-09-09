package com.claims.claim;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimDocumentCheckRepository extends JpaRepository<ClaimDocumentCheck, Long> {

    List<ClaimDocumentCheck> findByClaimIdOrderByIdAsc(Long claimId);

    Optional<ClaimDocumentCheck> findByIdAndClaimId(Long id, Long claimId);

    Optional<ClaimDocumentCheck> findByClaimIdAndRequiredDocumentId(Long claimId,
            Long requiredDocumentId);
}
