package com.claims.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Append-only audit writer (slice 1+, C1). Every status change and decision is logged with
 * actor and timestamp; the application never issues UPDATE/DELETE on audit_log — any
 * correction is a new row. Payloads are stored as JSONB via explicit casts.
 */
@Component
public class AuditLogWriter {

    private static final String INSERT = """
            INSERT INTO audit_log (actor_sub, action, entity_type, entity_id, before, after, rationale)
            VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public AuditLogWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void append(String actorSub, String action, String entityType, Long entityId,
            String beforeJson, String afterJson, String rationale) {
        jdbcTemplate.update(INSERT, actorSub, action, entityType, entityId, beforeJson, afterJson, rationale);
    }
}
