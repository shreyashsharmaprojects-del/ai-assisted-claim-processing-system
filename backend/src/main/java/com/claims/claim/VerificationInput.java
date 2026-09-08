package com.claims.claim;

/** V2-4: open a verification of the given type (DIGITAL | PHYSICAL). */
public record VerificationInput(String type, String notes) {
}
