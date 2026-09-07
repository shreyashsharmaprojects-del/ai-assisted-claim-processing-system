package com.claims.policy;

import tools.jackson.databind.JsonNode;

/**
 * Supervisor create-policy payload: number + product + holder identity + coverage. Coverage
 * arrives as a JSON object (or is omitted → {@code {}}); anything non-object is a 400.
 */
public record CreatePolicyRequest(String policyNumber, String productCode, String holderName,
        String holderEmail, JsonNode coverage) {
}
