package com.claims.claim;

import java.math.BigDecimal;

/** V2-2 claimant-safe cover line for the filing picker: limits only, no internals. */
public record FilingCoverView(String coverCode, String displayName, BigDecimal subLimit) {
}
