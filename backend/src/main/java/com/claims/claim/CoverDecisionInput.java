package com.claims.claim;

import java.math.BigDecimal;
import java.util.List;

/**
 * V2-6: per-cover decision. On claims with covers every entry carries a per-cover
 * decision; on legacy no-cover claims the body keeps the V1 single-figure shape
 * ({@code decision} + {@code indemnityAmount}), byte-identical to before.
 */
public record CoverDecisionInput(String decision, BigDecimal indemnityAmount, String rationale,
        List<CoverOutcome> covers) {
    public record CoverOutcome(String coverCode, String decision, BigDecimal approvedAmount,
            BigDecimal deductibleAmount, BigDecimal adjustmentAmount, String remarks) {
    }
}
