package com.claims.policy;

import java.util.Comparator;
import java.util.List;

/**
 * Pure mapping logic from persisted policy rows to the SUPERVISOR-ONLY legacy book
 * shape — no database, no framework. Kept as a plain static utility so the sorting
 * and field-shaping rules can be unit-tested without booting Spring.
 *
 * <p>This shape still carries every customer's policy number + holder name, so it
 * must never be served to claimants or adjusters — see SecurityConfig (supervisor
 * only) and CockpitService (claimants read only their own rows).
 */
public final class PolicyViewMapper {

    private PolicyViewMapper() {
    }

    public static List<PolicySummary> toSummaries(List<Policy> policies) {
        return policies.stream()
                .sorted(Comparator.comparing(Policy::getPolicyNumber))
                .map(policy -> new PolicySummary(
                        policy.getPolicyNumber(),
                        policy.getProductCode(),
                        policy.getHolderName()))
                .toList();
    }
}
