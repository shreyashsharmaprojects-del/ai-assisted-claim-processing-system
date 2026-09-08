package com.claims.claim;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What a decision produced, for the deciding adjuster's screen. Every field is consumed by
 * the claim-detail page: the outcome banner is composed from {@code decision}/
 * {@code escalatedTo} ({@code indemnityAmount} on approval, {@code decisionRemarks} on
 * denial), and the page no longer offers the decision form once the claim is closed or has
 * left the actor's hands. {@code claimNumber} feeds the decision email.
 *
 * <ul>
 *   <li>{@code decision}: APPROVED or DENIED when the claim closed; null when it escalated.</li>
 *   <li>{@code escalatedTo}: L2 or SUPERVISOR when an approval was above the actor's
 *       authority (never both with a non-null {@code decision}).</li>
 * </ul>
 *
 * <p>V2-6 grows the cover-decision shape: {@code proposedTotal} (the gated basis
 * aggregate), {@code authorityLimit} (the actor's rung limit) and {@code proposalsSaved}
 * ride the view when an above-authority cover decision saves proposals instead of
 * closing. {@code @JsonInclude(NON_NULL)} omits them on the legacy path, so legacy
 * responses are byte-identical to before.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaimDecisionView(String claimNumber, String decision,
        BigDecimal indemnityAmount, String decisionRemarks, String escalatedTo,
        BigDecimal proposedTotal, BigDecimal authorityLimit, Boolean proposalsSaved) {

    /** Legacy shape: single-figure decision or escalation (pre-V2-6 paths). */
    public ClaimDecisionView(String claimNumber, String decision, BigDecimal indemnityAmount,
            String decisionRemarks, String escalatedTo) {
        this(claimNumber, decision, indemnityAmount, decisionRemarks, escalatedTo, null,
                null, null);
    }
}
