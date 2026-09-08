package com.claims.claim;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.claims.api.PageRequest;
import com.claims.api.PageResult;

/**
 * The claimant's own history (GET /api/claims/mine): every claim this Keycloak subject
 * filed, newest first. Same visibility wall as the single-claim status endpoint — the
 * row shape is public facts only — but as a list, so claimants can find and reopen past
 * claims without remembering claim numbers.
 *
 * <p>R4: paginated ({@code page}/{@code size}, stable
 * {@code ORDER BY created_at DESC, id DESC}) with server-side search {@code q} over the
 * claim number, product code, loss location and loss description. The search is scoped
 * to the caller's own rows — the wall holds (a claimant {@code q} never returns
 * another claimant's claim).
 */
@Service
public class MyClaimsService {

    private static final String COLUMNS = """
            SELECT c.claim_number, c.status, p.product_code, c.loss_date, c.created_at,
                   c.decision, c.indemnity_amount, c.decision_remarks
            FROM claim c
            JOIN policy p ON p.id = c.policy_id
            """;

    private static final String SEARCH = """
            AND (c.claim_number ILIKE ? ESCAPE '\\'
                 OR p.product_code ILIKE ? ESCAPE '\\'
                 OR c.loss_location ILIKE ? ESCAPE '\\'
                 OR c.loss_description ILIKE ? ESCAPE '\\')
            """;

    private static final String ORDER = "ORDER BY c.created_at DESC, c.id DESC";

    private static final RowMapper<MyClaimView> ROW = (rs, rowNum) -> new MyClaimView(
            rs.getString("claim_number"),
            rs.getString("status"),
            rs.getString("product_code"),
            rs.getObject("loss_date", java.time.LocalDate.class),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getString("decision"),
            "APPROVED".equals(rs.getString("decision"))
                    || "PARTIALLY_APPROVED".equals(rs.getString("decision"))
                    ? rs.getBigDecimal("indemnity_amount")
                    : null,
            "DENIED".equals(rs.getString("decision"))
                    ? rs.getString("decision_remarks")
                    : null);

    private final JdbcTemplate jdbcTemplate;

    public MyClaimsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageResult<MyClaimView> mine(String claimantSub, PageRequest paging) {
        StringBuilder where = new StringBuilder("WHERE c.claimant_sub = ?");
        List<Object> filterArgs = new ArrayList<>(List.of(claimantSub));
        if (paging.hasStatus()) {
            where.append(" AND c.status = ?");
            filterArgs.add(paging.status());
        }
        if (paging.hasQuery()) {
            where.append(" ").append(SEARCH.strip());
            String like = "%" + escapeLike(paging.q()) + "%";
            filterArgs.addAll(List.of(like, like, like, like));
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) " + fromWhere(where), Long.class, filterArgs.toArray());
        long totalElements = total == null ? 0 : total;

        List<Object> pageArgs = new ArrayList<>(filterArgs);
        pageArgs.add(paging.size());
        pageArgs.add(paging.offset());
        List<MyClaimView> content = jdbcTemplate.query(
                COLUMNS + " " + where + " " + ORDER + " LIMIT ? OFFSET ?",
                ROW, pageArgs.toArray());
        return PageResult.of(content, paging, totalElements);
    }

    private static String fromWhere(StringBuilder where) {
        return """
                FROM claim c
                JOIN policy p ON p.id = c.policy_id
                """ + " " + where;
    }

    /** Escapes {@code %}, {@code _} and the escape char so {@code q} is a literal match. */
    static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
