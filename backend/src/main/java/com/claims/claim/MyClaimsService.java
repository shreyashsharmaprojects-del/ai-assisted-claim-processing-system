package com.claims.claim;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * The claimant's own history (GET /api/claims/mine): every claim this Keycloak subject
 * filed, newest first. Same visibility wall as the single-claim status endpoint — the
 * row shape is public facts only — but as a list, so claimants can find and reopen past
 * claims without remembering claim numbers.
 */
@Service
public class MyClaimsService {

    private static final RowMapper<MyClaimView> ROW = (rs, rowNum) -> new MyClaimView(
            rs.getString("claim_number"),
            rs.getString("status"),
            rs.getString("product_code"),
            rs.getObject("loss_date", java.time.LocalDate.class),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getString("decision"),
            "APPROVED".equals(rs.getString("decision"))
                    ? rs.getBigDecimal("indemnity_amount")
                    : null,
            "DENIED".equals(rs.getString("decision"))
                    ? rs.getString("decision_remarks")
                    : null);

    private final JdbcTemplate jdbcTemplate;

    public MyClaimsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MyClaimView> mine(String claimantSub) {
        return jdbcTemplate.query(
                """
                SELECT c.claim_number, c.status, p.product_code, c.loss_date, c.created_at,
                       c.decision, c.indemnity_amount, c.decision_remarks
                FROM claim c
                JOIN policy p ON p.id = c.policy_id
                WHERE c.claimant_sub = ?
                ORDER BY c.created_at DESC, c.id DESC
                """,
                ROW, claimantSub);
    }
}
