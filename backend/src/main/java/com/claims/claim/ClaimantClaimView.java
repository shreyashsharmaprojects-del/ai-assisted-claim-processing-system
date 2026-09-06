package com.claims.claim;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The public shape of a claim on claimant-facing surfaces. Structurally omits every
 * internal field (policy id, claimant subject, level internals, description, attachments,
 * claimant remarks): this is where the visibility wall begins.
 *
 * <p>Slice 6 grows the decision fields: {@code decision}, {@code indemnityAmount} (an
 * approval figure) and {@code decisionRemarks} (a denial's rationale, verbatim). They are
 * null on the record when not applicable, and {@code @JsonInclude(NON_NULL)} omits them
 * from the wire — so an undecided claim's response is byte-identical to slice 5, an
 * APPROVED closure adds decision + amount, and a DENIED closure adds decision + remarks.
 * The mapper guards each field to its decision so the "approved amount only when APPROVED /
 * remarks only when DENIED" rule is structural at the view boundary, not a serialization
 * accident.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaimantClaimView(String claimNumber, String status, List<String> steps,
        String decision, BigDecimal indemnityAmount, String decisionRemarks) {

    public static ClaimantClaimView from(Claim claim) {
        String decision = claim.getDecision();
        return new ClaimantClaimView(claim.getClaimNumber(), claim.getStatus(),
                stepsFor(claim.getStatus()), decision,
                "APPROVED".equals(decision) ? claim.getIndemnityAmount() : null,
                "DENIED".equals(decision) ? claim.getDecisionRemarks() : null);
    }

    /**
     * The claimant-visible process steps derived from status. The list grows as the state
     * machine does; it never exposes the reserve, internal notes, or the internal assignee.
     *
     * <p>For CLOSED claims the steps stop at the two stages every closure truthfully
     * shared — FNOL received, under review. CLOSED cannot recall whether the journey passed
     * through {@code ESCALATED_SUPERVISOR} (the decision/closure columns do not record it),
     * so no "Escalated" step is fabricated; the terminal step is the decision itself, which
     * the screen renders from the decision fields (slice-6 decision, see docs/decisions.md).
     */
    public static List<String> stepsFor(String status) {
        return switch (status) {
            case "UNASSIGNED" -> List.of("FNOL received — your claim is being routed to an adjuster");
            case "UNDER_REVIEW" -> List.of(
                    "FNOL received — your claim is being routed to an adjuster",
                    "Under review — an adjuster has been assigned to your claim");
            // Flow 6 (slice 5): while a claim waits on the supervisor the claimant sees the
            // escalation — the aging timeline is claimant-visible.
            case "ESCALATED_SUPERVISOR" -> List.of(
                    "FNOL received — your claim is being routed to an adjuster",
                    "Under review — an adjuster has been assigned to your claim",
                    "Escalated — your claim is now being reviewed by a supervisor");
            case "CLOSED" -> List.of(
                    "FNOL received — your claim is being routed to an adjuster",
                    "Under review — an adjuster has been assigned to your claim");
            default -> List.of();
        };
    }
}
