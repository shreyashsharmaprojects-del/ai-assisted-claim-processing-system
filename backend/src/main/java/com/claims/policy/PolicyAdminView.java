package com.claims.policy;

import java.time.OffsetDateTime;

/**
 * One row of the supervisor policy-admin list (R1): every column incl. RETIRED rows, newest
 * first. Unlike {@link PolicySummary} this is a supervisor-only view, so holder email and
 * status ride along; coverage stays out (internal underwriting detail, never listed).
 */
public record PolicyAdminView(String policyNumber, String productCode, String holderName,
        String holderEmail, String status, OffsetDateTime createdAt) {
}
