package com.claims.claim;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.claims.mail.DecisionEmailSender;

/**
 * The supervisor escalation-decision endpoint (slice 5): a claim in
 * {@code ESCALATED_SUPERVISOR} is approved (single payment + close) or denied (close with
 * remarks) by a supervisor, rationale required. The URL-level role rule admits SUPERVISOR
 * only — an adjuster or claimant is a 403 and never learns whether the claim exists; the
 * service restricts eligibility to claims actually escalated to the supervisor (400) and
 * mirrors the slice-4 "already decided" rule. On closure the decision email is sent
 * best-effort after commit, like every other decision email.
 */
@RestController
@RequestMapping("/api/claims")
public class EscalationDecisionController {

    private final EscalationDecisionService escalationDecisionService;
    private final DecisionEmailSender decisionEmailSender;

    public EscalationDecisionController(EscalationDecisionService escalationDecisionService,
            DecisionEmailSender decisionEmailSender) {
        this.escalationDecisionService = escalationDecisionService;
        this.decisionEmailSender = decisionEmailSender;
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
        return outcome.view();
    }
}
