package com.claims.policy;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * V2-1 claimant cockpit shape: one policy row. Claimant-safe by construction — no
 * internal fields. The holder email rides along because it IS the caller's own
 * link key (policy.holder_email = account email): the FNOL form pre-fills its
 * identity fields from the caller's own row, so filing from a policy never
 * re-asks for what the cockpit already proved. A row is only ever served to the
 * account it belongs to (see CockpitService), so this leaks nothing
 * cross-customer. Remaining benefit is derived (locked rule 2):
 * {@code sum_insured − Σ prior non-rejected net payables on the policy}; null when
 * the policy carries no sum insured (legacy rows).
 */
public record CockpitPolicyView(String policyNumber, String productCode, String productFamily,
        String productDisplayName, String holderName, String holderEmail, String status,
        BigDecimal sumInsured, BigDecimal remainingBenefit,
        LocalDate validFrom, LocalDate validTo, int coverCount) {
}
