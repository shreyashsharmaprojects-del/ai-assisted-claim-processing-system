package com.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * V2-4 unit: the cover gate is a pure function over the rung limits — no database.
 * Matrix: 3 rungs × basis (Σ approved vs Σ net) × skip-level (L1→L3 over L2) × the
 * ungated pure rejection.
 */
class CoverAuthorityGateTest {

    private static final BigDecimal L1 = new BigDecimal("100000");
    private static final BigDecimal L2 = new BigDecimal("400000");
    private static final BigDecimal L3 = new BigDecimal("1000000");

    @Test
    void withinAuthorityAppliesAtEveryRung() {
        assertEquals(CoverAuthorityGate.Verdict.APPLY,
                CoverAuthorityGate.evaluate(true, L1, L1));
        assertEquals(CoverAuthorityGate.Verdict.APPLY,
                CoverAuthorityGate.evaluate(true, L2, L2));
        assertEquals(CoverAuthorityGate.Verdict.APPLY,
                CoverAuthorityGate.evaluate(true, L3, L3));
    }

    @Test
    void aboveAuthorityProposesAtEveryRung() {
        assertEquals(CoverAuthorityGate.Verdict.PROPOSE,
                CoverAuthorityGate.evaluate(true, L1.add(BigDecimal.ONE), L1));
        assertEquals(CoverAuthorityGate.Verdict.PROPOSE,
                CoverAuthorityGate.evaluate(true, L2.add(BigDecimal.ONE), L2));
        assertEquals(CoverAuthorityGate.Verdict.PROPOSE,
                CoverAuthorityGate.evaluate(true, L3.add(BigDecimal.ONE), L3));
    }

    @Test
    void exactLimitIsWithinAuthority() {
        assertEquals(CoverAuthorityGate.Verdict.APPLY,
                CoverAuthorityGate.evaluate(true, new BigDecimal("400000"),
                        new BigDecimal("400000")));
    }

    @Test
    void pureRejectionIsNeverGatedEvenAboveEveryLimit() {
        assertEquals(CoverAuthorityGate.Verdict.DENY,
                CoverAuthorityGate.evaluate(false, L3.multiply(new BigDecimal("10")),
                        L1));
    }

    @Test
    void unconfiguredLimitProposes() {
        assertEquals(CoverAuthorityGate.Verdict.PROPOSE,
                CoverAuthorityGate.evaluate(true, BigDecimal.ONE, null));
    }
}

