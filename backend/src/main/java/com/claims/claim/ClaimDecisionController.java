package com.claims.claim;

import java.math.BigDecimal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.claims.mail.DecisionEmailSender;

/**
 * The decision endpoint (slice 4): an assigned adjuster approves or denies a claim they
 * hold. The URL-level role rule admits only ADJUSTER_L1/L2 (never SUPERVISOR — the
 * supervisor half of Flow 4 is slice 5), and the service enforces that the caller is the
 * claim's assigned adjuster (404 otherwise). On closure the decision email is sent
 * best-effort after commit, like the FNOL and assignment emails.
 */
@RestController
@RequestMapping("/api/claims")
public class ClaimDecisionController {

    private final ClaimDecisionService decisionService;
    private final DecisionEmailSender decisionEmailSender;

    public ClaimDecisionController(ClaimDecisionService decisionService,
            DecisionEmailSender decisionEmailSender) {
        this.decisionService = decisionService;
        this.decisionEmailSender = decisionEmailSender;
    }

    @PostMapping("/{claimNumber}/decision")
    public ClaimDecisionView decide(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String claimNumber, @RequestBody ClaimDecisionInput input) {
        ClaimDecisionOutcome outcome = decisionService.decide(claimNumber, jwt.getSubject(), input);
        if (outcome.view().escalatedTo() == null) {
            // A decision was made (approve or deny): tell the claimant, best-effort after
            // commit. Escalations are not closures — no decision email.
            decisionEmailSender.sendDecision(outcome.holderEmail(), outcome.holderName(),
                    outcome.view());
        }
        return outcome.view();
    }
}
