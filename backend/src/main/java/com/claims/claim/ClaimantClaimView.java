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
 *
 * <p>V2-2 grows the filed covers: {@code covers} (claimant-supplied figures plus the
 * above-limit flag derived from the already-public sub-limit) and the server-computed
 * {@code claimedTotal}. Both are null on pre-V2-2 and legacy no-cover filings, so those
 * responses are byte-identical to before. Assessed/deductible/adjustment figures,
 * verifier identity, proposals and notes never enter this shape.
 *
 * <p>V2-5 grows the closure outcome: per-cover {@code decision} + approved amounts +
 * remarks on {@code covers}, the aggregate {@code decision} (which may now be
 * {@code PARTIALLY_APPROVED}), and {@code netPayableTotal} — the payable figure — on
 * closure. All three are null while the claim is open.
 *
 * <p>V16 grows the NEED_INFO round-trip: {@code needInfoReason} carries the
 * adjuster's requested items while the claim waits on the claimant (null at every
 * other state, still omitted from the wire when null). It is the claimant's own
 * request text — no internal fields travel with it.
 *
 * <p>V3 S3 grows the required-documents tracker: {@code documentsReceived} /
 * {@code documentsTotal} counts plus the per-item labels ({@code requiredDocuments},
 * each {@code {displayName, status)}}. Never internals: decided_by never enters
 * this shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaimantClaimView(String claimNumber, String status, List<String> steps,
        String decision, BigDecimal indemnityAmount, String decisionRemarks,
        List<ClaimantCoverView> covers, BigDecimal claimedTotal, BigDecimal netPayableTotal,
        String needInfoReason, Integer documentsReceived, Integer documentsTotal,
        List<DocItem> requiredDocuments) {

    /** Legacy shape: no filed covers (pre-V2-2 rows and the no-covers filing path). */
    public static ClaimantClaimView from(Claim claim) {
        return from(claim, null, null);
    }

    public static ClaimantClaimView from(Claim claim, List<ClaimantCoverView> covers,
            BigDecimal claimedTotal) {
        return from(claim, covers, claimedTotal, null);
    }

    public static ClaimantClaimView from(Claim claim, List<ClaimantCoverView> covers,
            BigDecimal claimedTotal, BigDecimal netPayableTotal) {
        return from(claim, covers, claimedTotal, netPayableTotal, null, null, null);
    }

    public static ClaimantClaimView from(Claim claim, List<ClaimantCoverView> covers,
            BigDecimal claimedTotal, BigDecimal netPayableTotal,
            Integer documentsReceived, Integer documentsTotal,
            List<DocItem> requiredDocuments) {
        String decision = claim.getDecision();
        boolean approvedLike = "APPROVED".equals(decision)
                || "PARTIALLY_APPROVED".equals(decision);
        // The request text only while the claim waits on the claimant; null (and
        // hence omitted from the wire) at every other state.
        String needInfoReason = "NEED_INFO".equals(claim.getStatus())
                ? claim.getNeedInfoReason() : null;
        return new ClaimantClaimView(claim.getClaimNumber(), claim.getStatus(),
                stepsFor(claim.getStatus()), decision,
                approvedLike ? claim.getIndemnityAmount() : null,
                "DENIED".equals(decision) ? claim.getDecisionRemarks() : null,
                covers, claimedTotal,
                approvedLike ? netPayableTotal : null,
                needInfoReason, documentsReceived, documentsTotal, requiredDocuments);
    }

    /** S3: one claimant-safe checklist item — label + status, never internals. */
    public record DocItem(String displayName, String status) {
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
            case "UNDER_REVIEW", "NEED_INFO" -> List.of(
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
