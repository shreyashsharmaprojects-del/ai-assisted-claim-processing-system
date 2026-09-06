package com.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The claimant view is the first surface of the visibility wall: structurally it can
 * carry only claimNumber/status/steps — no internal fields, ever.
 */
class ClaimantClaimViewTest {

    @Test
    void viewStructurallyCarriesOnlyPublicFields() {
        List<String> components = Arrays.stream(ClaimantClaimView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertEquals(List.of("claimNumber", "status", "steps"), components,
                "adding an internal field to the claimant view is a deliberate act");
    }

    @Test
    void fromMapsAnUnassignedClaimToNumberStatusAndIntroductoryStep() {
        Claim claim = new Claim("CLM-000001", 1L, "sub-1", "L1", "UNASSIGNED",
                LocalDate.of(2026, 9, 1), "London", "Kitchen flooded", "Water everywhere");

        ClaimantClaimView view = ClaimantClaimView.from(claim);

        assertEquals("CLM-000001", view.claimNumber());
        assertEquals("UNASSIGNED", view.status());
        assertTrue(view.steps().get(0).startsWith("FNOL received"),
                "unassigned claims tell the claimant what happens next");
    }

    @Test
    void unknownStatusYieldsNoSteps() {
        assertTrue(ClaimantClaimView.stepsFor("MYSTERY").isEmpty());
    }
}
