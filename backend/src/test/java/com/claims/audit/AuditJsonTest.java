package com.claims.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * The audit log is append-only and compliance-critical; payloads must survive hostile
 * text (quotes, backslashes, newlines) without corrupting the JSONB row.
 */
class AuditJsonTest {

    @Test
    void hostileTextRoundTripsAsValidJson() throws Exception {
        String remarks = "quote \" backslash \\ newline \n tab \t";

        String json = AuditJson.of(Map.of(
                "claimNumber", "CLM-000001",
                "remarks", remarks));

        ObjectMapper mapper = new ObjectMapper();
        var tree = mapper.readTree(json);
        assertEquals("CLM-000001", tree.get("claimNumber").asText());
        assertEquals(remarks, tree.get("remarks").asText());
    }
}
