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
