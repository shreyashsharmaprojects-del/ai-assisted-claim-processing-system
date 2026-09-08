package com.claims.claim;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * V2-2 claimant-safe cover line: what was filed plus the fileable outcome only.
 * Assessed/deductible/adjustment figures, verifier identity, proposals and internal
 * notes never enter this shape (visibility wall). {@code aboveLimit} is derived
 * ({@code claimed > sub-limit}) — informational at filing, never a block.
 *
 * <p>V2-5 grows the closure outcome: {@code decision} (PENDING until decided),
 * {@code approvedAmount} (only when APPROVED) and {@code decisionRemarks}
 * (claimant-visible, e.g. a rejection's rationale). Assessed/deductible/adjustment
 * stay internal — the claimant sees the approved figure and (on closure) the net
 * payable total on the parent view, never the arithmetic behind it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaimantCoverView(String coverCode, String displayName, BigDecimal claimedAmount,
        BigDecimal subLimit, boolean aboveLimit, String decision, BigDecimal approvedAmount,
        String decisionRemarks) {

    /** V2-2 filing shape: outcome not yet known. */
    public ClaimantCoverView(String coverCode, String displayName, BigDecimal claimedAmount,
            BigDecimal subLimit, boolean aboveLimit) {
        this(coverCode, displayName, claimedAmount, subLimit, aboveLimit, null, null, null);
    }
}
