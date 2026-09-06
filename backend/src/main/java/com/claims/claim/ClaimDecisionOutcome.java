package com.claims.claim;

/**
 * The decision result riding back to the controller. The {@code view} is what gets
 * serialized to the adjuster; the holder identity rides along only so the controller can
 * send the after-commit decision email on closure — never on an escalation (no decision was
 * made).
 */
public record ClaimDecisionOutcome(ClaimDecisionView view, String holderName,
        String holderEmail) {
}
