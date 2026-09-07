package com.claims.api;

/**
 * V2-2 duplicate FNOL: same policy + loss date + cover set within 24h. Surfaces as
 * 409 with the existing claim number so the claimant references it instead of
 * filing twice.
 */
public class DuplicateFnolException extends RuntimeException {

    private final String claimNumber;

    public DuplicateFnolException(String claimNumber) {
        super("A claim with the same policy, loss date and covers was already filed as "
                + claimNumber + " within the last 24 hours.");
        this.claimNumber = claimNumber;
    }

    public String getClaimNumber() {
        return claimNumber;
    }
}
