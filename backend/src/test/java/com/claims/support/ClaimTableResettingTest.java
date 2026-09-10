package com.claims.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared reset contract for integration classes that WRITE claims. Every test starts from
 * the V4 seed state — no claims, attachments, or audit rows, and every adjuster back at
 * zero open claims — no matter which class ran before it.
 *
 * <p>All classes sharing the single Testcontainers database must extend this: a test that
 * can observe another class's claims (or its own earlier claims) is asserting on shared
 * mutable state. {@code policy} and {@code app_user} are seed tables and are deliberately
 * never truncated here; a test that needs to remove an adjuster restores it itself
 * (see AssignmentQueueIntegrationTest's provisioning-gap test).
 */
public abstract class ClaimTableResettingTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * The aging cron must never fire inside a test suite: claim-writing classes include the
     * one that backdates claims (AgingIntegrationTest), where a live scheduled run would be
     * nondeterministic. Tests drive {@code AgingService.ageClaims(Instant)} with fixed
     * instants instead (see the slice-5 plan risk). Inherited by every subclass.
     */
    @DynamicPropertySource
    static void agingSchedulerOffInTests(DynamicPropertyRegistry registry) {
        registry.add("claims.aging.enabled", () -> "false");
        // The outbox dispatcher is driven directly in tests (EmailOutboxDispatcher.dispatch())
        // with fixed data instead — a scheduler firing mid-suite could never be
        // deterministic. Controllers still flush it after commit, so delivery assertions
        // observe SENT rows without it.
        registry.add("claims.outbox.enabled", () -> "false");
    }

    /**
     * V24 adds {@code privacy_request}, which has no FK to {@code claim} — it is
     * listed here explicitly so each test starts with a clean erasure/export trail.
     * Erasure tests redact seed {@code policy} PII (holder name/email); the seed
     * holder identities are restored here so redaction never leaks into other
     * classes sharing the single Testcontainers database.
     */
    @BeforeEach
    final void resetClaimTablesBetweenTests() {
        jdbcTemplate.execute(
                "TRUNCATE claim, attachment, internal_note, payment, audit_log, fnol_submission, "
                        + "email_outbox, verification, privacy_request RESTART IDENTITY CASCADE");
        jdbcTemplate.update("UPDATE policy SET holder_name = CASE policy_number "
                + "WHEN 'POL-10001' THEN 'Ada Lovelace' "
                + "WHEN 'POL-20002' THEN 'Grace Hopper' "
                + "WHEN 'POL-30001' THEN 'Ravi Menon' "
                + "WHEN 'POL-30002' THEN 'Fatima Khan' "
                + "WHEN 'POL-30003' THEN 'David D''Souza' "
                + "WHEN 'POL-30004' THEN 'Lakshmi Iyer' "
                + "WHEN 'POL-30005' THEN 'Arjun Nair' "
                + "WHEN 'POL-30006' THEN 'Kavya Reddy' "
                + "WHEN 'POL-30007' THEN 'Retired Holder' "
                + "WHEN 'POL-30008' THEN 'Expired Holder' "
                + "WHEN 'POL-30009' THEN 'Orphan Holder' "
                + "WHEN 'POL-30010' THEN 'Vikram Rao' "
                + "WHEN 'POL-DEMO-01' THEN 'Eleanor Vance' "
                + "WHEN 'POL-DEMO-02' THEN 'Theodore Marsh' "
                + "WHEN 'POL-DEMO-03' THEN 'Priya Nair' "
                + "WHEN 'POL-DEMO-04' THEN 'Samuel Okafor' "
                + "WHEN 'POL-DEMO-05' THEN 'Ingrid Halvors' "
                + "WHEN 'POL-DEMO-06' THEN 'Tomas Reyes' "
                + "WHEN 'POL-DEMO-07' THEN 'Anaya Desai' "
                + "WHEN 'POL-DEMO-08' THEN 'Kabir Rao' "
                + "ELSE holder_name END, "
                + "holder_email = CASE policy_number "
                + "WHEN 'POL-10001' THEN 'ada.lovelace@example.test' "
                + "WHEN 'POL-20002' THEN 'grace.hopper@example.test' "
                + "WHEN 'POL-30001' THEN 'ravi.menon@example.test' "
                + "WHEN 'POL-30002' THEN 'fatima.khan@example.test' "
                + "WHEN 'POL-30003' THEN 'david.dsouza@example.test' "
                + "WHEN 'POL-30004' THEN 'lakshmi.iyer@example.test' "
                + "WHEN 'POL-30005' THEN 'arjun.nair@example.test' "
                + "WHEN 'POL-30006' THEN 'kavya.reddy@example.test' "
                + "WHEN 'POL-30007' THEN 'retired.holder@example.test' "
                + "WHEN 'POL-30008' THEN 'expired.holder@example.test' "
                + "WHEN 'POL-30009' THEN 'orphan.holder@example.test' "
                + "WHEN 'POL-30010' THEN 'vikram.rao@example.test' "
                + "WHEN 'POL-DEMO-01' THEN 'demo.eleanor@example.test' "
                + "WHEN 'POL-DEMO-02' THEN 'demo.theodore@example.test' "
                + "WHEN 'POL-DEMO-03' THEN 'demo.priya@example.test' "
                + "WHEN 'POL-DEMO-04' THEN 'demo.samuel@example.test' "
                + "WHEN 'POL-DEMO-05' THEN 'demo.ingrid@example.test' "
                + "WHEN 'POL-DEMO-06' THEN 'demo.tomas@example.test' "
                + "WHEN 'POL-DEMO-07' THEN 'demo.anaya@example.test' "
                + "WHEN 'POL-DEMO-08' THEN 'demo.kabir@example.test' "
                + "ELSE holder_email END");
    }
}
