package com.claims.claim;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    Optional<Claim> findByClaimNumber(String claimNumber);

    /**
     * Row-locked read used by the decision endpoint so two concurrent decisions on one
     * claim serialize: the second sees the CLOSED state and is rejected instead of racing
     * into the single-payment unique constraint (slice 4).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.claimNumber = :claimNumber")
    Optional<Claim> findByClaimNumberForUpdate(@Param("claimNumber") String claimNumber);

    /**
     * V24 (V3 S9): every claim this subject filed (the export + the erasure walk).
     * Ordered by id so exports are deterministic.
     */
    List<Claim> findByClaimantSubOrderByIdAsc(String claimantSub);

    /**
     * V24 (V3 S9): how many distinct subjects filed on a policy — more than one
     * means the policy is shared and erasure must refuse (400), because redacting
     * the holder row would destroy another living subject's PII.
     */
    @Query("select count(distinct c.claimantSub) from Claim c where c.policyId = :policyId")
    long countDistinctClaimantSubsByPolicyId(@Param("policyId") Long policyId);

    /** V24 (V3 S9): every distinct policy the subject's claims touch (export scope). */
    @Query("select distinct c.policyId from Claim c where c.claimantSub = :claimantSub")
    List<Long> findDistinctPolicyIdsByClaimantSub(
            @Param("claimantSub") String claimantSub);
}
