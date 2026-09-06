package com.claims.claim;

import java.time.OffsetDateTime;

import tools.jackson.databind.JsonNode;

/**
 * One append-only audit row for a claim, as read by the supervisor (slice 7). The payloads
 * ({@code before}/{@code after}) are the stored JSONB snapshots, parsed back to JSON so the
 * caller sees exactly what was recorded. Chronological by id (append order).
 */
public record AuditEntryView(Long id, String action, String actorSub, String rationale,
        OffsetDateTime createdAt, JsonNode before, JsonNode after) {
}
