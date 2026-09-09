package com.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.claims.TestcontainersConfiguration;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

/**
 * Slice-4 acceptance: the authority gate at the decision endpoint. Real HTTP against real
 * Postgres (claim tables reset per test, so the first L1 FNOL deterministically lands on
 * adjuster.one) with a real SMTP capture for the decision email. Success (approve/deny
 * closes with one payment + DECISION audit), above-level blocked + escalated (to L2 and
 * straight to the supervisor), validation, and the auth/404 matrix.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClaimDecisionIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----ClaimDecisionTestBoundary9";

    // Seeded staff (V4/V13): adjuster.one/two/four are L1, adjuster.three/five are L2.
    private static final String SUB_L1_ONE = "10000000-0000-0000-0000-000000000001";
    private static final String SUB_L2 = "10000000-0000-0000-0000-000000000003";
    private static final String SUB_L2_TWO = "10000000-0000-0000-0000-000000000006";
    private static final String CLAIMANT = "sub-claimant-decision";

    // Seeded authority thresholds: POL-10001 is HLTH-PLUS (L1 100000 / L2 400000 /
    // L3 1000000); POL-20002 is AUTO (L1 2500 / L2 10000 / L3 25000). The mid amount
    // must clear HLTH-PLUS L1 yet stay inside HLTH-PLUS L2 (200000); the AUTO test
    // needs its own amount inside AUTO L2 (5000); the top amount must clear
    // HLTH-PLUS L3 so it reaches the supervisor (1200000).
    private static final String WITHIN_L1 = "1500.00";
    private static final String ABOVE_L1_WITHIN_L2 = "200000.00";
    private static final String AUTO_ABOVE_L1_WITHIN_L2 = "5000.00";
    private static final String ABOVE_L2 = "1200000.00";

    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
            DockerImageName.parse("axllent/mailpit:v1.22"))
            .withExposedPorts(1025, 8025);

    private static final Path UPLOADS = createUploadsDir();

    static {
        MAILPIT.start();
    }

    private static Path createUploadsDir() {
        try {
            return Files.createTempDirectory("claims-decision-test-uploads");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @org.springframework.test.context.DynamicPropertySource
    static void datasourceProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        registry.add("claims.uploads.dir", () -> UPLOADS.toString());
    }

    @Autowired
    private Environment environment;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private ClaimDecisionService decisionService;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clearMailbox() throws Exception {
        http.send(HttpRequest.newBuilder(URI.create(mailpitUrl() + "/api/v1/messages"))
                .DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    // --- within-level approval closes with one payment + audit + email ---------

    @Test
    void withinLevelApprovalRecordsOnePaymentAndCloses() throws Exception {
        String claimNumber = fileHomeFnol();

        HttpResponse<String> response = postJson("/api/claims/" + claimNumber + "/decision",
                holderBearer(claimNumber), "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                        + WITHIN_L1
                        + ",\"rationale\":\"Quotes verified; within my authority.\""
                        + ",\"expectedVersion\":" + versionOf(claimNumber) + "}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"decision\":\"APPROVED\""), response.body());

        Long claimId = idOf(claimNumber);
        assertEquals("CLOSED", jdbcTemplate.queryForObject(
                "SELECT status FROM claim WHERE id = ?", String.class, claimId));
        assertEquals("APPROVED", jdbcTemplate.queryForObject(
                "SELECT decision FROM claim WHERE id = ?", String.class, claimId));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT closed_at FROM claim WHERE id = ?", java.sql.Timestamp.class, claimId));
        // Exactly one payment, amount == indemnity, authorized by the deciding adjuster.
        assertEquals(1, count("SELECT count(*) FROM payment WHERE claim_id = ?", claimId));
        assertEquals(0, new java.math.BigDecimal(WITHIN_L1).compareTo(
                jdbcTemplate.queryForObject(
                        "SELECT amount FROM payment WHERE claim_id = ?", java.math.BigDecimal.class,
                        claimId)));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT authorized_by_id FROM payment WHERE claim_id = ?", Long.class, claimId),
                "the payment is authorized by the deciding adjuster (adjuster.one, id 1)");
        // DECISION audit with actor + rationale.
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'DECISION' "
                + "AND entity_id = ? AND actor_sub = ? AND rationale IS NOT NULL",
                claimId, SUB_L1_ONE));

        // The claimant's view of the closed claim (slice 6): decision + the approved
        // amount ride the view — and only those — while the wall still holds.
        String claimantView = get("/api/claims/" + claimNumber, claimantBearer()).body();
        assertTrue(claimantView.contains("\"status\":\"CLOSED\""), claimantView);
        assertTrue(claimantView.contains("\"decision\":\"APPROVED\""), claimantView);
        assertTrue(claimantView.contains("\"indemnityAmount\":" + WITHIN_L1),
                "the approved amount reaches the claimant on an APPROVED closure: "
                        + claimantView);
        assertFalse(claimantView.contains("decisionRemarks"),
                "an approval carries no remarks on the claimant view: " + claimantView);
        assertTrue(claimantView.contains("FNOL received"), claimantView);
        assertTrue(claimantView.contains("Under review"),
                "the closed view keeps the shared pre-decision steps: " + claimantView);
        assertFalse(claimantView.contains("reserveAmount"), claimantView);
        assertFalse(claimantView.contains("\"notes\""), claimantView);
        assertFalse(claimantView.contains("\"assignedTo\""), claimantView);
        assertFalse(claimantView.contains("\"coverage\""), claimantView);

        // Decision email to the verified holder address (distinct subject from the
        // FNOL/assignment emails, which also mention the claim number), carrying the
        // approved amount in the body.
        String inbox = mailpitFetch();
        assertTrue(inbox.contains("Decision on claim " + claimNumber),
                "a decision email should exist: " + inbox);
        assertTrue(inbox.contains("ada.lovelace@example.test"), inbox);
        assertTrue(inbox.contains("approved"), inbox);
        assertTrue(inbox.contains("We will pay"), inbox);
        assertTrue(inbox.contains(WITHIN_L1), "the approval email states the amount: " + inbox);
    }

    // --- denial always closes with remarks -------------------------------------

    @Test
    void denialClosesWithRemarksAndNoPayment() throws Exception {
        String claimNumber = fileHomeFnol();

        HttpResponse<String> response = postJson("/api/claims/" + claimNumber + "/decision",
                holderBearer(claimNumber), "{\"decision\":\"DENIED\",\"rationale\":"
                        + "\"Coverage excludes the reported damage.\""
                        + ",\"expectedVersion\":" + versionOf(claimNumber) + "}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"decision\":\"DENIED\""), response.body());

        Long claimId = idOf(claimNumber);
        assertEquals("CLOSED", jdbcTemplate.queryForObject(
                "SELECT status FROM claim WHERE id = ?", String.class, claimId));
        assertEquals("Coverage excludes the reported damage.", jdbcTemplate.queryForObject(
                "SELECT decision_remarks FROM claim WHERE id = ?", String.class, claimId));
        assertEquals(0, count("SELECT count(*) FROM payment WHERE claim_id = ?", claimId));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'DECISION' "
                + "AND entity_id = ? AND rationale = ?", claimId,
                "Coverage excludes the reported damage."));

        // The claimant's view of the denied claim (slice 6): the denial + remarks verbatim,
        // never an indemnity amount, wall intact.
        String claimantView = get("/api/claims/" + claimNumber, claimantBearer()).body();
        assertTrue(claimantView.contains("\"status\":\"CLOSED\""), claimantView);
        assertTrue(claimantView.contains("\"decision\":\"DENIED\""), claimantView);
        assertTrue(claimantView.contains("\"decisionRemarks\":\"Coverage excludes the "
                + "reported damage.\""), "the rationale is the claimant-visible remarks: "
                + claimantView);
        assertFalse(claimantView.contains("indemnityAmount"),
                "a denied claim never carries an amount on the claimant view: " + claimantView);
        assertFalse(claimantView.contains("reserveAmount"), claimantView);
        assertFalse(claimantView.contains("\"notes\""), claimantView);

        // The denial email carries the remarks in the body.
        String inbox = mailpitFetch();
        assertTrue(inbox.contains("not been approved"), "denial email is sent: " + inbox);
        assertTrue(inbox.contains("Coverage excludes the reported damage."),
                "the denial email states the remarks: " + inbox);
    }

    // --- above-level approval is blocked and escalated -------------------------

    @Test
    void aboveLevelApprovalIsBlockedAndReassignedToL2() throws Exception {
        String claimNumber = fileHomeFnol();

        HttpResponse<String> response = postJson("/api/claims/" + claimNumber + "/decision",
                holderBearer(claimNumber), "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                        + ABOVE_L1_WITHIN_L2
                        + ",\"rationale\":\"Large water damage claim.\""
                        + ",\"expectedVersion\":" + versionOf(claimNumber) + "}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"escalatedTo\":\"L2\""), response.body());
        assertFalse(response.body().contains("\"decision\":\"APPROVED\""),
                "an above-level approval is never granted: " + response.body());

        Long claimId = idOf(claimNumber);
        assertEquals("UNDER_REVIEW", jdbcTemplate.queryForObject(
                "SELECT status FROM claim WHERE id = ?", String.class, claimId));
        assertEquals("L2", jdbcTemplate.queryForObject(
                "SELECT level FROM claim WHERE id = ?", String.class, claimId));
        // Reassigned to a least-loaded L2 adjuster — never the original actor.
        Long newAssignee = jdbcTemplate.queryForObject(
                "SELECT assigned_adjuster_id FROM claim WHERE id = ?", Long.class, claimId);
        assertTrue(SUB_L2.equals(keycloakSubOf(newAssignee))
                || SUB_L2_TWO.equals(keycloakSubOf(newAssignee)),
                "escalation lands on an L2 adjuster, never the actor");
        assertEquals(0, count("SELECT count(*) FROM payment WHERE claim_id = ?", claimId));
        // Escalation audit attributed to the adjuster whose attempt triggered it.
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_ESCALATED' "
                + "AND entity_id = ? AND actor_sub = ? AND rationale IS NOT NULL",
                claimId, SUB_L1_ONE));

        // No decision email: nothing was decided (FNOL/assignment emails are expected).
        assertFalse(mailpitFetch().contains("Decision on claim"),
                "an escalation is not a decision and sends no decision email: "
                        + mailpitFetch());
    }

    @Test
    void amountAboveTheL2LimitGoesStraightToTheSupervisor() throws Exception {
        String claimNumber = fileHomeFnol();

        HttpResponse<String> response = postJson("/api/claims/" + claimNumber + "/decision",
                holderBearer(claimNumber), "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                        + ABOVE_L2 + ",\"rationale\":\"Exceptional loss.\""
                        + ",\"expectedVersion\":" + versionOf(claimNumber) + "}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"escalatedTo\":\"SUPERVISOR\""), response.body());

        Long claimId = idOf(claimNumber);
        assertEquals("ESCALATED_SUPERVISOR", jdbcTemplate.queryForObject(
                "SELECT status FROM claim WHERE id = ?", String.class, claimId));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT assigned_adjuster_id FROM claim WHERE id = ?", Long.class, claimId),
                "an ESCALATED_SUPERVISOR claim has no adjuster");
        assertEquals(0, count("SELECT count(*) FROM payment WHERE claim_id = ?", claimId));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_ESCALATED' "
                + "AND entity_id = ?", claimId));
    }

    @Test
    void anL2AdjusterApprovingAnL2ClaimWithinLimitIsAllowed() throws Exception {
        // AUTO (POL-20002) routes to L2 and is assigned to an L2 adjuster. Approving
        // an L2-level claim within the AUTO L2 limit (10000) is a plain
        // within-authority approval; resolve the holder (V2-1 staff may add L2s).
        String claimNumber = fileAutoFnol();

        HttpResponse<String> response = postJson("/api/claims/" + claimNumber + "/decision",
                holderBearer(claimNumber), "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                        + AUTO_ABOVE_L1_WITHIN_L2
                        + ",\"rationale\":\"Within L2 authority.\""
                        + ",\"expectedVersion\":" + versionOf(claimNumber) + "}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"decision\":\"APPROVED\""), response.body());
        assertEquals("CLOSED", jdbcTemplate.queryForObject(
                "SELECT status FROM claim WHERE id = ?", String.class, idOf(claimNumber)));
        assertEquals(1, count("SELECT count(*) FROM payment WHERE claim_id = ?",
                idOf(claimNumber)));
    }

    @Test
    void escalationAboveL1WithNoL2AdjusterProvisionedGoesToTheSupervisor() throws Exception {
        // B1/S1: simulate the provisioning gap — no L2 adjuster exists to take an
        // escalation. The claim must still move out of the actor's hands (to the
        // supervisor), not blow up with a 500 or stay with the rejected actor.
        String claimNumber = fileHomeFnol();
        appUsersDeleteL2();
        try {
            HttpResponse<String> response = postJson(
                    "/api/claims/" + claimNumber + "/decision", holderBearer(claimNumber),
                    "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                            + ABOVE_L1_WITHIN_L2 + ",\"rationale\":\"no L2 available\""
                            + ",\"expectedVersion\":" + versionOf(claimNumber) + "}");
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"escalatedTo\":\"SUPERVISOR\""), response.body());
            assertFalse(response.body().contains("\"decision\":\"APPROVED\""),
                    "the above-level approval is never granted: " + response.body());

            Long claimId = idOf(claimNumber);
            assertEquals("ESCALATED_SUPERVISOR", jdbcTemplate.queryForObject(
                    "SELECT status FROM claim WHERE id = ?", String.class, claimId));
            assertNull(jdbcTemplate.queryForObject(
                    "SELECT assigned_adjuster_id FROM claim WHERE id = ?", Long.class, claimId),
                    "an escalated claim must not stay with the actor whose authority was rejected");
            assertEquals(0, count("SELECT count(*) FROM payment WHERE claim_id = ?", claimId));
            assertTrue(1 == count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_ESCALATED' "
                    + "AND entity_id = ?", claimId),
                    "a CLAIM_ESCALATED row must record the fallback");
        } finally {
            appUsersRestoreL2();
        }
    }

    @Test
    void decisionBlocksWhileAnotherTransactionHoldsTheClaimRowLock() throws Exception {
        // S2: a deterministic race test. Hold the claim row locked on a raw connection,
        // then decide on another thread. The decision must block behind that lock — if
        // ClaimRepository.findByClaimNumberForUpdate's SELECT ... FOR UPDATE were removed,
        // the decision would complete while the lock is held and this test fails. (A
        // sequential "second decision" test cannot force the two transactions to overlap.)
        String claimNumber = fileHomeFnol();
        Long claimId = idOf(claimNumber);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("SELECT id FROM claim WHERE id = " + claimId + " FOR UPDATE");
            }

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<ClaimDecisionOutcome> pending = pool.submit(() -> decisionService.decide(
                        claimNumber, SUB_L1_ONE,
                        new ClaimDecisionInput("APPROVED", new BigDecimal(WITHIN_L1),
                                "held-lock decision", versionOf(claimNumber))));

                // The lock is still held, so the decision cannot have completed.
                Thread.sleep(500);
                assertFalse(pending.isDone(),
                        "decision must block while another transaction holds the claim row lock");

                holder.commit(); // release the lock; the decision proceeds
                ClaimDecisionView view = pending.get(10, TimeUnit.SECONDS).view();
                assertEquals("APPROVED", view.decision());
                assertEquals("CLOSED", jdbcTemplate.queryForObject(
                        "SELECT status FROM claim WHERE id = ?", String.class, claimId));
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // --- validation -----------------------------------------------------------

    @Test
    void missingRationaleIsRejectedForApproveAndDeny() throws Exception {
        String claimNumber = fileHomeFnol();

        HttpResponse<String> approve = postJson("/api/claims/" + claimNumber + "/decision",
                holderBearer(claimNumber), "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                        + WITHIN_L1 + ",\"expectedVersion\":" + versionOf(claimNumber) + "}");
        assertEquals(400, approve.statusCode(), approve.body());
        assertTrue(approve.body().contains("rationale"), approve.body());

        HttpResponse<String> deny = postJson("/api/claims/" + claimNumber + "/decision",
                holderBearer(claimNumber),
                "{\"decision\":\"DENIED\",\"expectedVersion\":" + versionOf(claimNumber) + "}");
        assertEquals(400, deny.statusCode(), deny.body());
        assertTrue(deny.body().contains("rationale"), deny.body());

        assertEquals("UNDER_REVIEW", jdbcTemplate.queryForObject(
                "SELECT status FROM claim WHERE id = ?", String.class, idOf(claimNumber)));
        assertEquals(0, count("SELECT count(*) FROM payment"));
    }

    @Test
    void invalidAmountsAndDecisionsAreRejected() throws Exception {
        String claimNumber = fileHomeFnol();
        String bearer = holderBearer(claimNumber);

        assertEquals(400, postJson("/api/claims/" + claimNumber + "/decision", bearer,
                "{\"decision\":\"MAYBE\",\"indemnityAmount\":100,\"rationale\":\"x\","
                        + "\"expectedVersion\":" + versionOf(claimNumber) + "}")
                .statusCode());
        assertEquals(400, postJson("/api/claims/" + claimNumber + "/decision", bearer,
                "{\"decision\":\"APPROVED\",\"rationale\":\"x\","
                        + "\"expectedVersion\":" + versionOf(claimNumber) + "}").statusCode());
        assertEquals(400, postJson("/api/claims/" + claimNumber + "/decision", bearer,
                "{\"decision\":\"APPROVED\",\"indemnityAmount\":0,\"rationale\":\"x\","
                        + "\"expectedVersion\":" + versionOf(claimNumber) + "}")
                .statusCode());
        assertEquals(400, postJson("/api/claims/" + claimNumber + "/decision", bearer,
                "{\"decision\":\"APPROVED\",\"indemnityAmount\":1.234,\"rationale\":\"x\","
                        + "\"expectedVersion\":" + versionOf(claimNumber) + "}")
                .statusCode());
        assertEquals(400, postJson("/api/claims/" + claimNumber + "/decision", bearer,
                "{\"decision\":\"DENIED\",\"indemnityAmount\":100,\"rationale\":\"x\","
                        + "\"expectedVersion\":" + versionOf(claimNumber) + "}")
                .statusCode());
    }

    @Test
    void aSecondDecisionOnAClosedClaimIsRejectedAndPaymentStaysUnique() throws Exception {
        String claimNumber = fileHomeFnol();
        String bearer = holderBearer(claimNumber);
        String body = "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                + WITHIN_L1 + ",\"rationale\":\"first\""
                + ",\"expectedVersion\":" + versionOf(claimNumber) + "}";

        assertEquals(200, postJson("/api/claims/" + claimNumber + "/decision", bearer, body)
                .statusCode());

        HttpResponse<String> second = postJson("/api/claims/" + claimNumber + "/decision",
                bearer, "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                        + WITHIN_L1 + ",\"rationale\":\"first\""
                        + ",\"expectedVersion\":" + versionOf(claimNumber) + "}");
        assertEquals(400, second.statusCode(), second.body());
        assertTrue(second.body().contains("already been decided"), second.body());
        assertEquals(1, count("SELECT count(*) FROM payment WHERE claim_id = ?",
                idOf(claimNumber)));
    }

    // --- auth / 404 matrix ----------------------------------------------------

    @Test
    void decisionEndpointAuthMatrix() throws Exception {
        String claimNumber = fileHomeFnol();
        // Unknown claim -> 404 (version 0: the row does not exist to bump).
        assertEquals(404, postJson("/api/claims/CLM-999999/decision", adjusterOneBearer(),
                "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                        + WITHIN_L1 + ",\"rationale\":\"x\",\"expectedVersion\":0}")
                .statusCode());
        String body = "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                + WITHIN_L1 + ",\"rationale\":\"x\",\"expectedVersion\":"
                + versionOf(claimNumber) + "}";

        // Anonymous -> 401; claimant and supervisor -> 403 (supervisor's decision surface is
        // the slice-5 escalation endpoint, not this one).
        assertEquals(401, postJson("/api/claims/" + claimNumber + "/decision", null, body)
                .statusCode());
        assertEquals(403, postJson("/api/claims/" + claimNumber + "/decision", claimantBearer(),
                body).statusCode());
        assertEquals(403, postJson("/api/claims/" + claimNumber + "/decision",
                JwtTestConfig.tokenFor("sub-supervisor-decide", "supervisor"), body)
                .statusCode());

        // A non-assignee adjuster (adjuster.two is L1 but not the assignee) -> 404.
        assertEquals(404, postJson("/api/claims/" + claimNumber + "/decision",
                JwtTestConfig.tokenFor("10000000-0000-0000-0000-000000000002", "adjuster_l1"),
                body).statusCode());
    }

    @Test
    void anEscalatedClaimLeavesTheOriginalActorsHands() throws Exception {
        String claimNumber = fileHomeFnol();
        String originalBearer = holderBearer(claimNumber);
        String body = "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                + ABOVE_L1_WITHIN_L2 + ",\"rationale\":\"above L1\""
                + ",\"expectedVersion\":" + versionOf(claimNumber) + "}";

        assertEquals(200, postJson("/api/claims/" + claimNumber + "/decision",
                originalBearer, body).statusCode());

        // The original L1 actor can no longer see or decide the claim: it moved to L2.
        assertEquals(404, postJson("/api/claims/" + claimNumber + "/decision",
                originalBearer, body).statusCode());
        assertEquals(404, get("/api/claims/" + claimNumber + "/full", originalBearer)
                .statusCode());

        // The L2 adjuster who now holds it may approve within their limit. The
        // escalated assignee is whichever L2 adjuster was least-loaded (V2-1 staff);
        // resolve the bearer from the claim row instead of assuming adjuster.three.
        // Realm role is adjuster_l2 (SecurityConfig maps ROLE_* directly).
        String holderSub = jdbcTemplate.queryForObject(
                "SELECT a.keycloak_sub FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        HttpResponse<String> l2Decision = postJson("/api/claims/" + claimNumber + "/decision",
                JwtTestConfig.tokenFor(holderSub, "adjuster_l2"),
                "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                        + ABOVE_L1_WITHIN_L2 + ",\"rationale\":\"within L2\""
                        + ",\"expectedVersion\":" + versionOf(claimNumber) + "}");
        assertEquals(200, l2Decision.statusCode(), l2Decision.body());
        assertEquals("CLOSED", jdbcTemplate.queryForObject(
                "SELECT status FROM claim WHERE id = ?", String.class, idOf(claimNumber)));
    }

    @Test
    void aClosedClaimIsStillVisibleOnlyToItsOwner() throws Exception {
        // Slice 6: the decision fields reach the owner of a closed claim — and the access
        // rules are status-independent: a closed claim number is never revealed to an
        // anonymous caller (401) or another claimant (404), exactly like an open one.
        String claimNumber = fileHomeFnol();
        postJson("/api/claims/" + claimNumber + "/decision", holderBearer(claimNumber),
                "{\"decision\":\"DENIED\",\"rationale\":\"Not covered.\","
                        + "\"expectedVersion\":" + versionOf(claimNumber) + "}");

        assertEquals(200, get("/api/claims/" + claimNumber, claimantBearer()).statusCode(),
                "the owner reads their closed claim's decision");
        assertEquals(401, get("/api/claims/" + claimNumber, null).statusCode());
        assertEquals(404, get("/api/claims/" + claimNumber,
                JwtTestConfig.tokenFor("sub-claimant-other", "claimant")).statusCode(),
                "someone else's closed claim is a 404, never a 403");
    }

    // --- helpers ---------------------------------------------------------------

    /**
     * V2-1: HOME no longer exists as a routing code (POL-10001 is HLTH-PLUS). These
     * helpers pin the claim's assignee and return a bearer for whoever holds it, so
     * the gate assertions (not the routing) stay the subject of every test.
     */
    private String fileHomeFnol() throws Exception {
        return fileFnol(Map.of(
                "policyNumber", "POL-10001",
                "holderName", "Ada Lovelace",
                "holderEmail", "ada.lovelace@example.test",
                "lossDate", "2026-09-01",
                "lossLocation", "London",
                "lossDescription", "Kitchen flooded after a pipe burst."));
    }

    private String fileAutoFnol() throws Exception {
        return fileFnol(Map.of(
                "policyNumber", "POL-20002",
                "holderName", "Grace Hopper",
                "holderEmail", "grace.hopper@example.test",
                "lossDate", "2026-09-01",
                "lossLocation", "Manchester",
                "lossDescription", "Rear-ended at a roundabout."));
    }

    private String fileFnol(Map<String, String> fields) throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(fields));
        assertEquals(201, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    private HttpResponse<String> postJson(String path, String bearer, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String bearer, byte[] body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] multipart(Map<String, String> fields) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Map.Entry<String, String> field : fields.entrySet()) {
                write(out, "--" + BOUNDARY + "\r\n");
                write(out, "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n");
                write(out, field.getValue() + "\r\n");
            }
            write(out, "--" + BOUNDARY + "--\r\n");
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void write(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
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

    private Long l2AdjusterId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE keycloak_sub = ?", Long.class, SUB_L2);
    }

    private String keycloakSubOf(Long appUserId) {
        return jdbcTemplate.queryForObject(
                "SELECT keycloak_sub FROM app_user WHERE id = ?", String.class, appUserId);
    }

    /** Removes all L2 adjusters to simulate the provisioning gap; pair with restore. */
    private void appUsersDeleteL2() {
        jdbcTemplate.update("DELETE FROM app_user WHERE level = 'L2'");
    }

    /** Restores the seeded L2 adjusters (fresh ids, same identities as the seeds). */
    private void appUsersRestoreL2() {
        jdbcTemplate.update("INSERT INTO app_user (keycloak_sub, display_name, email, level) "
                + "VALUES (?, ?, ?, ?)", SUB_L2, "Ines Kowalski", "ines.kowalski@claims.test", "L2");
        jdbcTemplate.update("INSERT INTO app_user (keycloak_sub, display_name, email, level) "
                + "VALUES (?, ?, ?, ?)", SUB_L2_TWO, "Rahul Singh", "rahul.singh@claims.test",
                "L2");
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }

    private String mailpitUrl() {
        return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
    }

    private String mailpitFetch() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(mailpitUrl() + "/api/v1/messages"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private String claimantBearer() {
        return JwtTestConfig.tokenFor(CLAIMANT, "claimant");
    }

    private String adjusterOneBearer() {
        return JwtTestConfig.tokenFor(SUB_L1_ONE, "adjuster_l1");
    }

    /**
     * V2-1: bearer for whoever currently holds the claim (the gate — not routing —
     * is under test). Resolves the assignee from the claim row. Roles are the real
     * Keycloak names (adjuster_l1/l2/l3): JwtTestConfig signs realm_access roles, and
     * SecurityConfig maps ROLE_* directly, so the suffix must match the staff level.
     */
    private String holderBearer(String claimNumber) {
        String sub = jdbcTemplate.queryForObject(
                "SELECT a.keycloak_sub FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        String level = jdbcTemplate.queryForObject(
                "SELECT a.level FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        return JwtTestConfig.tokenFor(sub, "adjuster_" + level.toLowerCase());
    }

    private String adjusterThreeBearer() {
        return JwtTestConfig.tokenFor(SUB_L2, "adjuster_l2");
    }
}
