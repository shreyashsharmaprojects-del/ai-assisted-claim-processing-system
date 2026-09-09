package com.claims.claim;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** V22 (V3 S6): the highest closure ordinal on the claim (0 when none). */
    @Query("select coalesce(max(p.seq), 0) from Payment p where p.claimId = :claimId")
    int maxSeqForClaim(@Param("claimId") Long claimId);
}
