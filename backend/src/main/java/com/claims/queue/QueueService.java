package com.claims.queue;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.claims.api.PageRequest;
import com.claims.api.PageResult;

/**
 * Reads for the adjuster queue (slice 2) + the supervisor escalation queue (slice 5).
 * Open claims (anything not CLOSED) are returned oldest-first by {@code created_at}
 * (then id) so the longest-waiting work surfaces first. Team view = every open claim;
 * adjuster view = only the caller's own assignments.
 *
 * <p>R4: all reads are paginated ({@code page}/{@code size}, stable
 * {@code ORDER BY created_at, id}) with server-side search {@code q} over the
 * claim number, policy number, loss location and loss description (never internal
 * notes) and an exact {@code status} filter for queue-relevant values.
 */
@Service
public class QueueService {

    private static final String COLUMNS = """
            SELECT c.claim_number, c.status, c.level, p.policy_number,
                   c.loss_date, c.loss_location, c.loss_description, c.created_at,
                   a.display_name AS assigned_to
            FROM claim c
            JOIN policy p ON p.id = c.policy_id
            LEFT JOIN app_user a ON a.id = c.assigned_adjuster_id
            """;

    private static final String SEARCH = """
            AND (c.claim_number ILIKE ? ESCAPE '\\'
                 OR p.policy_number ILIKE ? ESCAPE '\\'
                 OR c.loss_location ILIKE ? ESCAPE '\\'
                 OR c.loss_description ILIKE ? ESCAPE '\\')
            """;

    private static final String ORDER = "ORDER BY c.created_at, c.id";

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
    public PageResult<QueueClaimView> teamQueue(PageRequest paging) {
        return query("WHERE c.status <> 'CLOSED'", List.of(), paging);
    }

    /** The open claims assigned to one adjuster — that adjuster's own queue. */
    public PageResult<QueueClaimView> adjusterQueue(Long adjusterId, PageRequest paging) {
        return query("WHERE c.status <> 'CLOSED' AND c.assigned_adjuster_id = ?",
                List.of(adjusterId), paging);
    }

    /**
     * The supervisor's escalation queue (slice 5): claims in
     * {@code ESCALATED_SUPERVISOR}, oldest first. The same row shape as the team queue —
     * the assignee is always null here (no adjuster holds an escalated claim).
     */
    public PageResult<QueueClaimView> supervisorEscalationQueue(PageRequest paging) {
        return query("WHERE c.status = 'ESCALATED_SUPERVISOR'", List.of(), paging);
    }

    private PageResult<QueueClaimView> query(String baseWhere, List<Object> baseArgs,
            PageRequest paging) {
        StringBuilder where = new StringBuilder(baseWhere);
        List<Object> filterArgs = new ArrayList<>(baseArgs);
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
        List<QueueClaimView> content = jdbcTemplate.query(
                COLUMNS + " " + where + " " + ORDER + " LIMIT ? OFFSET ?",
                ROW, pageArgs.toArray());
        return PageResult.of(content, paging, totalElements);
    }

    private static String fromWhere(StringBuilder where) {
        return """
                FROM claim c
                JOIN policy p ON p.id = c.policy_id
                LEFT JOIN app_user a ON a.id = c.assigned_adjuster_id
                """ + " " + where;
    }

    /** Escapes {@code %}, {@code _} and the escape char so {@code q} is a literal match. */
    static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
