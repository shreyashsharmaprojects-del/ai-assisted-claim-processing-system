package com.claims.assignment;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import com.claims.claim.Claim;
import com.claims.staff.AppUser;
import com.claims.staff.AppUserRepository;

/**
 * Assigns a freshly filed claim to the least-loaded adjuster of its level
 * (fewest open claims, lowest {@code app_user.id} on ties — see LoadBalancer), moving the
 * claim to UNDER_REVIEW. Runs inside the FNOL transaction (slice 2).
 *
 * <p>Concurrency: the candidate adjusters' rows are locked (SELECT ... FOR UPDATE) before
 * counts are read, so two simultaneous FNOLs to the same level serialize: the second
 * blocks here and recounts after the first commits, and can never double-assign to the
 * adjuster the first one just chose.
 */
@Component
public class ClaimAssigner {

    private static final Logger log = LoggerFactory.getLogger(ClaimAssigner.class);

    private final AppUserRepository appUsers;
    private final JdbcTemplate jdbcTemplate;

    public ClaimAssigner(AppUserRepository appUsers, JdbcTemplate jdbcTemplate) {
        this.appUsers = appUsers;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return the adjuster this claim was assigned to, or {@code null} when no adjuster of
     *         the claim's level is provisioned (the claim stays UNASSIGNED — a server
     *         provisioning gap, logged here).
     */
    public AppUser assign(Claim claim) {
        String level = claim.getLevel();
        // Serialize concurrent assignments within a level. Any other FNOL transaction to
        // the same level blocks on this lock until this transaction commits, then recounts.
        jdbcTemplate.queryForList(
                "SELECT id FROM app_user WHERE level = ? ORDER BY id FOR UPDATE", Long.class, level);

        List<Long> candidateIds = appUsers.findByLevelOrderById(level).stream()
                .map(AppUser::getId)
                .toList();
        if (candidateIds.isEmpty()) {
            log.warn("Claim {} is level {} but no adjusters of that level are provisioned; "
                    + "leaving it unassigned", claim.getClaimNumber(), level);
            return null;
        }

        Long pick = LoadBalancer.leastLoaded(candidateIds, openClaimCounts(candidateIds));
        AppUser adjuster = appUsers.findById(pick).orElseThrow();
        claim.assignTo(adjuster.getId(), Instant.now());
        return adjuster;
    }

    /** Open claims per candidate (a claim is open unless it is CLOSED). */
    private Map<Long, Long> openClaimCounts(List<Long> candidateIds) {
        Map<Long, Long> counts = new HashMap<>();
        String placeholders = String.join(",", candidateIds.stream().map(id -> "?").toList());
        RowCallbackHandler handler = rs -> counts.put(rs.getLong(1), rs.getLong(2));
        jdbcTemplate.query(
                "SELECT assigned_adjuster_id, COUNT(*) FROM claim "
                        + "WHERE assigned_adjuster_id IN (" + placeholders + ") "
                        + "AND status <> 'CLOSED' "
                        + "GROUP BY assigned_adjuster_id",
                handler,
                candidateIds.toArray());
        return counts;
    }
}
