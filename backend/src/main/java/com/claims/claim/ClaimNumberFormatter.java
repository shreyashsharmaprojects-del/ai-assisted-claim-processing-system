package com.claims.claim;

/** Claim numbers are CLM- followed by a zero-padded database sequence value. */
public final class ClaimNumberFormatter {

    private ClaimNumberFormatter() {
    }

    public static String format(long sequenceValue) {
        return "CLM-" + String.format("%06d", sequenceValue);
    }
}
