package com.claims.claim;

/** V2-4 review transition: ADVANCE (REVIEW→VERIFICATION), REJECT (close DENIED), NEED_INFO. */
public record ReviewInput(String action, String rationale, String notes, String requestedItems) {
}
