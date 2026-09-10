package com.claims.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.claims.routing.AuthorityGate.Outcome;

/**
 * The exhaustive unit matrix for the authority gate (slice 4) — the product's core rule,
 * pinned with no database. Covers: within-level approval (and the boundary equality),
 * above-level escalation routing to L2 vs straight to the supervisor (skip-levels), the
 * L2 adjuster's own ceiling, and the denial path.
 */
class AuthorityGateTest {

    private static final BigDecimal L1_LIMIT = new BigDecimal("2500.00");
    private static final BigDecimal L2_LIMIT = new BigDecimal("10000.00");

    // --- L1 actor: what may be approved within L1 authority ------------------

    @Test
    void l1ActorApprovesAnAmountWithinTheL1Limit() {
        assertEquals(Outcome.APPROVE, AuthorityGate.evaluate("APPROVED", "L1",
                new BigDecimal("1500.00"), L1_LIMIT, L2_LIMIT));
    }

    @Test
    void l1ActorApprovesAnAmountExactlyAtTheL1Limit() {
        assertEquals(Outcome.APPROVE, AuthorityGate.evaluate("APPROVED", "L1",
                L1_LIMIT, L1_LIMIT, L2_LIMIT));
    }

    @Test
    void l1ActorEscalatesAnAmountJustAboveTheL1LimitToL2() {
        assertEquals(Outcome.ESCALATE_TO_L2, AuthorityGate.evaluate("APPROVED", "L1",
                new BigDecimal("2500.01"), L1_LIMIT, L2_LIMIT));
    }

    @Test
    void l1ActorEscalatesAnAmountExactlyAtTheL2LimitToL2() {
        // An L2 adjuster may approve up to the L2 limit, so an L1-sized decision at the L2
        // ceiling belongs to L2 — never approved by the L1 actor.
        assertEquals(Outcome.ESCALATE_TO_L2, AuthorityGate.evaluate("APPROVED", "L1",
                L2_LIMIT, L1_LIMIT, L2_LIMIT));
    }

    @Test
    void l1ActorAboveTheL2LimitSkipsStraightToTheSupervisor() {
        assertEquals(Outcome.ESCALATE_TO_SUPERVISOR, AuthorityGate.evaluate("APPROVED", "L1",
                new BigDecimal("10000.01"), L1_LIMIT, L2_LIMIT));
    }

    // --- L2 actor: the top adjuster ceiling ----------------------------------

    @Test
    void l2ActorApprovesAnAmountWithinTheL2Limit() {
        assertEquals(Outcome.APPROVE, AuthorityGate.evaluate("APPROVED", "L2",
                new BigDecimal("9000.00"), L1_LIMIT, L2_LIMIT));
    }

    @Test
    void l2ActorApprovesAnL2LevelClaimWithinTheL2Limit() {
        // The no-self-approval matrix case: an L2 adjuster approving an L2-level claim
        // within the L2 limit is a plain within-authority approval (the escalation that
        // must not be self-approved is the supervisor's, slice 5) — never blocked here.
        assertEquals(Outcome.APPROVE, AuthorityGate.evaluate("APPROVED", "L2",
                new BigDecimal("6000.00"), L1_LIMIT, L2_LIMIT));
    }

    @Test
    void l2ActorApprovesExactlyAtTheL2Limit() {
        assertEquals(Outcome.APPROVE, AuthorityGate.evaluate("APPROVED", "L2",
                L2_LIMIT, L1_LIMIT, L2_LIMIT));
    }

    @Test
    void l2ActorAboveTheL2LimitEscalatesToTheSupervisor() {
        assertEquals(Outcome.ESCALATE_TO_SUPERVISOR, AuthorityGate.evaluate("APPROVED", "L2",
                new BigDecimal("12000.00"), L1_LIMIT, L2_LIMIT));
    }

    // --- denial is never authority-gated --------------------------------------

    @Test
    void denyAlwaysClosesWhateverTheAmount() {
        assertEquals(Outcome.DENY, AuthorityGate.evaluate("DENIED", "L1",
                new BigDecimal("1.00"), L1_LIMIT, L2_LIMIT));
        assertEquals(Outcome.DENY, AuthorityGate.evaluate("DENIED", "L1",
                L2_LIMIT, L1_LIMIT, L2_LIMIT));
        assertEquals(Outcome.DENY, AuthorityGate.evaluate("DENIED", "L1",
                new BigDecimal("99999999.00"), L1_LIMIT, L2_LIMIT));
        assertEquals(Outcome.DENY, AuthorityGate.evaluate("DENIED", "L2",
                new BigDecimal("99999999.00"), L1_LIMIT, L2_LIMIT));
    }

    // --- request validation ---------------------------------------------------

    @Test
    void rationaleIsRequiredForAnApproval() {
        assertEquals("A rationale is required.",
                AuthorityGate.validate("APPROVED", new BigDecimal("100.00"), "  "));
        // V23 (V3 S8): closures need a rationale of at least 20 characters.
        assertEquals("Rationale must be at least 20 characters.",
                AuthorityGate.validate("APPROVED", new BigDecimal("100.00"), "ok"));
        assertNull(AuthorityGate.validate("APPROVED", new BigDecimal("100.00"),
                "A well-documented reason here."));
    }

    @Test
    void rationaleIsRequiredForADenial() {
        assertEquals("A rationale is required.",
                AuthorityGate.validate("DENIED", null, null));
        assertEquals("Rationale must be at least 20 characters.",
                AuthorityGate.validate("DENIED", null, "ok"));
        assertNull(AuthorityGate.validate("DENIED", null,
                "A well-documented reason here."));
    }

    @Test
    void approvalRequiresAPositiveIndemnityAmount() {
        assertEquals("An indemnity amount is required for an approval.",
                AuthorityGate.validate("APPROVED", null,
                        "A well-documented reason here."));
        assertEquals("Indemnity amount must be greater than zero.",
                AuthorityGate.validate("APPROVED", BigDecimal.ZERO,
                        "A well-documented reason here."));
        assertEquals("Indemnity amount must be greater than zero.",
                AuthorityGate.validate("APPROVED", new BigDecimal("-5"),
                        "A well-documented reason here."));
    }

    @Test
    void approvalAmountMustFitTheMoneyColumn() {
        assertEquals("Indemnity amount may have at most 2 decimal places.",
                AuthorityGate.validate("APPROVED", new BigDecimal("1.234"),
                        "A well-documented reason here."));
        assertEquals("Indemnity amount is too large (maximum 999999999999.99).",
                AuthorityGate.validate("APPROVED", new BigDecimal("1000000000000"),
                        "A well-documented reason here."));
        assertNull(AuthorityGate.validate("APPROVED",
                new BigDecimal("999999999999.99"),
                "A well-documented reason here."));
    }

    @Test
    void denialCannotCarryAnIndemnityAmount() {
        assertEquals("An indemnity amount only applies to an approval.",
                AuthorityGate.validate("DENIED", new BigDecimal("500.00"),
                        "A well-documented reason here."));
    }

    @Test
    void decisionMustBeApprovedOrDenied() {
        assertEquals("Decision must be APPROVED or DENIED.",
                AuthorityGate.validate("MAYBE", new BigDecimal("500.00"),
                        "A well-documented reason here."));
        assertEquals("Decision must be APPROVED or DENIED.",
                AuthorityGate.validate(null, null, null));
    }
}
