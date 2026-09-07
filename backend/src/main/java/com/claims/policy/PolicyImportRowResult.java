package com.claims.policy;

/**
 * One row of a CSV import summary (R1): the 1-based data-row number, the policy number as
 * supplied, whether the row persisted, and the human-readable error for failed rows.
 * The endpoint always returns HTTP 200 with the full summary — never a bare 500 on row 400.
 */
public record PolicyImportRowResult(int row, String policyNumber, boolean ok, String error) {
}
