package com.claims.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claims.api.ClaimNotFoundException;
import com.claims.api.InvalidRequestException;
import com.claims.audit.AuditJson;
import com.claims.audit.AuditLogWriter;
import com.claims.metrics.ClaimsMetrics;
import com.claims.outbox.EmailOutboxWriter;
import com.claims.policy.Policy;
import com.claims.policy.PolicyRepository;
import com.claims.routing.AuthorityGate;

/**
 * Slice 5: the supervisor half of Flow 4 — a supervisor approves or denies a claim in
 * {@code ESCALATED_SUPERVISOR}. The supervisor is the highest authority: an approval is
 * NOT amount-gated (there is no level above to escalate to), and no-self-approval is
 * structural — claims only reach {@code ESCALATED_SUPERVISOR} through an adjuster's
 * above-authority attempt or the aging job, and a supervisor can never be the actor on
 * either path, so nobody decides an escalation they caused.
 *
 * <p>Closure reuses the slice-4 machinery: the claim row is locked, approve records the
 * single payment (authorized_by NULL — a supervisor token has no app_user row; their
 * identity rides the DECISION audit as {@code actor_sub}) and closes atomically with the
 * audit row, deny closes with the rationale as claimant-visible remarks. The row lock
 * serializes concurrent decisions exactly as the adjuster decision endpoint does.
 */
@Service
public class EscalationDecisionService {

    private final ClaimRepository claims;
    private final PolicyRepository policies;
    private final PaymentRepository payments;
    private final AuditLogWriter auditLog;
    private final ClaimsMetrics metrics;
    private final EmailOutboxWriter outboxWriter;

    public EscalationDecisionService(ClaimRepository claims, PolicyRepository policies,
            PaymentRepository payments, AuditLogWriter auditLog, ClaimsMetrics metrics,
            EmailOutboxWriter outboxWriter) {
        this.claims = claims;
        this.policies = policies;
        this.payments = payments;
        this.auditLog = auditLog;
        this.metrics = metrics;
        this.outboxWriter = outboxWriter;
    }

    /**
     * Decides a supervisor-escalated claim. Returns the outcome view plus the policy-holder
     * identity so the controller can send the decision email after commit (best-effort,
     * exactly like the slice-4 decision endpoint).
     */
    @Transactional
    public ClaimDecisionOutcome decideEscalation(String claimNumber, String actorSub,
            ClaimDecisionInput input) {
        Claim claim = claims.findByClaimNumberForUpdate(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        // V21 (V3 S5): compare-and-swap — a stale caller is a 409 BEFORE any guard,
        // so a conflict never masks a guard 400.
        if (input == null || input.expectedVersion() == null
                || !input.expectedVersion().equals(claim.getVersion())) {
            throw new jakarta.persistence.OptimisticLockException(
                    "This claim changed since you opened it. Reload and retry.");
        }
        if (!"ESCALATED_SUPERVISOR".equals(claim.getStatus())) {
            if (claim.getDecision() != null) {
                throw new InvalidRequestException("This claim has already been decided.");
            }
            // An ordinary claim is decided by its assigned adjuster on the slice-4 endpoint;
            // the supervisor's surface is only for claims escalated to them. (A supervisor
            // can see team claims, so the state — not existence — is the problem: 400.)
            throw new InvalidRequestException(
                    "This claim is not awaiting a supervisor decision.");
        }

        String error = AuthorityGate.validate(input.decision(), input.indemnityAmount(),
                input.rationale());
        if (error != null) {
            throw new InvalidRequestException(error);
        }

        Policy policy = policies.findById(claim.getPolicyId())
                .orElseThrow(() -> new IllegalStateException(
                        "Claim " + claimNumber + " references a missing policy "
                                + claim.getPolicyId()));
        String rationale = input.rationale().trim();
        Instant now = Instant.now();

        if ("DENIED".equals(input.decision())) {
            return deny(claim, policy, actorSub, rationale, now);
        }
        return approve(claim, policy, actorSub, input.indemnityAmount(), rationale, now);
    }

    private ClaimDecisionOutcome deny(Claim claim, Policy policy, String actorSub,
            String rationale, Instant now) {
        claim.deny(rationale, now);
        // Flush: see ClaimDecisionService.deny.
        claims.saveAndFlush(claim);
        metrics.decision("DENIED");
        auditLog.append(actorSub, "DECISION", "CLAIM", claim.getId(),
                AuditJson.of(Map.of("status", "ESCALATED_SUPERVISOR", "level", claim.getLevel())),
                AuditJson.of(Map.of("claimNumber", claim.getClaimNumber(),
                        "decision", "DENIED", "decisionRemarks", rationale,
                        "status", "CLOSED")),
                rationale);
        ClaimDecisionOutcome result = outcome(claim, policy);
        // R2: same-transaction outbox write — the notice commits or rolls back with the
        // supervisor's closure.
        outboxWriter.enqueueDecision(claim.getId(), policy.getHolderEmail(),
                policy.getHolderName(), result.view());
        return result;
    }

    private ClaimDecisionOutcome approve(Claim claim, Policy policy, String actorSub,
            BigDecimal amount, String rationale, Instant now) {
        claim.approve(amount, now);
        // Flush: see ClaimDecisionService.deny.
        claims.saveAndFlush(claim);
        metrics.decision("APPROVED");
        // authorized_by is NULL on purpose: a supervisor token has no app_user row (the
        // staff cache is L1/L2 adjusters only); the DECISION audit row below carries their
        // subject as the actor.
        payments.save(new Payment(claim.getId(), amount, null, now));
        auditLog.append(actorSub, "DECISION", "CLAIM", claim.getId(),
                AuditJson.of(Map.of("status", "ESCALATED_SUPERVISOR", "level", claim.getLevel())),
                AuditJson.of(Map.of("claimNumber", claim.getClaimNumber(),
                        "decision", "APPROVED", "indemnityAmount", amount,
                        "status", "CLOSED")),
                rationale);
        ClaimDecisionOutcome result = outcome(claim, policy);
        // R2: same-transaction outbox write (see deny above).
        outboxWriter.enqueueDecision(claim.getId(), policy.getHolderEmail(),
                policy.getHolderName(), result.view());
        return result;
    }

    private ClaimDecisionOutcome outcome(Claim claim, Policy policy) {
        return new ClaimDecisionOutcome(
                new ClaimDecisionView(claim.getClaimNumber(), claim.getDecision(),
                        claim.getIndemnityAmount(), claim.getDecisionRemarks(), null),
                policy.getHolderName(), policy.getHolderEmail());
    }
}
