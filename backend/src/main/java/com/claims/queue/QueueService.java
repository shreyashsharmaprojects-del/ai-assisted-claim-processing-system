package com.claims.queue;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Reads for the adjuster queue (slice 2). Open claims (anything not CLOSED) are returned
 * oldest-first by {@code created_at} (then id) so the longest-waiting work surfaces first.
 * Team view = every open claim; adjuster view = only the caller's own assignments.
 */
@Service
public class QueueService {

    private static final String SELECT = """
            SELECT c.claim_number, c.status, c.level, p.policy_number,
                   c.loss_date, c.loss_location, c.loss_description, c.created_at,
                   a.display_name AS assigned_to
            FROM claim c
            JOIN policy p ON p.id = c.policy_id
            LEFT JOIN app_user a ON a.id = c.assigned_adjuster_id
            WHERE c.status <> 'CLOSED'
            %s
            ORDER BY c.created_at, c.id
            """;

    private static final RowMapper<QueueClaimView> ROW = (rs, rowNum) -> new QueueClaimView(
            rs.getString("claim_number"),
            rs.getString("status"),
            rs.getString("level"),
            rs.getString("policy_number"),
            rs.getObject("loss_date", java.time.LocalDate.class),
            rs.getString("loss_location"),
            rs.getString("loss_description"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getString("assigned_to"));

    private final JdbcTemplate jdbcTemplate;

    public QueueService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Every open claim — the supervisor's team view. */
    public List<QueueClaimView> teamQueue() {
        return query("", new Object[0]);
    }

    /** The open claims assigned to one adjuster — that adjuster's own queue. */
    public List<QueueClaimView> adjusterQueue(Long adjusterId) {
        return query("AND c.assigned_adjuster_id = ?", new Object[] {adjusterId});
    }

    private List<QueueClaimView> query(String extraWhere, Object[] args) {
        return jdbcTemplate.query(SELECT.formatted(extraWhere), ROW, args);
    }
}
