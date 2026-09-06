package com.claims.claim;

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
}
