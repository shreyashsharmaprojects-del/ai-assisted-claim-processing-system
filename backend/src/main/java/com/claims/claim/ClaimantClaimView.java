package com.claims.claim;

import java.util.List;

/**
 * The public shape of a claim on claimant-facing surfaces. Structurally omits every
 * internal field (policy id, claimant subject, level internals, description, attachments,
 * remarks): this is where the visibility wall begins.
 */
public record ClaimantClaimView(String claimNumber, String status, List<String> steps) {

    public static ClaimantClaimView from(Claim claim) {
        return new ClaimantClaimView(claim.getClaimNumber(), claim.getStatus(), stepsFor(claim.getStatus()));
    }

    /**
     * The claimant-visible process steps derived from status. The list grows as the state
     * machine does; it never exposes the reserve, internal notes, or the internal assignee.
     */
    public static List<String> stepsFor(String status) {
        return switch (status) {
            case "UNASSIGNED" -> List.of("FNOL received — your claim is being routed to an adjuster");
            case "UNDER_REVIEW" -> List.of(
                    "FNOL received — your claim is being routed to an adjuster",
                    "Under review — an adjuster has been assigned to your claim");
            default -> List.of();
        };
    }
}
