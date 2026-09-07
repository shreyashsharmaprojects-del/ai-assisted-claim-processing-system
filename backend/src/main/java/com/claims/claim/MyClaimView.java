package com.claims.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One row of a claimant's own claim history (GET /api/claims/mine): the public facts of
 * the claim plus the current indemnity figure when approved. Never carries reserve,
 * notes, assignee, policy contact data, or coverage — the same visibility wall as the
 * single-claim status view, one list row at a time.
 */
public record MyClaimView(String claimNumber, String status, String productCode,
        LocalDate lossDate, OffsetDateTime createdAt, String decision,
        BigDecimal indemnityAmount, String decisionRemarks) {
}
