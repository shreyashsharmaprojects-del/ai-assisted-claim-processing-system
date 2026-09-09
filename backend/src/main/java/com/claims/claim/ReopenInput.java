package com.claims.claim;

/**
 * V22 (V3 S6): supervisor reopen of a closed claim. {@code rationale} (≥ 20 chars)
 * is the audit/outbox record of why; {@code expectedVersion} is the S5
 * compare-and-swap token.
 */
public record ReopenInput(String rationale, Long expectedVersion) {
}
