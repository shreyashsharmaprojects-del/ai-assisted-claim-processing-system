package com.claims.claim;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * V2-2 claimant-safe cover line: what was filed plus the fileable outcome only.
 * Assessed/approved/deductible/net figures, verifier identity, proposals and internal
 * notes never enter this shape (visibility wall). {@code aboveLimit} is derived
 * ({@code claimed > sub-limit}) — informational at filing, never a block.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaimantCoverView(String coverCode, String displayName, BigDecimal claimedAmount,
        BigDecimal subLimit, boolean aboveLimit) {
}
