package com.claims.claim;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claims.api.ClaimNotFoundException;
import com.claims.api.InvalidRequestException;
import com.claims.assignment.ClaimAssigner;
import com.claims.audit.AuditJson;
import com.claims.audit.AuditLogWriter;
import com.claims.staff.AppUser;
import com.claims.staff.AppUserRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Slice 7: the supervisor's claim-admin actions. {@code reassign} moves an open claim to a
 * target level — routed to that level's least-loaded adjuster through {@link ClaimAssigner},
 * the same rule every other reassignment uses — and {@code audit} exposes the claim's
 * immutable audit trail.
 *
 * <p>The audit trail is read-only here and append-only everywhere: a V7 trigger makes raw
 * UPDATE/DELETE on {@code audit_log} impossible at the data layer; this service only reads
 * it. Reassignment is audited ({@code CLAIM_REASSIGNED}, actor = the supervisor's subject —
 * supervisors have no app_user row, like the slice-5 escalation decision) so the actor
 * behind every re-hold is recorded.
 */
@Service
public class ClaimAdminService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ClaimRepository claims;
    private final AppUserRepository appUsers;
    private final ClaimAssigner assigner;
    private final AuditLogWriter auditLog;
    private final JdbcTemplate jdbcTemplate;

    public ClaimAdminService(ClaimRepository claims, AppUserRepository appUsers,
            ClaimAssigner assigner, AuditLogWriter auditLog, JdbcTemplate jdbcTemplate) {
        this.claims = claims;
        this.appUsers = appUsers;
        this.assigner = assigner;
        this.auditLog = auditLog;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Reassigns an open claim to a target level. The row is locked so a concurrent decision
     * or a second reassignment serializes; the claim's routing level follows its new holder
     * (aging and the gate read the level). Rejects decided and supervisor-escalated claims,
     * and a target level with no provisioned adjuster — the supervisor's explicit request
     * must not silently no-op.
     */
    @Transactional
    public ClaimAssigneeView reassign(String claimNumber, String actorSub, String level) {
        Claim claim = claims.findByClaimNumberForUpdate(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (claim.getDecision() != null) {
            throw new InvalidRequestException("This claim has already been decided.");
        }
        if ("ESCALATED_SUPERVISOR".equals(claim.getStatus())) {
            throw new InvalidRequestException(
                    "This claim is awaiting a supervisor decision and cannot be reassigned.");
        }
        if (!"L1".equals(level) && !"L2".equals(level) && !"L3".equals(level)) {
            throw new InvalidRequestException("Reassign level must be L1, L2, or L3.");
        }

        String previousLevel = claim.getLevel();
        Long previousAssigneeId = claim.getAssignedAdjusterId();
        String previousAssigneeName = displayNameOf(previousAssigneeId);
        String previousStatus = claim.getStatus();

        claim.setLevel(level);
        AppUser pick = assigner.assign(claim);
        if (pick == null) {
            throw new InvalidRequestException(
                    "No " + level + " adjuster is provisioned to reassign to.");
        }
        claims.save(claim);

        Map<String, Object> before = new HashMap<>();
        before.put("assignedAdjusterId", previousAssigneeId);
        before.put("assignedTo", previousAssigneeName);
        before.put("level", previousLevel);
        before.put("status", previousStatus);
        auditLog.append(actorSub, "CLAIM_REASSIGNED", "CLAIM", claim.getId(),
                AuditJson.of(before),
                AuditJson.of(Map.of(
                        "claimNumber", claimNumber,
                        "assignedAdjusterId", pick.getId(),
                        "assignedTo", pick.getDisplayName(),
                        "level", claim.getLevel(),
                        "status", claim.getStatus())),
                null);
        return new ClaimAssigneeView(claimNumber, claim.getStatus(), claim.getLevel(),
                pick.getDisplayName());
    }

    /** The claim's audit trail, oldest first — a 404 for an unknown claim, never a 403. */
    public List<AuditEntryView> audit(String claimNumber) {
        Claim claim = claims.findByClaimNumber(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        return jdbcTemplate.query(
                """
                SELECT id, action, actor_sub, rationale, created_at,
                       before::text AS before_text, after::text AS after_text
                FROM audit_log
                WHERE entity_type = 'CLAIM' AND entity_id = ?
                ORDER BY id
                """,
                ROW, claim.getId());
    }

    private static final RowMapper<AuditEntryView> ROW = (rs, rowNum) -> new AuditEntryView(
            rs.getLong("id"),
            rs.getString("action"),
            rs.getString("actor_sub"),
            rs.getString("rationale"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            jsonOf(rs.getString("before_text")),
            jsonOf(rs.getString("after_text")));

    private static JsonNode jsonOf(String text) {
        if (text == null) {
            return null;
        }
        try {
            return JSON.readTree(text);
        } catch (JacksonException ex) {
            throw new IllegalStateException("audit_log holds invalid JSONB payload", ex);
        }
    }

    private String displayNameOf(Long appUserId) {
        if (appUserId == null) {
            return null;
        }
        return appUsers.findById(appUserId).map(AppUser::getDisplayName).orElse(null);
    }
}
