package com.claims.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * R3 metrics/alerting baseline: the sale-diligence numbers as JSON instead of log-grep.
 * Dependency-free by design (the Prometheus registry jar is not in the offline build
 * cache): plain {@link AtomicLong} counters incremented on the write paths (FNOL,
 * decisions, escalations) plus live JDBC gauges read at scrape time (queue depth by
 * status). Served supervisor-scoped at {@code GET /api/metrics} (see
 * {@link MetricsController}). No per-claim PII in labels — claim numbers never appear.
 *
 * <p>Outbox gauges read the V10 {@code email_outbox} table live (PENDING due-or-not for
 * depth, FAILED for the dead-letter count). If the table is absent (a database migrated
 * only to V9), both report 0 so the scrape never breaks a half-migrated database.
 */
@Component
public class ClaimsMetrics {

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong fnolTotal = new AtomicLong();
    private final ConcurrentMap<String, AtomicLong> fnolRejected = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> decisions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> escalations = new ConcurrentHashMap<>();

    public ClaimsMetrics(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** An FNOL filing persisted (called after the claim row is saved). */
    public void fnolAccepted() {
        fnolTotal.incrementAndGet();
    }

    /**
     * An FNOL rejected before persistence. Reason is a fixed vocabulary
     * ({@code validation}, {@code policy_mismatch}, {@code rate_limited},
     * {@code unroutable}) — never free text, never PII.
     */
    public void fnolRejected(String reason) {
        fnolRejected.computeIfAbsent(reason, r -> new AtomicLong()).incrementAndGet();
    }

    /** A claim closed: outcome is APPROVED or DENIED. */
    public void decision(String outcome) {
        decisions.computeIfAbsent(outcome, o -> new AtomicLong()).incrementAndGet();
    }

    /** A claim escalated above the acting authority: target is L2 or SUPERVISOR. */
    public void escalation(String target) {
        escalations.computeIfAbsent(target, t -> new AtomicLong()).incrementAndGet();
    }

    /**
     * The scrape snapshot: counters plus live gauges. A {@link java.util.LinkedHashMap}
     * keeps the wire order stable (fnol first) so scrapers and log-tail parsers see a
     * deterministic shape — {@code Map.of} makes no ordering promise.
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("claims_fnol_total", fnolTotal.get());
        snapshot.put("claims_fnol_rejected_total", copyOf(fnolRejected));
        snapshot.put("claims_decisions_total", copyOf(decisions));
        snapshot.put("claims_escalations_total", copyOf(escalations));
        snapshot.put("claims_queue_depth", Map.of(
                "UNASSIGNED", queueDepth("UNASSIGNED"),
                "UNDER_REVIEW", queueDepth("UNDER_REVIEW"),
                "ESCALATED_SUPERVISOR", queueDepth("ESCALATED_SUPERVISOR")));
        snapshot.put("claims_outbox_pending", outboxCount("PENDING"));
        snapshot.put("claims_outbox_failed_total", outboxCount("FAILED"));
        return snapshot;
    }

    private long outboxCount(String status) {
        try {
            Long value = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM email_outbox WHERE status = ?", Long.class, status);
            return value == null ? 0 : value;
        } catch (Exception ex) {
            // Pre-V10 database: no outbox table yet — report 0, never break the scrape.
            return 0;
        }
    }

    private long queueDepth(String status) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM claim WHERE status = ?", Long.class, status);
        return value == null ? 0 : value;
    }

    private static Map<String, Long> copyOf(ConcurrentMap<String, AtomicLong> counters) {
        Map<String, Long> copy = new java.util.HashMap<>();
        counters.forEach((key, value) -> copy.put(key, value.get()));
        return copy;
    }
}
