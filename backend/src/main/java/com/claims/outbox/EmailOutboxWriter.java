package com.claims.outbox;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.claims.claim.ClaimDecisionView;

/**
 * R2 outbox writer: composes the same subjects/bodies the best-effort senders use today and
 * enqueues them in the caller's transaction. Keeps the exact claimant-visible wording —
 * the dispatcher sends the stored subject/body verbatim — so Mailpit assertions on content
 * keep passing unchanged.
 */
@Component
public class EmailOutboxWriter {

    private final EmailOutboxRepository outbox;

    public EmailOutboxWriter(EmailOutboxRepository outbox) {
        this.outbox = outbox;
    }

    public void enqueueFnol(long claimId, String to, String claimNumber, String holderName) {
        outbox.enqueue(claimId, "FNOL", to,
                "Claim " + claimNumber + " received",
                """
                Dear %s,

                We have received your claim %s.

                What happens next: your claim is being routed to an adjuster, who will
                review the details and get in touch. You can track progress on your
                claim status screen.

                Yours,
                Claims Processing
                """.formatted(holderName, claimNumber));
    }

    public void enqueueAssignment(long claimId, String to, String claimNumber, String holderName,
            String adjusterName, String adjusterEmail) {
        outbox.enqueue(claimId, "ASSIGNMENT", to,
                "Claim " + claimNumber + " is now with an adjuster",
                """
                Dear %s,

                Your claim %s has been assigned to an adjuster:

                  %s (%s)

                They have your details and photos and will review the claim and be in
                touch with you. You can track progress on your claim status screen.

                Yours,
                Claims Processing
                """.formatted(holderName, claimNumber, adjusterName, adjusterEmail));
    }

    public void enqueueDecision(long claimId, String to, String holderName,
            ClaimDecisionView decision) {
        outbox.enqueue(claimId, "DECISION", to,
                "Decision on claim " + decision.claimNumber(),
                decisionBody(holderName, decision));
    }

    /**
     * V22 (V3 S6): the reopen notice — same tone as the decision mails, queued in
     * the reopen transaction (commits or rolls back with it). Kind stays DECISION:
     * the V10 kind CHECK is immutable this slice and S10 assumes the three kinds.
     * The subject carries the reopen fact; the dispatcher sends it verbatim.
     *
     * <p>V25 (V3 S10): superseded by {@link #enqueueReopenClaimant} (kind REOPEN).
     * Kept for the S6 test contract (subject LIKE '%reopened%' still holds).
     */
    public void enqueueReopen(long claimId, String to, String holderName,
            String claimNumber, String rationale) {
        enqueueReopenClaimant(claimId, to, holderName, claimNumber, rationale);
    }

    /**
     * V25 (V3 S10): NEED_INFO claimant mail — the "what we need from you" notice,
     * queued in the review NEED_INFO transaction. Kind NEED_INFO (the V25
     * migration extends the V10 kind CHECK in a new file; V10 itself untouched).
     */
    public void enqueueNeedInfo(long claimId, String to, String holderName,
            String claimNumber, String requestedItems) {
        outbox.enqueue(claimId, "NEED_INFO", to,
                "Claim " + claimNumber + " needs information from you",
                """
                Dear %s,

                Your claim %s needs more information before we can continue.

                %s

                Reply on your claim status screen with the items above and we
                will pick the claim back up.

                Yours,
                Claims Processing
                """.formatted(holderName, claimNumber,
                        requestedItems == null ? "" : requestedItems));
    }

    /**
     * V25 (V3 S10): referral notice — the claim moved to a senior adjuster,
     * queued in the refer transaction. Kind REFERRAL.
     */
    public void enqueueReferral(long claimId, String to, String holderName,
            String claimNumber, String escalatedTo, String reason) {
        outbox.enqueue(claimId, "REFERRAL", to,
                "Claim " + claimNumber + " has been referred for senior review",
                """
                Dear %s,

                Your claim %s has been referred to %s for further review.

                %s

                You can track progress on your claim status screen.

                Yours,
                Claims Processing
                """.formatted(holderName, claimNumber,
                        escalatedTo == null ? "a senior adjuster" : escalatedTo,
                        reason == null ? "" : reason));
    }

    /**
     * V25 (V3 S10): the claimant reopen notice under its own kind (REOPEN) —
     * same tone and subject as the S6 mail, so content assertions stay stable.
     */
    public void enqueueReopenClaimant(long claimId, String to, String holderName,
            String claimNumber, String rationale) {
        outbox.enqueue(claimId, "REOPEN", to,
                "Claim " + claimNumber + " has been reopened",
                """
                Dear %s,

                Your claim %s has been reopened for further review.

                %s

                What happens next: an adjuster will review the claim again and be
                in touch with you. You can track progress on your claim status
                screen.

                Yours,
                Claims Processing
                """.formatted(holderName, claimNumber,
                        rationale == null ? "" : rationale));
    }

    private static String decisionBody(String holderName, ClaimDecisionView decision) {
        if ("APPROVED".equals(decision.decision())) {
            return """
                    Dear %s,

                    Your claim %s has been approved. We will pay %s.

                    Yours,
                    Claims Processing
                    """.formatted(holderName, decision.claimNumber(),
                    pounds(decision.indemnityAmount()));
        }
        return """
                Dear %s,

                Your claim %s has not been approved.

                %s

                Yours,
                Claims Processing
                """.formatted(holderName, decision.claimNumber(),
                decision.decisionRemarks() == null ? "" : decision.decisionRemarks());
    }

    private static String pounds(BigDecimal amount) {
        return amount == null ? "" : "£" + amount.toPlainString();
    }
}
