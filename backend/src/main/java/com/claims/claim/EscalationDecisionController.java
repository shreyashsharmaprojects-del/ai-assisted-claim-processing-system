package com.claims.claim;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.claims.mail.DecisionEmailSender;
import com.claims.outbox.EmailOutboxDispatcher;

/**
 * The supervisor escalation-decision endpoint (slice 5): a claim in
 * {@code ESCALATED_SUPERVISOR} is approved (single payment + close) or denied (close with
 * remarks) by a supervisor, rationale required. The URL-level role rule admits SUPERVISOR
 * only — an adjuster or claimant is a 403 and never learns whether the claim exists; the
 * service restricts eligibility to claims actually escalated to the supervisor (400) and
 * mirrors the slice-4 "already decided" rule.
 *
 * <p>R2: on closure the service writes the decision mail to the outbox in its transaction;
 * this controller keeps the best-effort immediate send (existing Mailpit tests assert on
 * it) and flushes the outbox row through the dispatcher, like the slice-4 endpoint.
 */
@RestController
@RequestMapping("/api/claims")
public class EscalationDecisionController {

    private final EscalationDecisionService escalationDecisionService;
    private final DecisionEmailSender decisionEmailSender;
    private final EmailOutboxDispatcher dispatcher;

    public EscalationDecisionController(EscalationDecisionService escalationDecisionService,
            DecisionEmailSender decisionEmailSender, EmailOutboxDispatcher dispatcher) {
        this.escalationDecisionService = escalationDecisionService;
        this.decisionEmailSender = decisionEmailSender;
        this.dispatcher = dispatcher;
    }

    @PostMapping("/{claimNumber}/escalation-decision")
    public ClaimDecisionView decide(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String claimNumber, @RequestBody ClaimDecisionInput input) {
        ClaimDecisionOutcome outcome = escalationDecisionService.decideEscalation(
                claimNumber, jwt.getSubject(), input);
        // A supervisor decision always closes the claim: tell the claimant, best-effort
        // after commit (the slice-4 machinery).
        decisionEmailSender.sendDecision(outcome.holderEmail(), outcome.holderName(),
                outcome.view());
        dispatcher.dispatch();
        return outcome.view();
    }
}
