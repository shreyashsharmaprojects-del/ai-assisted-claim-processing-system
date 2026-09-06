package com.claims.queue;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The internal shape of a claim on queue surfaces (adjuster "my queue" and supervisor
 * team queue). This is an internal DTO — never returned toward a claimant. The assignee
 * is the display name (null for unassigned claims), so a supervisor can see who holds
 * each claim.
 */
public record QueueClaimView(String claimNumber, String status, String level,
        String policyNumber, LocalDate lossDate, String lossLocation, String lossDescription,
        OffsetDateTime createdAt, String assignedTo) {
}
