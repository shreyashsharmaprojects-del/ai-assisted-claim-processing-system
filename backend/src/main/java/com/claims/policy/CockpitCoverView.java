package com.claims.policy;

import java.math.BigDecimal;

/**
 * V2-1 claimant cockpit shape: one opted cover on a policy, with its remaining
 * sub-limit (locked rule 2 — per-cover exhaustion never touches other covers).
 * Claimant-safe: limits and benefits only, no internal figures.
 */
public record CockpitCoverView(String coverCode, String displayName, BigDecimal subLimit,
        BigDecimal deductibleDefault, BigDecimal remainingSubLimit) {
}
