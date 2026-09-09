package com.claims.claim;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.claims.claim.StagedWorkflowService.ReferResult;
import com.claims.mail.DecisionEmailSender;
import com.claims.outbox.EmailOutboxDispatcher;

/**
 * V2-4/V2-5/V2-6: the staged adjuster workflow surface. The staged full view (stage,
 * covers, verifications, authority context); review transitions (advance / reject /
 * NEED_INFO); verification records; per-cover assessment; the dual-shape decision (V1
 * single-figure passthrough on legacy claims, per-cover outcomes on claims with
 * covers); explicit referral upwards; and the claimant NEED_INFO response.
 *
 * <p>Object auth is 404-not-403 throughout (the service owns it via
 * {@link ClaimAccess}); URL roles admit the three adjuster rungs (+ supervisor where
 * noted) and CLAIMANT only on the need-info response.
 */
@RestController
@RequestMapping("/api/claims")
public class StagedWorkflowController {

    private final StagedWorkflowService workflow;
    private final DecisionEmailSender decisionEmailSender;
    private final EmailOutboxDispatcher dispatcher;

    public StagedWorkflowController(StagedWorkflowService workflow,
            DecisionEmailSender decisionEmailSender, EmailOutboxDispatcher dispatcher) {
        this.workflow = workflow;
        this.decisionEmailSender = decisionEmailSender;
        this.dispatcher = dispatcher;
    }

    /** Staged full view: stage, NEED_INFO state, covers, verifications, authority. */
    @GetMapping("/{claimNumber}/staged")
    public StagedClaimView staged(@AuthenticationPrincipal Jwt jwt,
            Authentication authentication, @PathVariable String claimNumber) {
        return workflow.fullView(claimNumber, jwt.getSubject(),
                Authorities.isSupervisor(authentication));
    }

    /** Review transition: ADVANCE | REJECT | NEED_INFO. Returns the staged view. */
    @PostMapping("/{claimNumber}/review")
    public StagedClaimView review(@AuthenticationPrincipal Jwt jwt,
            Authentication authentication, @PathVariable String claimNumber,
            @RequestBody ReviewInput input) {
        StagedClaimView view = workflow.review(claimNumber, jwt.getSubject(),
                Authorities.isSupervisor(authentication), input);
        if ("CLOSED".equals(view.status())) {
            // Review rejection closes like a denial: flush the in-transaction decision
            // mail so the best-effort immediate send behaves like every other closure.
            dispatcher.dispatch();
        }
        return view;
    }

    /** Open a verification (DIGITAL | PHYSICAL); returns the created row. */
    @PostMapping("/{claimNumber}/verifications")
    public StagedClaimView.VerificationView createVerification(
            @AuthenticationPrincipal Jwt jwt, Authentication authentication,
            @PathVariable String claimNumber, @RequestBody VerificationInput input) {
        return workflow.createVerification(claimNumber, jwt.getSubject(),
                Authorities.isSupervisor(authentication), input);
    }

    /** Update a verification row; COMPLETE requires outcome + notes. */
    @PutMapping("/{claimNumber}/verifications/{id}")
    public StagedClaimView.VerificationView updateVerification(
            @AuthenticationPrincipal Jwt jwt, Authentication authentication,
            @PathVariable String claimNumber, @PathVariable Long id,
            @RequestBody VerificationUpdate input) {
        return workflow.updateVerification(claimNumber, id, jwt.getSubject(),
                Authorities.isSupervisor(authentication), input);
    }

    /** Per-cover assessment (VERIFICATION→DECISION). Returns the staged view. */
    @PutMapping("/{claimNumber}/assessment")
    public StagedClaimView assess(@AuthenticationPrincipal Jwt jwt,
            Authentication authentication, @PathVariable String claimNumber,
            @RequestBody AssessmentInput input) {
        return workflow.assess(claimNumber, jwt.getSubject(),
                Authorities.isSupervisor(authentication), input);
    }

    /**
     * V18: send the claim one step back (DECISION→VERIFICATION or
     * VERIFICATION→REVIEW) with a reason. Returns the staged view.
     */
    @PostMapping("/{claimNumber}/send-back")
    public StagedClaimView sendBack(@AuthenticationPrincipal Jwt jwt,
            Authentication authentication, @PathVariable String claimNumber,
            @RequestBody SendBackInput input) {
        return workflow.sendBack(claimNumber, jwt.getSubject(),
                Authorities.isSupervisor(authentication), input);
    }

    /**
     * The dual-shape decision: legacy single-figure on no-cover claims (V1 behaviour,
     * byte-identical, including auto-escalation), per-cover outcomes on claims with
     * covers (within-authority closes, above-authority saves proposals and stays).
     */
    @PostMapping("/{claimNumber}/cover-decision")
    public Object decide(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String claimNumber, @RequestBody CoverDecisionInput input) {
        Object result = workflow.decide(claimNumber, jwt.getSubject(), input);
        if (result instanceof ClaimDecisionOutcome outcome
                && outcome.view().escalatedTo() == null) {
            decisionEmailSender.sendDecision(outcome.holderEmail(), outcome.holderName(),
                    outcome.view());
            dispatcher.dispatch();
        }
        // Cover-path closures enqueue their own outbox row in-transaction; flush it so
        // the best-effort immediate send behaves like every other decision.
        if (result instanceof StagedClaimView view && "CLOSED".equals(view.status())) {
            dispatcher.dispatch();
        }
        return result;
    }

    /** Explicit referral upwards (named senior or auto-pick; supervisor fallback). */
    @PostMapping("/{claimNumber}/refer")
    public ReferView refer(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String claimNumber, @RequestBody ReferInput input) {
        ReferResult result = workflow.refer(claimNumber, jwt.getSubject(), input);
        return new ReferView(result.claimNumber(), result.status(), result.level(),
                result.assignedTo(), result.escalatedTo());
    }

    /**
     * V22 (V3 S6): supervisor reopen of a CLOSED claim — back to UNDER_REVIEW at
     * REVIEW, reassigned. Flushes the in-transaction reopen mail like closures.
     */
    @PostMapping("/{claimNumber}/reopen")
    public StagedClaimView reopen(@AuthenticationPrincipal Jwt jwt,
            Authentication authentication, @PathVariable String claimNumber,
            @RequestBody ReopenInput input) {
        StagedClaimView view = workflow.reopen(claimNumber, jwt.getSubject(),
                Authorities.isSupervisor(authentication), input);
        dispatcher.dispatch();
        return view;
    }

    /** Claimant response to NEED_INFO: back to the prior stage, reassigned. */
    @PostMapping("/{claimNumber}/need-info-response")
    public ClaimantClaimView respond(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String claimNumber, @RequestBody NeedInfoResponseInput input) {
        return workflow.respondToNeedInfo(claimNumber, jwt.getSubject(), input);
    }

    /**
     * Supervisor cover-decision on an ESCALATED_SUPERVISOR claim (ungated, rationale
     * required). Legacy no-cover claims keep the single-figure escalation service.
     */
    @PostMapping("/{claimNumber}/escalation-cover-decision")
    public Object decideEscalation(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String claimNumber, @RequestBody CoverDecisionInput input) {
        Object result = workflow.decideEscalation(claimNumber, jwt.getSubject(), input);
        if (result instanceof ClaimDecisionOutcome outcome) {
            decisionEmailSender.sendDecision(outcome.holderEmail(), outcome.holderName(),
                    outcome.view());
            dispatcher.dispatch();
        }
        if (result instanceof StagedClaimView view && "CLOSED".equals(view.status())) {
            dispatcher.dispatch();
        }
        return result;
    }

    public record ReferView(String claimNumber, String status, String level,
            String assignedTo, String escalatedTo) {
    }
}
