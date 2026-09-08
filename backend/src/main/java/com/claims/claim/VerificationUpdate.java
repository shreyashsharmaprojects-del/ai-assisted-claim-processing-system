package com.claims.claim;

/** V2-4: update a verification row (any subset; COMPLETE requires outcome + notes). */
public record VerificationUpdate(String status, String type, String outcome, String notes,
        String evidenceRefs) {
}
