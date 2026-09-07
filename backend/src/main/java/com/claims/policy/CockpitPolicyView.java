package com.claims.policy;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * V2-1 claimant cockpit shape: one policy row. Claimant-safe by construction — no
 * holder email, no internal fields. Remaining benefit is derived (locked rule 2):
 * {@code sum_insured − Σ prior non-rejected net payables on the policy}; null when
 * the policy carries no sum insured (legacy rows).
 */
public record CockpitPolicyView(String policyNumber, String productCode, String productFamily,
        String productDisplayName, String holderName, String status,
        BigDecimal sumInsured, BigDecimal remainingBenefit,
        LocalDate validFrom, LocalDate validTo, int coverCount) {
}
