package com.claims.staff;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claims.api.ClaimNotFoundException;
import com.claims.assignment.ClaimAssigner;
import com.claims.audit.AuditJson;
import com.claims.audit.AuditLogWriter;
import com.claims.claim.Claim;
import com.claims.claim.ClaimRepository;

/**
 * V3 S7: the supervisor's staff surface. Lists every staff row with its open-claim
 * load (the LoadBalancer's definition: COUNT claim WHERE assigned_adjuster_id = id
 * AND status &lt;&gt; 'CLOSED'), and flips the {@code active} flag. Deactivation
 * drains the adjuster's open queue through {@link ClaimAssigner} (same-level
 * least-loaded, skill-aware like every other assignment) with a CLAIM_REASSIGNED
 * audit row per move; claims with no eligible target park UNASSIGNED (the queue
 * {@code ?status=UNASSIGNED} filter is the attention list — no reason column
 * exists). Reactivation flips without moving. No Keycloak writes this slice.
 *
 * <p>No @Version on app_user: a single-writer admin action — plain update.
 */
@Service
public class StaffService {

    private static final RowMapper<StaffRow> ROW = (rs, rowNum) -> new StaffRow(
            rs.getLong("id"),
            rs.getString("display_name"),
            rs.getString("email"),
            rs.getString("level"),
            rs.getBoolean("active"),
            rs.getString("keycloak_sub"),
            rs.getLong("open_claims"));

    private final AppUserRepository appUsers;
    private final ClaimRepository claims;
    private final ClaimAssigner assigner;
    private final AuditLogWriter auditLog;
    private final JdbcTemplate jdbcTemplate;

    public StaffService(AppUserRepository appUsers, ClaimRepository claims,
            ClaimAssigner assigner, AuditLogWriter auditLog, JdbcTemplate jdbcTemplate) {
        this.appUsers = appUsers;
        this.claims = claims;
        this.assigner = assigner;
        this.auditLog = auditLog;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Every staff row with its open-claim load, in deterministic (id) order.
     * Supervisor-only at the URL; no per-row visibility rule applies.
     */
    public List<StaffRow> list() {
        return jdbcTemplate.query(
                """
                SELECT a.id, a.display_name, a.email, a.level, a.active, a.keycloak_sub,
                       (SELECT count(*) FROM claim c
                        WHERE c.assigned_adjuster_id = a.id AND c.status <> 'CLOSED') AS open_claims
                FROM app_user a
                ORDER BY a.id
                """,
                ROW);
    }

    /**
     * Flips one adjuster's active flag. On deactivation (active=false) every open
     * claim of that adjuster is reassigned via {@link ClaimAssigner} (the level is
     * preserved — the claim stays at its routing tier and moves to the
     * least-loaded active adjuster of that level); claims with no eligible target
     * park UNASSIGNED (audit records the null target). Every move writes a
     * CLAIM_REASSIGNED audit row (actor = the supervisor's subject), the same
     * shape ClaimAdminService.reassign uses. Reactivation flips without moving.
     * Unknown id → 404 (staff reads are supervisor-only, so 404 is safe).
     */
    @Transactional
    public StaffRow setActive(Long staffId, boolean active, String actorSub) {
        AppUser staff = appUsers.findById(staffId)
                .orElseThrow(ClaimNotFoundException::new);
        staff.setActive(active);
        // Flush before the JDBC re-read below: raw JdbcTemplate queries do not
        // trigger a persistence-context flush, so rowOf would see the stale flag.
        appUsers.saveAndFlush(staff);

        if (!active) {
            drainOpenClaims(staff, actorSub);
        }
        return rowOf(staff.getId());
    }

    private void drainOpenClaims(AppUser staff, String actorSub) {
        List<String> claimNumbers = jdbcTemplate.queryForList(
                "SELECT claim_number FROM claim WHERE assigned_adjuster_id = ? "
                        + "AND status <> 'CLOSED' ORDER BY id",
                String.class, staff.getId());
        for (String claimNumber : claimNumbers) {
            Claim claim = claims.findByClaimNumberForUpdate(claimNumber).orElse(null);
            if (claim == null || "CLOSED".equals(claim.getStatus())
                    || !staff.getId().equals(claim.getAssignedAdjusterId())) {
                continue;
            }
            Long previousAssigneeId = claim.getAssignedAdjusterId();
            String previousStatus = claim.getStatus();

            com.claims.staff.AppUser pick = assigner.assign(claim);
            if (pick == null) {
                // No eligible active target (the only same-level adjuster was the
                // deactivated one, or none is provisioned): park UNASSIGNED — the
                // supervisor attention list via ?status=UNASSIGNED.
                claim.setAssignedAdjusterId(null, null);
                claim.setStatus("UNASSIGNED");
            }
            claims.save(claim);

            Map<String, Object> before = new HashMap<>();
            before.put("assignedAdjusterId", previousAssigneeId);
            before.put("assignedTo", staff.getDisplayName());
            before.put("level", claim.getLevel());
            before.put("status", previousStatus);
            Map<String, Object> after = new HashMap<>();
            after.put("claimNumber", claim.getClaimNumber());
            after.put("assignedAdjusterId", pick == null ? null : pick.getId());
            after.put("assignedTo", pick == null ? null : pick.getDisplayName());
            after.put("level", claim.getLevel());
            after.put("status", claim.getStatus());
            auditLog.append(actorSub, "CLAIM_REASSIGNED", "CLAIM", claim.getId(),
                    AuditJson.of(before), AuditJson.of(after), null);
        }
    }

    private StaffRow rowOf(Long staffId) {
        List<StaffRow> rows = jdbcTemplate.query(
                """
                SELECT a.id, a.display_name, a.email, a.level, a.active, a.keycloak_sub,
                       (SELECT count(*) FROM claim c
                        WHERE c.assigned_adjuster_id = a.id AND c.status <> 'CLOSED') AS open_claims
                FROM app_user a
                WHERE a.id = ?
                """,
                ROW, staffId);
        if (rows.isEmpty()) {
            throw new ClaimNotFoundException();
        }
        return rows.get(0);
    }

    /** One staff list row. {@code keycloakSub} is exposed so the UI can copy it. */
    public record StaffRow(Long id, String displayName, String email, String level,
            boolean active, String keycloakSub, long openClaims) {
    }
}
