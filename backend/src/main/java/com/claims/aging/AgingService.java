package com.claims.aging;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claims.aging.AgingPolicy.AgingStep;
import com.claims.assignment.ClaimAssigner;
import com.claims.audit.AuditJson;
import com.claims.audit.AuditLogWriter;
import com.claims.claim.Claim;
import com.claims.claim.ClaimRepository;
import com.claims.metrics.ClaimsMetrics;
import com.claims.staff.AppUser;

/**
 * The aging background job (Flow 6): open claims that have sat undecided past the service
 * commitment are pushed up the ladder — 3 days from FNOL to the L2 tier, 5 days to the
 * supervisor. Never a user action; the scheduler calls {@link #ageClaims(Instant)} with the
 * real clock and tests call it with a fixed instant (determinism, per the plan's risk
 * list).
 *
 * <p>Reading the anchor: {@code claim.created_at} (FNOL time) is not entity-mapped, so
 * candidates are read through JDBC; each candidate's row is then locked (the slice-4
 * decision lock) so a concurrent decision or another job run serializes and the transition
 * is recomputed against the freshly locked state — a claim another transaction just closed
 * or escalated is skipped, and running the job twice never double-transitions a claim.
 * Every transition writes a {@code CLAIM_ESCALATED} audit row with a NULL actor (aging is
 * a system action, like FNOL assignment) and a rationale naming the rung.
 */
@Service
public class AgingService {

    private static final Logger log = LoggerFactory.getLogger(AgingService.class);

    private final ClaimRepository claims;
    private final ClaimAssigner assigner;
    private final AuditLogWriter auditLog;
    private final JdbcTemplate jdbcTemplate;
    private final ClaimsMetrics metrics;

    public AgingService(ClaimRepository claims, ClaimAssigner assigner,
            AuditLogWriter auditLog, JdbcTemplate jdbcTemplate, ClaimsMetrics metrics) {
        this.claims = claims;
        this.assigner = assigner;
        this.auditLog = auditLog;
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
    }

    /**
     * Runs one aging pass as seen at {@code now}. Candidates are claims old enough to be on
     * the ladder (open, created 3+ days before {@code now}); each is transitioned at most
     * once per pass.
     *
     * @return how many claims the pass transitioned (useful to tests; the scheduler logs it)
     */
    @Transactional
    public int ageClaims(Instant now) {
        Timestamp oldest = Timestamp.from(now.minus(3, ChronoUnit.DAYS));
        List<Candidate> candidates = jdbcTemplate.query(
                "SELECT claim_number, created_at FROM claim "
                        + "WHERE status IN ('UNASSIGNED', 'UNDER_REVIEW') "
                        + "AND created_at <= ? ORDER BY id",
                (rs, rowNum) -> new Candidate(rs.getString("claim_number"),
                        rs.getTimestamp("created_at").toInstant()),
                oldest);

        int aged = 0;
        for (Candidate candidate : candidates) {
            // Lock the row, then decide against the freshly locked state: another
            // transaction may have decided or escalated the claim since the select.
            Claim claim = claims.findByClaimNumberForUpdate(candidate.claimNumber()).orElse(null);
            if (claim == null) {
                continue;
            }
            AgingStep step = AgingPolicy.stepFor(claim.getStatus(), claim.getLevel(),
                    candidate.createdAt(), now);
            switch (step) {
                case REASSIGN_TO_L2 -> {
                    reassignToL2(claim);
                    metrics.escalation("L2");
                    aged++;
                }
                case ESCALATE_TO_SUPERVISOR -> {
                    escalateToSupervisor(claim);
                    metrics.escalation("SUPERVISOR");
                    aged++;
                }
                default -> {
                    // No action (still within thresholds, already L2-held, or immune).
                }
            }
        }
        return aged;
    }

    /**
     * The 3-day rung: push the claim to the L2 tier (level becomes L2, then the
     * least-loaded L2 adjuster takes it — the slice-4 escalation funnel). When no L2
     * adjuster is provisioned, the claim escalates to the supervisor instead, exactly like
     * the slice-4 provisioning-gap rule: it must not stay below the L2 tier unanswered.
     */
    private void reassignToL2(Claim claim) {
        String previousLevel = claim.getLevel();
        String previousStatus = claim.getStatus();
        Long previousAssignee = claim.getAssignedAdjusterId();

        claim.setLevel("L2");
        AppUser l2 = assigner.assign(claim);
        String escalatedTo = "L2";
        if (l2 == null) {
            log.warn("Aging claim {} reached the 3-day L2 push but no L2 adjuster is "
                    + "provisioned; moving it to ESCALATED_SUPERVISOR", claim.getClaimNumber());
            claim.setLevel(previousLevel);
            claim.escalateToSupervisor();
            escalatedTo = "SUPERVISOR";
        }
        claims.save(claim);

        Map<String, Object> after = new HashMap<>();
        after.put("claimNumber", claim.getClaimNumber());
        after.put("status", claim.getStatus());
        after.put("level", claim.getLevel());
        after.put("escalatedTo", escalatedTo);
        after.put("assignedAdjusterId", l2 == null ? null : l2.getId());
        after.put("assignedTo", l2 == null ? null : l2.getDisplayName());
        auditLog.append(null, "CLAIM_ESCALATED", "CLAIM", claim.getId(),
                AuditJson.of(before(previousStatus, previousLevel, previousAssignee)),
                AuditJson.of(after),
                "Aged at least 3 days without a decision.");
    }

    /** The 5-day rung: move the claim to ESCALATED_SUPERVISOR (no adjuster holds it). */
    private void escalateToSupervisor(Claim claim) {
        String previousLevel = claim.getLevel();
        String previousStatus = claim.getStatus();
        Long previousAssignee = claim.getAssignedAdjusterId();

        claim.escalateToSupervisor();
        claims.save(claim);

        Map<String, Object> after = new HashMap<>();
        after.put("claimNumber", claim.getClaimNumber());
        after.put("status", claim.getStatus());
        after.put("level", claim.getLevel());
        after.put("escalatedTo", "SUPERVISOR");
        auditLog.append(null, "CLAIM_ESCALATED", "CLAIM", claim.getId(),
                AuditJson.of(before(previousStatus, previousLevel, previousAssignee)),
                AuditJson.of(after),
                "Aged at least 5 days without a decision.");
    }

    /** Null-tolerant before payload (a claim can be UNASSIGNED with no assignee). */
    private static Map<String, Object> before(String status, String level, Long assigneeId) {
        Map<String, Object> before = new HashMap<>();
        before.put("status", status);
        before.put("level", level);
        before.put("assignedAdjusterId", assigneeId);
        return before;
    }

    private record Candidate(String claimNumber, Instant createdAt) {
    }
}
