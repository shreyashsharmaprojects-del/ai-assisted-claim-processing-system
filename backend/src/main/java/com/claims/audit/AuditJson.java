package com.claims.audit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes audit payloads to JSON text. The audit log is compliance-critical and
 * append-only: payloads are always built through a serializer, never by string
 * concatenation, so a value containing quotes or newlines cannot corrupt a row
 * (jsonb cast failure would roll back the very claim being logged).
 *
 * <p>Boot 4 ships Jackson 3 (tools.jackson), which is what the HTTP layer uses too.
 */
public final class AuditJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditJson() {
    }

    public static String of(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Audit payload is not JSON-serializable", ex);
        }
    }
}
