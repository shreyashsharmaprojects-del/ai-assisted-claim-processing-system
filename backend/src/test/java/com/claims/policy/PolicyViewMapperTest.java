package com.claims.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the policy read-path shaping: deterministic ordering plus the structural
 * rule that contact/coverage data never reaches the public summary. No database, no Spring.
 */
class PolicyViewMapperTest {

    private static Policy policy(String number, String holderName) {
        return new Policy(number, "HOME", holderName, holderName.toLowerCase().replace(' ', '.') + "@example.test");
    }

    @Test
    void toSummariesSortsByPolicyNumberAscending() {
        List<Policy> policies = List.of(
                policy("POL-20002", "Grace Hopper"),
                policy("POL-10001", "Ada Lovelace"));

        List<PolicySummary> summaries = PolicyViewMapper.toSummaries(policies);

        assertEquals(List.of("POL-10001", "POL-20002"),
                summaries.stream().map(PolicySummary::policyNumber).toList());
        assertEquals("Ada Lovelace", summaries.get(0).holderName());
    }

    @Test
    void toSummariesMapsOnlyPublicFields() {
        List<PolicySummary> summaries = PolicyViewMapper.toSummaries(List.of(policy("POL-10001", "Ada Lovelace")));

        PolicySummary summary = summaries.get(0);
        assertEquals("POL-10001", summary.policyNumber());
        assertEquals("HOME", summary.productCode());
        assertEquals("Ada Lovelace", summary.holderName());
    }

    @Test
    void summaryStructurallyCarriesNoContactOrCoverageData() {
        List<String> components = Arrays.stream(PolicySummary.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertEquals(List.of("policyNumber", "productCode", "holderName"), components,
                "adding a field to the public summary is a deliberate act");
    }

    @Test
    void toSummariesReturnsEmptyListForEmptyInput() {
        List<PolicySummary> summaries = PolicyViewMapper.toSummaries(List.of());
        assertEquals(0, summaries.size());
        assertFalse(summaries.iterator().hasNext());
    }
}
