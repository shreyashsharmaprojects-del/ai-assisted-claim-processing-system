package com.claims.ai.analysis;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimAiAnalysisRepository extends JpaRepository<ClaimAiAnalysis, Long> {

    /** Dedupe read: one advisory row per (claim, claim version). */
    Optional<ClaimAiAnalysis> findByClaimIdAndClaimVersion(Long claimId, Long claimVersion);

    /** Latest advisory for a claim (max claim_version). */
    Optional<ClaimAiAnalysis> findFirstByClaimIdOrderByClaimVersionDesc(Long claimId);
}
