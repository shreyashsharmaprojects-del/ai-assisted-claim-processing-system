package com.claims.queue;

import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The supervisor operations dashboard (GET /api/dashboard): one read-only aggregate over
 * the claim + audit tables. No per-claim data leaves this endpoint — only counts and a
 * monthly approved total — so there is no visibility-wall surface to guard beyond the
 * SUPERVISOR-only URL rule. Every figure is a single indexed aggregate; the endpoint is
 * safe to poll.
 */
@Service
public class DashboardService {

    private final JdbcTemplate jdbcTemplate;

    public DashboardService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DashboardStats stats() {
        long open = count("SELECT count(*) FROM claim WHERE status <> 'CLOSED'");
        long unassigned = count("SELECT count(*) FROM claim WHERE status = 'UNASSIGNED'");
        long underReview = count("SELECT count(*) FROM claim WHERE status = 'UNDER_REVIEW'");
        long escalated = count("SELECT count(*) FROM claim WHERE status = 'ESCALATED_SUPERVISOR'");
        long closed = count("SELECT count(*) FROM claim WHERE status = 'CLOSED'");
        long agedL2 = count("""
                SELECT count(*) FROM audit_log
                WHERE action = 'CLAIM_ESCALATED'
                  AND created_at >= now() - interval '7 days'
                  AND after->>'escalatedTo' = 'L2'
                """);
        long agedSup = count("""
                SELECT count(*) FROM audit_log
                WHERE action = 'CLAIM_ESCALATED'
                  AND created_at >= now() - interval '7 days'
                  AND after->>'escalatedTo' = 'SUPERVISOR'
                """);
        BigDecimal approved = jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(indemnity_amount), 0) FROM claim
                WHERE decision = 'APPROVED'
                  AND closed_at >= date_trunc('month', now())
                """, BigDecimal.class);
        return new DashboardStats(open, unassigned, underReview, escalated, closed, agedL2,
                agedSup, approved == null ? BigDecimal.ZERO : approved);
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
