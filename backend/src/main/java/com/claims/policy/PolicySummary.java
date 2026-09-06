package com.claims.policy;

/**
 * The public shape of a policy on the skeleton page. Structurally excludes holder email
 * and coverage — the same discipline that later becomes the visibility wall: fields are
 * kept out of the DTO, not hidden in the UI.
 */
public record PolicySummary(String policyNumber, String productCode, String holderName) {
}
