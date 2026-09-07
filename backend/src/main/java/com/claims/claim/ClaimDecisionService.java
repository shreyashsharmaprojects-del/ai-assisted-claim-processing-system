package com.claims.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claims.api.ClaimNotFoundException;
import com.claims.api.InvalidRequestException;
import com.claims.assignment.ClaimAssigner;
import com.claims.audit.AuditJson;
import com.claims.audit.AuditLogWriter;
import com.claims.metrics.ClaimsMetrics;
import com.claims.outbox.EmailOutboxWriter;
import com.claims.policy.Policy;
import com.claims.policy.PolicyRepository;
import com.claims.routing.AuthorityConfig;
import com.claims.routing.AuthorityGate;
import com.claims.routing.AuthorityGate.Outcome;
import com.claims.routing.AuthorityConfigRepository;
import com.claims.staff.AppUser;
import com.claims.staff.AppUserRepository;

/**
 * Slice 4: the decision endpoint — the product's core rule. The acting adjuster decides an
 * assigned claim; the {@link AuthorityGate} decides whether the indemnity figure is within
 * their level's authority. Approve-within-level records the single payment and closes the
 * claim atomically; an above-level approval is never granted and escalates instead
 * (re-assignment to the least-loaded L2 adjuster, or {@code ESCALATED_SUPERVISOR} above the
 * L2 limit). A denial always closes.
 *
 * <p>Every closure writes a {@code DECISION} audit row with the actor and a non-null
 * rationale; every escalation writes a {@code CLAIM_ESCALATED} row attributed to the
 * adjuster whose approval attempt triggered it. The claim row is locked
 * (SELECT ... FOR UPDATE) so concurrent decisions serialize and the second sees the CLOSED
 * state rather than racing into the single-payment unique constraint.
 */
@Service
public class ClaimDecisionService {

    private static final Logger log = LoggerFactory.getLogger(ClaimDecisionService.class);

    private final ClaimRepository claims;
    private final PolicyRepository policies;
    private final AuthorityConfigRepository authorityConfigs;
    private final AppUserRepository appUsers;
    private final ClaimAccess access;
    private final ClaimAssigner assigner;
    private final PaymentRepository payments;
    private final AuditLogWriter auditLog;
    private final ClaimsMetrics metrics;
    private final EmailOutboxWriter outboxWriter;

    public ClaimDecisionService(ClaimRepository claims, PolicyRepository policies,
            AuthorityConfigRepository authorityConfigs, AppUserRepository appUsers,
            ClaimAccess access, ClaimAssigner assigner, PaymentRepository payments,
            AuditLogWriter auditLog, ClaimsMetrics metrics, EmailOutboxWriter outboxWriter) {
        this.claims = claims;
        this.policies = policies;
        this.authorityConfigs = authorityConfigs;
        this.appUsers = appUsers;
        this.access = access;
        this.assigner = assigner;
        this.payments = payments;
        this.auditLog = auditLog;
        this.metrics = metrics;
        this.outboxWriter = outboxWriter;
    }

    /**
     * Decides an assigned claim. Returns the outcome view plus the policy-holder identity so
     * the controller can send the decision email after commit (best-effort, like the other
     * emails).
     */
    @Transactional
    public ClaimDecisionOutcome decide(String claimNumber, String actorSub,
            ClaimDecisionInput input) {
        Claim claim = claims.findByClaimNumberForUpdate(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (!access.internalReaderMaySee(claim, actorSub, false)) {
            // Only the assigned adjuster decides. A supervisor has their own escalation
            // surface (slice 5); this endpoint is never theirs.
            throw new ClaimNotFoundException();
        }
        if ("CLOSED".equals(claim.getStatus()) || claim.getDecision() != null) {
            throw new InvalidRequestException("This claim has already been decided.");
        }

        String error = AuthorityGate.validate(input.decision(), input.indemnityAmount(),
                input.rationale());
        if (error != null) {
            throw new InvalidRequestException(error);
        }

        // The actor is the assigned adjuster (ClaimAccess matched them through the staff
        // cache), so the row exists; its level is the authority level the gate applies.
        AppUser actor = appUsers.findByKeycloakSub(actorSub)
                .orElseThrow(IllegalStateException::new);
        Policy policy = policies.findById(claim.getPolicyId())
                .orElseThrow(() -> new IllegalStateException(
                        "Claim " + claimNumber + " references a missing policy "
                                + claim.getPolicyId()));
        AuthorityConfig config = authorityConfigs.findAll().stream()
                .filter(c -> c.getProductCode().equals(policy.getProductCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No authority config exists for product " + policy.getProductCode()
                                + " (claim " + claimNumber + ")"));
        String rationale = input.rationale().trim();
        Instant now = Instant.now();

        if ("DENIED".equals(input.decision())) {
            return deny(claim, actor, policy, rationale, now);
        }
        return approve(claim, actor, policy, config, input.indemnityAmount(), rationale, now);
    }

    private ClaimDecisionOutcome deny(Claim claim, AppUser actor, Policy policy,
            String rationale, Instant now) {
        claim.deny(rationale, now);
        claims.save(claim);
        metrics.decision("DENIED");
        auditLog.append(actor.getKeycloakSub(), "DECISION", "CLAIM", claim.getId(),
                AuditJson.of(Map.of("status", "UNDER_REVIEW")),
                AuditJson.of(Map.of("claimNumber", claim.getClaimNumber(),
                        "decision", "DENIED", "decisionRemarks", rationale,
                        "status", "CLOSED")),
                rationale);
        ClaimDecisionOutcome result = outcome(claim, policy, null);
        // R2: the decision mail is an outbox row in this same transaction — it commits or
        // rolls back with the closure, so the notice is never lost and never sent for a
        // decision that did not happen.
        outboxWriter.enqueueDecision(claim.getId(), policy.getHolderEmail(),
                policy.getHolderName(), result.view());
        return result;
    }

    private ClaimDecisionOutcome approve(Claim claim, AppUser actor, Policy policy,
            AuthorityConfig config, BigDecimal amount, String rationale, Instant now) {
        Outcome outcome = AuthorityGate.evaluate("APPROVED", actor.getLevel(), amount,
                config.getL1LimitAmount(), config.getL2LimitAmount());
        String previousLevel = claim.getLevel();
        Long previousAssignee = claim.getAssignedAdjusterId();

        switch (outcome) {
            case APPROVE -> {
                claim.approve(amount, now);
                claims.save(claim);
                metrics.decision("APPROVED");
                payments.save(new Payment(claim.getId(), amount, actor.getId(), now));
                auditLog.append(actor.getKeycloakSub(), "DECISION", "CLAIM", claim.getId(),
                        AuditJson.of(Map.of("status", "UNDER_REVIEW", "level", previousLevel)),
                        AuditJson.of(Map.of("claimNumber", claim.getClaimNumber(),
                                "decision", "APPROVED", "indemnityAmount", amount,
                                "status", "CLOSED")),
                        rationale);
                ClaimDecisionOutcome result = outcome(claim, policy, null);
                // R2: same-transaction outbox write (see deny above).
                outboxWriter.enqueueDecision(claim.getId(), policy.getHolderEmail(),
                        policy.getHolderName(), result.view());
                return result;
            }
            case ESCALATE_TO_L2 -> {
                // Above the actor's level but within the L2 limit: a system re-assignment to
                // the least-loaded L2 adjuster (the claim's level becomes L2, status returns
                // to UNDER_REVIEW under the new assignee — never approved by the actor).
                claim.setLevel("L2");
                AppUser l2 = assigner.assign(claim);
                String escalatedTo = "L2";
                if (l2 == null) {
                    // Provisioning gap: no L2 adjuster exists to take the escalation, so the
                    // claim goes to the supervisor rather than staying with an actor whose
                    // authority was just rejected. The level is reverted first — nothing at
                    // L2 accepted the claim, so it must not look L2-routed.
                    log.warn("Claim {} escalated above L1 but no L2 adjuster is provisioned; "
                            + "moving it to ESCALATED_SUPERVISOR", claim.getClaimNumber());
                    claim.setLevel(previousLevel);
                    claim.escalateToSupervisor();
                    escalatedTo = "SUPERVISOR";
                }
                claims.save(claim);
                metrics.escalation(escalatedTo);
                Map<String, Object> after = new java.util.HashMap<>();
                after.put("claimNumber", claim.getClaimNumber());
                after.put("attemptedDecision", "APPROVED");
                after.put("indemnityAmount", amount);
                after.put("status", claim.getStatus());
                after.put("level", claim.getLevel());
                after.put("escalatedTo", escalatedTo);
                after.put("assignedAdjusterId", l2 == null ? null : l2.getId());
                after.put("assignedTo", l2 == null ? null : l2.getDisplayName());
                auditLog.append(actor.getKeycloakSub(), "CLAIM_ESCALATED", "CLAIM",
                        claim.getId(),
                        AuditJson.of(Map.of("status", "UNDER_REVIEW", "level", previousLevel,
                                "assignedAdjusterId", previousAssignee)),
                        AuditJson.of(after),
                        rationale);
                return outcome(claim, policy, escalatedTo);
            }
            case ESCALATE_TO_SUPERVISOR -> {
                claim.escalateToSupervisor();
                claims.save(claim);
                metrics.escalation("SUPERVISOR");
                auditLog.append(actor.getKeycloakSub(), "CLAIM_ESCALATED", "CLAIM",
                        claim.getId(),
                        AuditJson.of(Map.of("status", "UNDER_REVIEW", "level", previousLevel,
                                "assignedAdjusterId", previousAssignee)),
                        AuditJson.of(Map.of("claimNumber", claim.getClaimNumber(),
                                "attemptedDecision", "APPROVED", "indemnityAmount", amount,
                                "status", "ESCALATED_SUPERVISOR", "level", claim.getLevel(),
                                "escalatedTo", "SUPERVISOR")),
                        rationale);
                return outcome(claim, policy, "SUPERVISOR");
            }
            default -> throw new IllegalStateException("Unexpected gate outcome " + outcome);
        }
    }

    private ClaimDecisionOutcome outcome(Claim claim, Policy policy, String escalatedTo) {
        return new ClaimDecisionOutcome(
                new ClaimDecisionView(claim.getClaimNumber(), claim.getDecision(),
                        claim.getIndemnityAmount(), claim.getDecisionRemarks(), escalatedTo),
                policy.getHolderName(), policy.getHolderEmail());
    }
}
