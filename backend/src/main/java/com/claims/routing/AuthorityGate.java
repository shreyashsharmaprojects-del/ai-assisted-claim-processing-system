package com.claims.routing;

import java.math.BigDecimal;

/**
 * The authority gate (slice 4) — the product's core rule, kept as a pure function so the
 * unit matrix is exhaustive and needs no database.
 *
 * <p>Semantics (from docs/plan.md, stated unambiguously there):
 * <ul>
 *   <li>Authority is per-claim: the acting adjuster's level limit is compared against the
 *       proposed {@code indemnity_amount}. L1 adjusters may approve up to
 *       {@code l1_limit_amount}, L2 adjusters up to {@code l2_limit_amount}.</li>
 *   <li>Within the actor's level limit an approval is legal and closes the claim.</li>
 *   <li>Above the actor's level an approval is never granted: if the amount is within the
 *       L2 limit the claim is escalated to L2 (a system re-assignment); above the L2 limit
 *       it goes to {@code ESCALATED_SUPERVISOR}. Skip-levels routing: an L1 actor whose
 *       amount exceeds the L2 limit goes straight to the supervisor, never via L2.</li>
 *   <li>DENIED is not authority-gated — any deciding adjuster may deny and close.</li>
 *   <li>No-self-approval is structural, not an extra rule: an escalation always leaves the
 *       actor's hands (re-assigned to another level's adjuster, or supervisor with no
 *       adjuster), so nobody can approve their own escalation. In particular an L2
 *       adjuster approving an L2-level claim within the L2 limit is a plain approval and
 *       must never be mistaken for one.</li>
 * </ul>
 */
public final class AuthorityGate {

    /** The claim.indemnity_amount / payment.amount column is NUMERIC(14,2). */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

    private AuthorityGate() {
    }

    /** The decision's outcome, from the acting level's perspective. */
    public enum Outcome {
        /** Approve within the actor's level limit: record payment, close the claim. */
        APPROVE,
        /** The actor's own claim may never hold an escalation — not produced for DENIED. */
        DENY,
        /** Above the actor's level but within the L2 limit: re-assign to the least-loaded L2. */
        ESCALATE_TO_L2,
        /** Above the L2 limit: move to ESCALATED_SUPERVISOR (no adjuster). */
        ESCALATE_TO_SUPERVISOR
    }

    /**
     * @param decision   the requested decision ({@code APPROVED} or {@code DENIED})
     * @param actorLevel the deciding adjuster's level ({@code L1} or {@code L2})
     * @param amount     the proposed indemnity amount (ignored for {@code DENIED})
     * @param l1Limit    the product's L1 authority threshold
     * @param l2Limit    the product's L2 authority threshold
     */
    public static Outcome evaluate(String decision, String actorLevel, BigDecimal amount,
            BigDecimal l1Limit, BigDecimal l2Limit) {
        if ("DENIED".equals(decision)) {
            return Outcome.DENY;
        }
        BigDecimal actorLimit = "L2".equals(actorLevel) ? l2Limit : l1Limit;
        if (amount.compareTo(actorLimit) <= 0) {
            return Outcome.APPROVE;
        }
        if (amount.compareTo(l2Limit) <= 0) {
            return Outcome.ESCALATE_TO_L2;
        }
        return Outcome.ESCALATE_TO_SUPERVISOR;
    }

    /**
     * Validates a decision request body, returning a user-actionable message or {@code null}
     * when valid. Rationale is required for both approve and deny; an approval needs a
     * positive indemnity amount within the {@code NUMERIC(14,2)} column bounds.
     * V23 (V3 S8): the claim-level rationale on every closure is at least 20 characters.
     */
    public static String validate(String decision, BigDecimal amount, String rationale) {
        if (decision == null || (!"APPROVED".equals(decision) && !"DENIED".equals(decision))) {
            return "Decision must be APPROVED or DENIED.";
        }
        if (rationale == null || rationale.isBlank()) {
            return "A rationale is required.";
        }
        if (rationale.trim().length() < 20) {
            return "Rationale must be at least 20 characters.";
        }
        if ("DENIED".equals(decision)) {
            if (amount != null) {
                return "An indemnity amount only applies to an approval.";
            }
            return null;
        }
        if (amount == null) {
            return "An indemnity amount is required for an approval.";
        }
        if (amount.signum() <= 0) {
            return "Indemnity amount must be greater than zero.";
        }
        if (amount.scale() > 2) {
            return "Indemnity amount may have at most 2 decimal places.";
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            return "Indemnity amount is too large (maximum 999999999999.99).";
        }
        return null;
    }
}
