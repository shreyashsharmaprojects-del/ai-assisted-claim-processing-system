package com.claims.claim;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimCoverRepository extends JpaRepository<ClaimCover, Long> {

    List<ClaimCover> findByClaimIdOrderByIdAsc(Long claimId);
}
