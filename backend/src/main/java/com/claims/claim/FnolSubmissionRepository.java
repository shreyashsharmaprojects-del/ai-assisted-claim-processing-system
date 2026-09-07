package com.claims.claim;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface FnolSubmissionRepository extends JpaRepository<FnolSubmission, Long> {

    /** How many FNOLs this claimant filed at or after {@code since}. */
    @Query("select count(s) from FnolSubmission s where s.claimantSub = :sub and s.createdAt >= :since")
    long countSince(@Param("sub") String claimantSub, @Param("since") Instant since);
}
