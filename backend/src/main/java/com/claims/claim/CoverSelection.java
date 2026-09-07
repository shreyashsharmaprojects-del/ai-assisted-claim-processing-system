package com.claims.claim;

import java.math.BigDecimal;

/**
 * V2-2: one per-cover selection at FNOL — the cover code plus the claimed amount.
 * Above-sub-limit amounts are legal (locked rule 1: limits flag, never block filing);
 * validation of shape/duplicates/opted-status lives in ClaimService.
 */
public record CoverSelection(String coverCode, BigDecimal claimedAmount) {
}
