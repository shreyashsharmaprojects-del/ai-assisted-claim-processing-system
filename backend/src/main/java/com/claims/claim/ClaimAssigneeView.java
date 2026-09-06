package com.claims.claim;

/**
 * What a supervisor reassignment produced (slice 7): the claim and who now holds it. An
 * internal shape — only ever returned to a supervisor.
 */
public record ClaimAssigneeView(String claimNumber, String status, String level,
        String assignedTo) {
}
