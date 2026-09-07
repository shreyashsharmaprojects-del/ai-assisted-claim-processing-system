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
    }

    @BeforeEach
    final void resetClaimTablesBetweenTests() {
        jdbcTemplate.execute(
                "TRUNCATE claim, attachment, internal_note, payment, audit_log, fnol_submission "
                        + "RESTART IDENTITY CASCADE");
    }
}
