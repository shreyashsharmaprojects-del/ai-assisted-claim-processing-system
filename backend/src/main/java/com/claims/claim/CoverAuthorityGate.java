package com.claims.claim;

import java.math.BigDecimal;

/**
 * V2-6: the gate on cover decisions — a pure function over the rung limits so the
 * 3-rung × basis × skip-level matrix is exhaustively unit-testable without a database.
 * Authority is per-claim: the product's basis aggregate (Σ approved default, or Σ net)
 * is compared against the acting rung's limit; pure rejections never gate.
 */
final class CoverAuthorityGate {

    private CoverAuthorityGate() {
    }

    /** Within authority (apply + close), above (save proposals, stay), or ungated deny. */
    enum Verdict {
        APPLY,
        PROPOSE,
        DENY
    }

    static Verdict evaluate(boolean anyApproved, BigDecimal basisTotal, BigDecimal actorLimit) {
        if (!anyApproved) {
            return Verdict.DENY;
        }
        if (actorLimit == null) {
            return Verdict.PROPOSE;
        }
        return basisTotal.compareTo(actorLimit) <= 0 ? Verdict.APPLY : Verdict.PROPOSE;
    }
}
