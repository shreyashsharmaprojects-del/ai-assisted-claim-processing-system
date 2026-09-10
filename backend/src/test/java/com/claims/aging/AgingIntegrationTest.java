package com.claims.aging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.claims.TestcontainersConfiguration;
import com.claims.claim.ClaimDecisionInput;
import com.claims.claim.ClaimDecisionService;
import com.claims.claim.ClaimService;
import com.claims.claim.FnolInput;
import com.claims.claim.FnolResult;
import com.claims.staff.AppUserRepository;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

/**
 * The aging job (Flow 6) at the integration layer: claims are backdated through
 * {@code claim.created_at} (the FNOL anchor, read via JDBC — it is not entity-mapped) and
 * {@link AgingService#ageClaims(Instant)} is driven with a FIXED instant, so the 3-day and
 * 5-day rungs are deterministic without waiting on the real scheduler (the plan's named
 * risk: "scheduled job determinism in tests — inject the clock"). Claims are created
 * through the real {@link ClaimService} and escalated/closed through the real
 * {@link ClaimDecisionService}, so every fixture is a state a real flow produces.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgingIntegrationTest extends ClaimTableResettingTest {

    // A fixed "now" for every run: transitions are computed against this instant, never
    // the wall clock.
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

    // Seeded staff (V4): adjuster.one/two are L1, adjuster.three is L2.
    private static final String SUB_L1_ONE = "10000000-0000-0000-0000-000000000001";
    private static final String SUB_L2 = "10000000-0000-0000-0000-000000000003";

    @Autowired
    private ClaimService claimService;
    @Autowired
    private ClaimDecisionService decisionService;
    @Autowired
    private AgingService agingService;
    @Autowired
    private AppUserRepository appUsers;

    // --- the 3-day rung ----------------------------------------------------------

    @Test
    void anL1ClaimUndecidedAtThreeDaysIsReassignedToTheL2Adjuster() {
        String claimNumber = fileHomeFnol("sub-aging-l1");
        Long l1Assignee = assigneeOf(claimNumber);
        assertNotNull(l1Assignee, "the fresh claim is assigned to an L1 adjuster");

        backdate(claimNumber, NOW.minus(3, ChronoUnit.DAYS));
        assertEquals(1, agingService.ageClaims(NOW));

        Long claimId = idOf(claimNumber);
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        assertEquals("L2", levelOf(claimNumber), "the claim's routing level moves to L2");
        assertEquals(l2AdjusterId(), assigneeOf(claimNumber),
                "the claim is re-assigned to the least-loaded L2 adjuster");
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT assigned_at FROM claim WHERE id = ?", Timestamp.class, claimId));
        // One system escalation audit row (actor NULL — aging is not a person), and the
        // FNOL's CLAIM_ASSIGNED row is not duplicated by the re-assignment.
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_ESCALATED' "
                + "AND entity_id = ? AND actor_sub IS NULL AND rationale LIKE ?",
                claimId, "%Aged at least 3 days%"));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_ASSIGNED' "
                + "AND entity_id = ?", claimId));
    }

    @Test
    void agingIsIdempotentUnderASecondRun() {
        String claimNumber = fileHomeFnol("sub-aging-idem");
        backdate(claimNumber, NOW.minus(3, ChronoUnit.DAYS));
        assertEquals(1, agingService.ageClaims(NOW));

        // A second pass must not re-touch the claim now that it is an L2-tier claim.
        assertEquals(0, agingService.ageClaims(NOW));
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        assertEquals("L2", levelOf(claimNumber));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_ESCALATED' "
                + "AND entity_id = ?", idOf(claimNumber)));
    }

    @Test
    void theThreeDayRungFallsBackToTheSupervisorWhenNoL2AdjusterIsProvisioned() {
        // Mirror of the slice-4 provisioning-gap rule: an L2 push with no L2 adjuster must
        // land the claim on the supervisor, never leave it with an unanswered rung.
        String claimNumber = fileHomeFnol("sub-aging-gap");
        backdate(claimNumber, NOW.minus(3, ChronoUnit.DAYS));
        appUsers.findAll().stream().filter(user -> "L2".equals(user.getLevel())).toList()
                .forEach(appUsers::delete);
        try {
            assertEquals(1, agingService.ageClaims(NOW));
            assertEquals("ESCALATED_SUPERVISOR", statusOf(claimNumber));
            assertNull(assigneeOf(claimNumber), "no adjuster holds the fallback escalation");
            assertEquals("L1", levelOf(claimNumber), "the failed L2 push reverts the level");
            assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_ESCALATED' "
                    + "AND entity_id = ? AND actor_sub IS NULL", idOf(claimNumber)));
        } finally {
            jdbcTemplate.update("INSERT INTO app_user (keycloak_sub, display_name, email, level) "
                    + "VALUES (?, ?, ?, ?)", SUB_L2, "Ines Kowalski", "ines.kowalski@claims.test", "L2");
            jdbcTemplate.update("INSERT INTO app_user (keycloak_sub, display_name, email, level) "
                    + "VALUES (?, ?, ?, ?)", "10000000-0000-0000-0000-000000000006",
                    "Rahul Singh", "rahul.singh@claims.test", "L2");
        }
    }

    // --- the 5-day rung ----------------------------------------------------------

    @Test
    void anL1ClaimUndecidedAtFiveDaysIsEscalatedToTheSupervisor() {
        String claimNumber = fileHomeFnol("sub-aging-l1-5");
        backdate(claimNumber, NOW.minus(5, ChronoUnit.DAYS));
        assertEquals(1, agingService.ageClaims(NOW));

        Long claimId = idOf(claimNumber);
        assertEquals("ESCALATED_SUPERVISOR", statusOf(claimNumber));
        assertNull(assigneeOf(claimNumber), "an ESCALATED_SUPERVISOR claim has no adjuster");
        assertEquals("L1", levelOf(claimNumber), "the supervisor rung does not re-route the level");
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_ESCALATED' "
                + "AND entity_id = ? AND actor_sub IS NULL AND rationale LIKE ?",
                claimId, "%Aged at least 5 days%"));
    }

    @Test
    void aClockJumpFromDayTwoToDaySixSkipsTheL2Rung() {
        // One run after the job was missed: the claim must land at the top of the ladder
        // (supervisor), not stop at L2 for a later run.
        String claimNumber = fileHomeFnol("sub-aging-jump");
        backdate(claimNumber, NOW.minus(6, ChronoUnit.DAYS));
        assertEquals(1, agingService.ageClaims(NOW));
        assertEquals("ESCALATED_SUPERVISOR", statusOf(claimNumber));
        assertEquals("L1", levelOf(claimNumber), "the claim never stopped at the L2 tier");
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_ESCALATED' "
                + "AND entity_id = ?", idOf(claimNumber)));
    }

    @Test
    void anL2ClaimStaysWithItsL2AdjusterAtThreeDaysAndEscalatesAtFive() {
        String claimNumber = fileAutoFnol("sub-aging-auto");
        assertEquals("L2", levelOf(claimNumber));
        assertEquals(l2AdjusterId(), assigneeOf(claimNumber));

        // At 3 days an AUTO claim already sits at the L2 tier: the rung is satisfied.
        backdate(claimNumber, NOW.minus(3, ChronoUnit.DAYS));
        assertEquals(0, agingService.ageClaims(NOW));
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        assertEquals(l2AdjusterId(), assigneeOf(claimNumber));
        assertEquals(0, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_ESCALATED' "
                + "AND entity_id = ?", idOf(claimNumber)));

        // Still undecided at 5 days, it goes to the supervisor.
        backdate(claimNumber, NOW.minus(5, ChronoUnit.DAYS));
        assertEquals(1, agingService.ageClaims(NOW));
        assertEquals("ESCALATED_SUPERVISOR", statusOf(claimNumber));
        assertNull(assigneeOf(claimNumber));
    }

    // --- immunity ----------------------------------------------------------------

    @Test
    void closedClaimsAreNeverAged() {
        String claimNumber = fileHomeFnol("sub-aging-closed");
        decisionService.decide(claimNumber, SUB_L1_ONE,
                new ClaimDecisionInput("APPROVED", new BigDecimal("1500.00"),
                        "within my authority here", versionOf(claimNumber)));
        assertEquals("CLOSED", statusOf(claimNumber));

        backdate(claimNumber, NOW.minus(10, ChronoUnit.DAYS));
        assertEquals(0, agingService.ageClaims(NOW));
        assertEquals("CLOSED", statusOf(claimNumber));
        assertEquals(1, count("SELECT count(*) FROM payment WHERE claim_id = ?", idOf(claimNumber)));
        assertEquals(0, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_ESCALATED' "
                + "AND entity_id = ?", idOf(claimNumber)));
    }

    @Test
    void supervisorEscalatedClaimsAreNeverReAged() {
        String claimNumber = fileHomeFnol("sub-aging-esc");
        // V2-1: POL-10001 is HLTH-PLUS (L3 1000000) — 1200000 clears every adjuster
        // limit, so the claim goes straight to the supervisor.
        decisionService.decide(claimNumber, SUB_L1_ONE,
                new ClaimDecisionInput("APPROVED", new BigDecimal("1200000.00"),
                        "above the L3 limit by far", versionOf(claimNumber)));
        assertEquals("ESCALATED_SUPERVISOR", statusOf(claimNumber));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_ESCALATED' "
                + "AND entity_id = ?", idOf(claimNumber)));

        backdate(claimNumber, NOW.minus(10, ChronoUnit.DAYS));
        assertEquals(0, agingService.ageClaims(NOW));
        assertEquals("ESCALATED_SUPERVISOR", statusOf(claimNumber));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_ESCALATED' "
                + "AND entity_id = ?", idOf(claimNumber)),
                "a claim already on the supervisor is not aged again");
    }

    // --- helpers ------------------------------------------------------------------

    private String fileHomeFnol(String claimantSub) {
        return fileFnol(Map.of(
                "policyNumber", "POL-10001",
                "holderName", "Ada Lovelace",
                "holderEmail", "ada.lovelace@example.test"), claimantSub);
    }

    private String fileAutoFnol(String claimantSub) {
        return fileFnol(Map.of(
                "policyNumber", "POL-20002",
                "holderName", "Grace Hopper",
                "holderEmail", "grace.hopper@example.test"), claimantSub);
    }

    private String fileFnol(Map<String, String> fields, String claimantSub) {
        FnolInput input = new FnolInput(fields.get("policyNumber"), fields.get("holderName"),
                fields.get("holderEmail"), "2026-09-01", "London", "Kitchen flooded",
                "Happened overnight.", claimantSub, List.of());
        FnolResult result = claimService.fileFnol(input);
        return result.view().claimNumber();
    }

    private void backdate(String claimNumber, Instant created) {
        jdbcTemplate.update("UPDATE claim SET created_at = ? WHERE claim_number = ?",
                Timestamp.from(created), claimNumber);
    }

    private Long idOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM claim WHERE claim_number = ?", Long.class, claimNumber);
    }

    /** V21 (V3 S5): the claim version a writer must echo back as expectedVersion. */
    private Long versionOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM claim WHERE claim_number = ?", Long.class, claimNumber);
    }

    private String statusOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM claim WHERE claim_number = ?", String.class, claimNumber);
    }

    private String levelOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT level FROM claim WHERE claim_number = ?", String.class, claimNumber);
    }

    private Long assigneeOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT assigned_adjuster_id FROM claim WHERE claim_number = ?", Long.class,
                claimNumber);
    }

    private Long l2AdjusterId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE keycloak_sub = ?", Long.class, SUB_L2);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
