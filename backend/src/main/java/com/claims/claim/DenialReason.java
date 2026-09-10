package com.claims.claim;

/**
 * V23 (V3 S8): the structured denial code on a REJECTED cover. Internal-only —
 * the claimant sees {@code decisionRemarks}, never this code (visibility wall).
 * No lookup table this slice; unknown codes are a 400 naming these values.
 */
public enum DenialReason {
    NOT_COVERED,
    EXCLUDED_PER_CLAUSE,
    ABOVE_SUB_LIMIT_EXHAUSTED,
    INSUFFICIENT_EVIDENCE,
    DUPLICATE_PRE_EXISTING,
    FRAUD_SUSPECTED_REFERRAL,
    OTHER;

    /** The 400-facing list of valid codes. */
    public static String list() {
        StringBuilder names = new StringBuilder();
        for (DenialReason reason : values()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(reason.name());
        }
        return names.toString();
    }
}
