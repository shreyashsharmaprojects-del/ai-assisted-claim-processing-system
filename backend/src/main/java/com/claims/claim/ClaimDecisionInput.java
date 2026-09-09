package com.claims.claim;

import java.math.BigDecimal;

/**
 * Raw decision submission from the adjuster (slice 4). Validated by the authority gate and
 * the decision service: {@code decision} is APPROVED or DENIED, {@code rationale} is
 * required for both, and an indemnity amount is required (and only meaningful) for an
 * approval.
 */
public record ClaimDecisionInput(String decision, BigDecimal indemnityAmount, String rationale,
        Long expectedVersion) {
    /** Pre-V21 convenience: requests without a version (kept for service-internal callers). */
    public ClaimDecisionInput(String decision, BigDecimal indemnityAmount, String rationale) {
        this(decision, indemnityAmount, rationale, null);
    }
}
