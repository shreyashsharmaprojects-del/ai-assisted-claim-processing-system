package com.claims.policy;

/**
 * The legacy full-book policy shape (supervisor-only since the privacy hardening —
 * it carries every customer's policy number + holder name). Structurally excludes
 * holder email and coverage — the same discipline that becomes the visibility wall:
 * fields are kept out of the DTO, not hidden in the UI.
 */
public record PolicySummary(String policyNumber, String productCode, String holderName) {
}
