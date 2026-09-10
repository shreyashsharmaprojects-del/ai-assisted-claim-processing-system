package com.claims.notify;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V25 (V3 S10): the in-app mirror of {@code EmailOutboxWriter} — writes one
 * INAPP {@code notification} row in the caller's transaction (commits or rolls
 * back with the business change, exactly like the outbox spine). Honours
 * {@code notification_preference.inapp_events}: no preference row means the
 * default TRUE (claimants get movement pings unless they opt out).
 *
 * <p>Events: FNOL_RECEIVED | ASSIGNED | NEED_INFO_SENT | NEED_INFO_RESPONSE |
 * DECIDED | REOPENED | REFERRED.
 */
@Component
public class NotificationWriter {

    private final JdbcTemplate jdbcTemplate;

    public NotificationWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** True when the subject wants in-app events (default TRUE, no row yet). */
    public boolean inappEnabled(String claimantSub) {
        // queryForObject would throw on the common no-row case — read a list.
        return jdbcTemplate.query(
                "SELECT inapp_events FROM notification_preference WHERE claimant_sub = ?",
                (rs, rowNum) -> rs.getBoolean("inapp_events"), claimantSub).stream()
                .findFirst().orElse(Boolean.TRUE);
    }

    /**
     * Writes one INAPP row unless the claimant opted out (opt-out writes
     * nothing — no row, no error). Returns the row id, or -1 on opt-out.
     */
    public long write(long claimId, String claimantSub, String event, String title,
            String body) {
        if (!inappEnabled(claimantSub)) {
            return -1;
        }
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO notification (claim_id, claimant_sub, channel, event, title, body) "
                        + "VALUES (?, ?, 'INAPP', ?, ?, ?) RETURNING id",
                Long.class, claimId, claimantSub, event, title, body);
        return id == null ? -1 : id;
    }
}
