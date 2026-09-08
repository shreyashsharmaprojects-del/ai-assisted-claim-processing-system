package com.claims.claim;

/** V2-6: explicit referral to a higher authority (named senior or auto-pick). */
public record ReferInput(Long targetAdjusterId, Boolean auto, String reason) {
}
