package com.claims.notify;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claims.api.ClaimNotFoundException;
import com.claims.api.InvalidRequestException;

/**
 * V25 (V3 S10): the claimant's own notification center. Reads and writes are
 * scoped by the caller's subject throughout — another claimant's row id is a
 * 404, never a 403 (the response never reveals the row exists).
 */
@Service
public class NotificationService {

    private static final RowMapper<NotificationView> ROW = (rs, rowNum) -> new NotificationView(
            rs.getLong("id"),
            rs.getLong("claim_id"),
            rs.getString("claim_number"),
            rs.getString("event"),
            rs.getString("title"),
            rs.getString("body"),
            rs.getObject("read_at", OffsetDateTime.class) != null,
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public NotificationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Own notifications, newest first, with the unread count beside the page. */
    @Transactional(readOnly = true)
    public NotificationPage mine(String claimantSub, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? 25 : Math.min(size, 100);
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification WHERE claimant_sub = ?",
                Long.class, claimantSub);
        long totalElements = total == null ? 0 : total;
        int totalPages = totalElements == 0 ? 0
                : (int) Math.ceil((double) totalElements / safeSize);
        List<NotificationView> content = jdbcTemplate.query(
                "SELECT n.id, n.claim_id, c.claim_number, n.event, n.title, n.body, "
                        + "n.read_at, n.created_at FROM notification n "
                        + "JOIN claim c ON c.id = n.claim_id "
                        + "WHERE n.claimant_sub = ? "
                        + "ORDER BY n.created_at DESC, n.id DESC LIMIT ? OFFSET ?",
                ROW, claimantSub, safeSize, safePage * safeSize);
        Long unread = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification WHERE claimant_sub = ? "
                        + "AND read_at IS NULL",
                Long.class, claimantSub);
        return new NotificationPage(List.copyOf(content), safePage, safeSize,
                totalElements, totalPages, unread == null ? 0 : unread);
    }

    /** Marks one own notification read (idempotent); another's id is a 404. */
    @Transactional
    public NotificationView markRead(long id, String claimantSub) {
        NotificationView current = jdbcTemplate.query(
                "SELECT n.id, n.claim_id, c.claim_number, n.event, n.title, n.body, "
                        + "n.read_at, n.created_at FROM notification n "
                        + "JOIN claim c ON c.id = n.claim_id "
                        + "WHERE n.id = ? AND n.claimant_sub = ?",
                ROW, id, claimantSub).stream().findFirst()
                .orElseThrow(ClaimNotFoundException::new);
        jdbcTemplate.update(
                "UPDATE notification SET read_at = COALESCE(read_at, now()) WHERE id = ?",
                id);
        return new NotificationView(current.id(), current.claimId(),
                current.claimNumber(), current.event(), current.title(),
                current.body(), true, current.createdAt());
    }

    /** Own preference row (defaults when the claimant never saved one). */
    @Transactional(readOnly = true)
    public NotificationPreferencesView preferences(String claimantSub) {
        return jdbcTemplate.query(
                "SELECT email_events, inapp_events, sms_events, phone "
                        + "FROM notification_preference WHERE claimant_sub = ?",
                (rs, rowNum) -> new NotificationPreferencesView(
                        rs.getBoolean("email_events"), rs.getBoolean("inapp_events"),
                        rs.getBoolean("sms_events"), rs.getString("phone")),
                claimantSub).stream().findFirst()
                .orElseGet(() -> new NotificationPreferencesView(true, true, false,
                        null));
    }

    /** Upserts the caller's own preference row (own-sub only — no id in play). */
    @Transactional
    public NotificationPreferencesView savePreferences(String claimantSub,
            PreferenceInput input) {
        if (input == null) {
            throw new InvalidRequestException("Preferences are required.");
        }
        String phone = input.phone() == null || input.phone().isBlank() ? null
                : input.phone().trim();
        if (phone != null && phone.length() > 20) {
            throw new InvalidRequestException(
                    "The phone number may be at most 20 characters.");
        }
        boolean emailEvents = input.emailEvents() == null || input.emailEvents();
        boolean inappEvents = input.inappEvents() == null || input.inappEvents();
        boolean smsEvents = Boolean.TRUE.equals(input.smsEvents());
        jdbcTemplate.update(
                "INSERT INTO notification_preference "
                        + "(claimant_sub, email_events, inapp_events, sms_events, phone) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (claimant_sub) DO UPDATE SET "
                        + "email_events = EXCLUDED.email_events, "
                        + "inapp_events = EXCLUDED.inapp_events, "
                        + "sms_events = EXCLUDED.sms_events, "
                        + "phone = EXCLUDED.phone",
                claimantSub, emailEvents, inappEvents, smsEvents, phone);
        return new NotificationPreferencesView(emailEvents, inappEvents, smsEvents,
                phone);
    }

    /**
     * Paginated envelope plus the bell count. PageResult is fixed
     * ({content,page,size,totalElements,totalPages}) and shared — this record
     * extends it with {@code unread} instead of changing the shared shape.
     */
    public record NotificationPage(List<NotificationView> content, int page, int size,
            long totalElements, int totalPages, long unread) {
        public static NotificationPage empty(int page, int size) {
            return new NotificationPage(List.of(), page, size, 0, 0, 0);
        }
    }

    public record NotificationView(long id, long claimId, String claimNumber,
            String event, String title, String body, boolean read,
            OffsetDateTime createdAt) {
    }

    public record NotificationPreferencesView(boolean emailEvents, boolean inappEvents,
            boolean smsEvents, String phone) {
    }

    /** Null booleans mean "leave the default" (TRUE, TRUE, FALSE). */
    public record PreferenceInput(Boolean emailEvents, Boolean inappEvents,
            Boolean smsEvents, String phone) {
    }
}
