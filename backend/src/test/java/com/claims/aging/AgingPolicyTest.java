package com.claims.aging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import com.claims.aging.AgingPolicy.AgingStep;

/**
 * The aging ladder (Flow 6) as a pure function over (status, level, created, now) — no
 * database, no network. The matrix pins the 3-day and 5-day thresholds including their
 * exact boundaries, that the 5-day rung wins over the 3-day rung under a clock jump (a
 * missed/caught-up schedule must land the claim at the top, idempotently), that an L2
 * claim already satisfies the 3-day rung, and that CLOSED / ESCALATED_SUPERVISOR claims
 * are never re-touched.
 */
class AgingPolicyTest {

    private static final Instant CREATED = Instant.parse("2026-09-01T00:00:00Z");

    private static AgingStep stepAt(long days) {
        return AgingPolicy.stepFor("UNDER_REVIEW", "L1", CREATED,
                CREATED.plus(days, ChronoUnit.DAYS));
    }

    // --- under the thresholds ---------------------------------------------------

    @Test
    void aFreshClaimUnderThreeDaysIsNotAged() {
        assertEquals(AgingStep.NONE, stepAt(2));
    }

    @Test
    void aClaimJustUnderThreeDaysIsNotAged() {
        Instant justUnder = CREATED.plus(3, ChronoUnit.DAYS).minusMillis(1);
        assertEquals(AgingStep.NONE,
                AgingPolicy.stepFor("UNDER_REVIEW", "L1", CREATED, justUnder));
    }

    // --- the 3-day rung ---------------------------------------------------------

    @Test
    void anL1ClaimExactlyAtThreeDaysIsPushedToL2() {
        assertEquals(AgingStep.REASSIGN_TO_L2, stepAt(3));
    }

    @Test
    void anL1ClaimAtFourDaysIsPushedToL2() {
        assertEquals(AgingStep.REASSIGN_TO_L2, stepAt(4));
    }

    @Test
    void anL2ClaimAtThreeOrFourDaysAlreadySatisfiesTheRung() {
        Instant day3 = CREATED.plus(3, ChronoUnit.DAYS);
        Instant day4 = CREATED.plus(4, ChronoUnit.DAYS);
        assertEquals(AgingStep.NONE,
                AgingPolicy.stepFor("UNDER_REVIEW", "L2", CREATED, day3),
                "a claim already held at L2 is not re-pushed at 3 days");
        assertEquals(AgingStep.NONE,
                AgingPolicy.stepFor("UNDER_REVIEW", "L2", CREATED, day4));
    }

    @Test
    void anUnassignedL1ClaimAtThreeDaysIsPushedToL2() {
        Instant day3 = CREATED.plus(3, ChronoUnit.DAYS);
        assertEquals(AgingStep.REASSIGN_TO_L2,
                AgingPolicy.stepFor("UNASSIGNED", "L1", CREATED, day3));
    }

    @Test
    void anUnassignedL2ClaimAtThreeDaysIsAssignedToL2() {
        // A level-L2 claim that never got an adjuster (provisioning gap at FNOL) is still
        // below a working L2 holder: the 3-day rung assigns it.
        Instant day3 = CREATED.plus(3, ChronoUnit.DAYS);
        assertEquals(AgingStep.REASSIGN_TO_L2,
                AgingPolicy.stepFor("UNASSIGNED", "L2", CREATED, day3));
    }

    // --- the 5-day rung ---------------------------------------------------------

    @Test
    void anL1ClaimExactlyAtFiveDaysGoesStraightToTheSupervisor() {
        // The 5-day rung wins over the 3-day rung: an L1 claim never visits L2 first.
        assertEquals(AgingStep.ESCALATE_TO_SUPERVISOR, stepAt(5));
    }

    @Test
    void anL1ClaimJustUnderFiveDaysIsStillPushedToL2() {
        Instant justUnder = CREATED.plus(5, ChronoUnit.DAYS).minusMillis(1);
        assertEquals(AgingStep.REASSIGN_TO_L2,
                AgingPolicy.stepFor("UNDER_REVIEW", "L1", CREATED, justUnder));
    }

    @Test
    void anL2ClaimAtFiveDaysIsEscalatedToTheSupervisor() {
        Instant day5 = CREATED.plus(5, ChronoUnit.DAYS);
        assertEquals(AgingStep.ESCALATE_TO_SUPERVISOR,
                AgingPolicy.stepFor("UNDER_REVIEW", "L2", CREATED, day5));
    }

    @Test
    void anUnassignedClaimAtFiveDaysIsEscalatedToTheSupervisor() {
        Instant day5 = CREATED.plus(5, ChronoUnit.DAYS);
        assertEquals(AgingStep.ESCALATE_TO_SUPERVISOR,
                AgingPolicy.stepFor("UNASSIGNED", "L1", CREATED, day5));
    }

    @Test
    void aClockJumpFromDayTwoToDaySixLandsTheClaimAtTheTop() {
        // A single run after the job was off (or a test clock jump) must be idempotent:
        // the claim ends at the supervisor, not stuck on a half-climbed ladder.
        assertEquals(AgingStep.ESCALATE_TO_SUPERVISOR, stepAt(6));
    }

    // --- immunity ---------------------------------------------------------------

    @Test
    void supervisorEscalatedClaimsAreNeverRetouched() {
        for (long days : new long[] {1, 3, 5, 10}) {
            Instant now = CREATED.plus(days, ChronoUnit.DAYS);
            assertEquals(AgingStep.NONE,
                    AgingPolicy.stepFor("ESCALATED_SUPERVISOR", "L1", CREATED, now),
                    "an ESCALATED_SUPERVISOR claim already waits on the supervisor at day " + days);
            assertEquals(AgingStep.NONE,
                    AgingPolicy.stepFor("ESCALATED_SUPERVISOR", "L2", CREATED, now));
        }
    }

    @Test
    void closedClaimsAreNeverRetouched() {
        for (long days : new long[] {3, 5, 10}) {
            Instant now = CREATED.plus(days, ChronoUnit.DAYS);
            assertEquals(AgingStep.NONE,
                    AgingPolicy.stepFor("CLOSED", "L1", CREATED, now),
                    "a CLOSED claim is terminal and never aged at day " + days);
        }
    }

    @Test
    void aClaimCreatedInTheFutureIsNotAged() {
        assertEquals(AgingStep.NONE,
                AgingPolicy.stepFor("UNDER_REVIEW", "L1", CREATED, CREATED.minusSeconds(1)));
    }
}
