package com.claims.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.claims.api.PageRequest;
import com.claims.api.PageResult;

/**
 * JDBC repository for {@code email_outbox} (R2, V10). Plain JdbcTemplate — no JPA entity —
 * because the outbox is a queue table: the dispatcher claims due rows with an atomic
 * UPDATE…RETURNING, and the admin list joins claim numbers without loading entities.
 */
@Repository
public class EmailOutboxRepository {

    private static final RowMapper<OutboxRowView> ROW = (rs, rowNum) -> new OutboxRowView(
            rs.getLong("id"),
            rs.getLong("claim_id"),
            rs.getString("claim_number"),
            rs.getString("kind"),
            rs.getString("to_address"),
            rs.getString("subject"),
            rs.getString("status"),
            rs.getInt("attempts"),
            rs.getString("last_error"),
            rs.getObject("next_attempt_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("sent_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public EmailOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Writes one PENDING row. Called inside the business transaction that triggers the
     * mail (FNOL, assignment, closure) — the row commits or rolls back with it, so mail
     * is never lost and never sent for a change that did not happen.
     */
    public long enqueue(long claimId, String kind, String toAddress, String subject,
            String body) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO email_outbox (claim_id, kind, to_address, subject, body) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, claimId, kind, toAddress, subject, body);
        return id == null ? -1 : id;
    }

    /**
     * Atomically claims up to {@code batch} due PENDING rows (attempt lease): marks them
     * claimed so concurrent dispatcher runs cannot double-send, and returns their ids.
     * Implemented as one UPDATE…RETURNING — no row locks held across the SMTP send.
     */
    public List<Long> claimDue(int batch) {
        return jdbcTemplate.queryForList(
                """
                UPDATE email_outbox
                SET attempts = attempts + 1
                WHERE id IN (
                    SELECT id FROM email_outbox
                    WHERE status = 'PENDING' AND next_attempt_at <= now()
                    ORDER BY next_attempt_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id
                """,
                Long.class, batch);
    }

    public OutboxMail loadMail(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT id, claim_id, kind, to_address, subject, body, attempts "
                        + "FROM email_outbox WHERE id = ?",
                (rs, rowNum) -> new OutboxMail(
                        rs.getLong("id"),
                        rs.getLong("claim_id"),
                        rs.getString("kind"),
                        rs.getString("to_address"),
                        rs.getString("subject"),
                        rs.getString("body"),
                        rs.getInt("attempts")),
                id);
    }

    public void markSent(long id, Instant now) {
        jdbcTemplate.update(
                "UPDATE email_outbox SET status = 'SENT', sent_at = ?, last_error = NULL "
                        + "WHERE id = ?",
                Timestamp.from(now), id);
    }

    /**
     * Records a failed attempt: backs off exponentially and, past {@code maxAttempts},
     * parks the row FAILED with the error kept for the runbook triage query.
     */
    public void markAttemptFailed(long id, String error, Instant nextAttemptAt,
            boolean exhausted) {
        if (exhausted) {
            jdbcTemplate.update(
                    "UPDATE email_outbox SET status = 'FAILED', last_error = ?, "
                            + "next_attempt_at = ? WHERE id = ?",
                    error, Timestamp.from(nextAttemptAt), id);
        } else {
            jdbcTemplate.update(
                    "UPDATE email_outbox SET last_error = ?, next_attempt_at = ? WHERE id = ?",
                    error, Timestamp.from(nextAttemptAt), id);
        }
    }

    /** Supervisor retry: a FAILED row goes back to PENDING (attempts preserved). */
    public boolean retry(long id) {
        int updated = jdbcTemplate.update(
                "UPDATE email_outbox SET status = 'PENDING', last_error = NULL, "
                        + "next_attempt_at = now() WHERE id = ? AND status = 'FAILED'",
                id);
        return updated == 1;
    }

    public OutboxRowView findRow(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT o.id, o.claim_id, c.claim_number, o.kind, o.to_address, o.subject, "
                        + "o.status, o.attempts, o.last_error, o.next_attempt_at, o.created_at, "
                        + "o.sent_at FROM email_outbox o JOIN claim c ON c.id = o.claim_id "
                        + "WHERE o.id = ?",
                ROW, id);
    }

    public PageResult<OutboxRowView> adminList(PageRequest paging) {
        StringBuilder where = new StringBuilder();
        List<Object> filterArgs = new ArrayList<>();
        if (paging.hasStatus()) {
            where.append("WHERE o.status = ?");
            filterArgs.add(paging.status());
        }
        if (paging.hasQuery()) {
            where.append(where.length() == 0 ? "WHERE " : " AND ");
            where.append("(c.claim_number ILIKE ? ESCAPE '\\' "
                    + "OR o.to_address ILIKE ? ESCAPE '\\' "
                    + "OR o.subject ILIKE ? ESCAPE '\\')");
            String like = "%" + escapeLike(paging.q()) + "%";
            filterArgs.addAll(List.of(like, like, like));
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM email_outbox o JOIN claim c ON c.id = o.claim_id "
                        + where,
                Long.class, filterArgs.toArray());
        long totalElements = total == null ? 0 : total;

        List<Object> pageArgs = new ArrayList<>(filterArgs);
        pageArgs.add(paging.size());
        pageArgs.add(paging.offset());
        List<OutboxRowView> content = jdbcTemplate.query(
                "SELECT o.id, o.claim_id, c.claim_number, o.kind, o.to_address, o.subject, "
                        + "o.status, o.attempts, o.last_error, o.next_attempt_at, o.created_at, "
                        + "o.sent_at FROM email_outbox o JOIN claim c ON c.id = o.claim_id "
                        + where + " ORDER BY o.created_at DESC, o.id DESC LIMIT ? OFFSET ?",
                ROW, pageArgs.toArray());
        return PageResult.of(content, paging, totalElements);
    }

    static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** The delivery payload the dispatcher sends through the existing sender classes. */
    public record OutboxMail(long id, long claimId, String kind, String toAddress,
            String subject, String body, int attempts) {
    }
}
