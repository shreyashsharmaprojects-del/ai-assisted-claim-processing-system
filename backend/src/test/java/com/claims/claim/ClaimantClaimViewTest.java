package com.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The claimant view is the first surface of the visibility wall: structurally it can
 * carry only the public fields — claimNumber/status/steps, plus the slice-6 decision
 * fields (decision, indemnityAmount on approval, decisionRemarks on denial) — no
 * internal fields, ever. Slice 4 deferred the decision fields here deliberately; this
 * test pins the exact shape so growing it is a conscious act.
 */
class ClaimantClaimViewTest {

    @Test
    void viewStructurallyCarriesOnlyPublicFields() {
        List<String> components = Arrays.stream(ClaimantClaimView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertEquals(List.of("claimNumber", "status", "steps", "decision", "indemnityAmount",
                "decisionRemarks"), components,
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
    void underReviewStatusMapsToReceivedAndUnderReviewSteps() {
        List<String> steps = ClaimantClaimView.stepsFor("UNDER_REVIEW");
        assertEquals(2, steps.size());
        assertTrue(steps.get(0).startsWith("FNOL received"),
                "the received step stays visible once an adjuster is assigned");
        assertTrue(steps.get(1).startsWith("Under review"),
                "an assigned claim tells the claimant it is now under review");
    }

    @Test
    void escalatedStatusShowsTheEscalatedProcessStep() {
        // Flow 6: while a claim waits on the supervisor, the claimant sees that it was
        // escalated — FNOL received -> under review -> escalated (the decision display
        // itself lands in slice 6).
        List<String> steps = ClaimantClaimView.stepsFor("ESCALATED_SUPERVISOR");
        assertEquals(3, steps.size());
        assertTrue(steps.get(0).startsWith("FNOL received"));
        assertTrue(steps.get(1).startsWith("Under review"));
        assertTrue(steps.get(2).startsWith("Escalated"),
                "an escalated claim tells the claimant it is with the supervisor");
    }

    @Test
    void closedStatusShowsTheSharedTimelineAndNeverAHistorianEscalationStep() {
        // Slice-6 decision: CLOSED cannot recall whether the journey passed through
        // ESCALATED_SUPERVISOR (the decision/closure columns do not record it), so the
        // closed view shows only the two steps every closure truthfully shared — never a
        // guessed "Escalated" step. The decision itself renders as the terminal block on
        // the screen, not as a status-derived list line.
        List<String> steps = ClaimantClaimView.stepsFor("CLOSED");
        assertEquals(2, steps.size());
        assertTrue(steps.get(0).startsWith("FNOL received"));
        assertTrue(steps.get(1).startsWith("Under review"));
        for (String step : steps) {
            assertFalse(step.contains("Escalated"),
                    "a closed claim's steps must not fabricate an escalation that cannot "
                            + "be recalled from status alone: " + steps);
        }
    }

    @Test
    void fromMapsAnApprovedClosedClaimToTheApprovedAmountAndNoRemarks() {
        Claim claim = claim();
        claim.approve(new BigDecimal("1500.00"), Instant.parse("2026-09-06T12:00:00Z"));

        ClaimantClaimView view = ClaimantClaimView.from(claim);

        assertEquals("CLOSED", view.status());
        assertEquals("APPROVED", view.decision());
        assertEquals(0, new BigDecimal("1500.00").compareTo(view.indemnityAmount()),
                "an approved claim carries the indemnity amount on the claimant view");
        assertNull(view.decisionRemarks(),
                "approval has no remarks; the field must not ride the view");
        assertEquals(ClaimantClaimView.stepsFor("CLOSED"), view.steps());
    }

    @Test
    void fromMapsADeniedClosedClaimToTheRemarksAndNoAmount() {
        Claim claim = claim();
        claim.deny("Coverage excludes the reported damage.",
                Instant.parse("2026-09-06T12:00:00Z"));

        ClaimantClaimView view = ClaimantClaimView.from(claim);

        assertEquals("CLOSED", view.status());
        assertEquals("DENIED", view.decision());
        assertEquals("Coverage excludes the reported damage.", view.decisionRemarks(),
                "the denial rationale is the claimant-visible remarks (slice-4 rule)");
        assertNull(view.indemnityAmount(),
                "a denied claim never carries an indemnity amount on the claimant view");
    }

    @Test
    void fromMapsAnUndecidedOpenClaimToNoDecisionContent() {
        ClaimantClaimView view = ClaimantClaimView.from(claim());

        assertNull(view.decision(), "an undecided claim has no decision");
        assertNull(view.indemnityAmount());
        assertNull(view.decisionRemarks());
        assertEquals(ClaimantClaimView.stepsFor("UNDER_REVIEW"), view.steps(),
                "the open claim's process steps are unchanged by the decision fields");
    }

    @Test
    void unknownStatusYieldsNoSteps() {
        assertTrue(ClaimantClaimView.stepsFor("MYSTERY").isEmpty());
    }

    private static Claim claim() {
        return new Claim("CLM-000001", 1L, "sub-1", "L1", "UNDER_REVIEW",
                LocalDate.of(2026, 9, 1), "London", "Kitchen flooded", "Water everywhere");
    }
}
