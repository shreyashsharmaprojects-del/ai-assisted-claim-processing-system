package com.claims.aging;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The aging ladder (Flow 6), kept as a pure function so the unit matrix is exhaustive and
 * needs no database. Given where a claim sits and how old it is (from FNOL —
 * {@code claim.created_at}), it says what the scheduled aging job should do.
 *
 * <p>Semantics (recorded in docs/decisions.md):
 * <ul>
 *   <li>The anchor is claim creation (FNOL), never per-status time.</li>
 *   <li>A claim undecided at 3 days is pushed to L2 — applied to claims still below the L2
 *       tier (an L1 claim, or one that never got an adjuster). A claim already held at L2
 *       is not re-pushed: it already satisfies the rung.</li>
 *   <li>A claim undecided at 5 days is pushed to the supervisor, whatever tier it reached.
 *       The 5-day rung wins over the 3-day rung, so a single run after a clock jump (or a
 *       missed schedule) lands the claim at the top of the ladder — idempotent, never
 *       "L2 first, supervisor later".</li>
 *   <li>{@code CLOSED} and {@code ESCALATED_SUPERVISOR} claims are never re-touched: the
 *       ladder is for undecided claims still in the hands of adjusters.</li>
 * </ul>
 */
public final class AgingPolicy {

    private AgingPolicy() {
    }

    /** What the aging job should do with one claim. */
    public enum AgingStep {
        /** The claim needs no aging action. */
        NONE,
        /** Push the claim to the L2 tier: re-assign to the least-loaded L2 adjuster. */
        REASSIGN_TO_L2,
        /** Move the claim to ESCALATED_SUPERVISOR (no adjuster holds it). */
        ESCALATE_TO_SUPERVISOR
    }

    /**
     * @param status  the claim's current status (CLOSED / ESCALATED_SUPERVISOR are immune)
     * @param level   the claim's current routing level (L1 | L2)
     * @param created the claim's {@code created_at} (FNOL time)
     * @param now     the job's notion of "now" (injectable for deterministic tests)
     */
    public static AgingStep stepFor(String status, String level, Instant created, Instant now) {
        if ("CLOSED".equals(status) || "ESCALATED_SUPERVISOR".equals(status)) {
            return AgingStep.NONE;
        }
        if (atLeast(created, now, 5)) {
            // The 5-day rung wins over the 3-day rung: a claim that is 5+ days old has
            // missed its L2 stop and lands on the supervisor in one idempotent move.
            return AgingStep.ESCALATE_TO_SUPERVISOR;
        }
        if (atLeast(created, now, 3)
                && ("L1".equals(level) || "UNASSIGNED".equals(status))) {
            // Below the L2 tier still: an L1-held claim, or one that never got an adjuster.
            // A claim already held at L2 satisfies the rung and is not re-pushed.
            return AgingStep.REASSIGN_TO_L2;
        }
        return AgingStep.NONE;
    }

    static boolean atLeast(Instant created, Instant now, long days) {
        return !now.isBefore(created.plus(days, ChronoUnit.DAYS));
    }
}
