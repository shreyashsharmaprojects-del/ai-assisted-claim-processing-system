package com.claims.claim;

/** V18: send-back body — a reason is required (it lands on the audit row). */
public record SendBackInput(String rationale) {
}
