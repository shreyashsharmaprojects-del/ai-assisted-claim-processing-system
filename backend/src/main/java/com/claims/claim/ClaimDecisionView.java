package com.claims.claim;

import java.math.BigDecimal;

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
 */
public record ClaimDecisionView(String claimNumber, String decision,
        BigDecimal indemnityAmount, String decisionRemarks, String escalatedTo) {
}
