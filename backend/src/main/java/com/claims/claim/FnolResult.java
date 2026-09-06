package com.claims.claim;

/**
 * What filing an FNOL produced. The claimant view is what gets serialized; the assignee
 * identity rides along to the controller only, so the after-commit assignment email can be
 * sent — internal identity never reaches a claimant-facing response (visibility wall).
 */
public record FnolResult(ClaimantClaimView view, String adjusterName, String adjusterEmail) {
}
