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
import java.util.Map;

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
 * Slice-5 acceptance for the supervisor half of Flow 4: real HTTP against real Postgres —
 * a supervisor approves or denies a claim in {@code ESCALATED_SUPERVISOR} (rationale
 * required; approve records the single payment with a NULL authorized-by — a supervisor
 * has no app_user row — and closes; deny closes with remarks), with the DECISION audit and
 * the decision email. Also pins the supervisor-only rules on the escalation queue
 * (GET /api/escalations) and the claimant-visible escalated process step while the claim
 * waits on the supervisor.
 *
 * <p>Fixtures are real flows: an FNOL + an above-L2 approval by the L1 adjuster produces
 * the {@code ESCALATED_SUPERVISOR} claim, exactly as slice 4 ships them.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EscalationDecisionIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----EscalationDecisionBoundary5";

    // Seeded staff (V4): adjuster.one/two are L1, adjuster.three is L2.
    private static final String SUB_L1_ONE = "10000000-0000-0000-0000-000000000001";
    private static final String SUB_L2 = "10000000-0000-0000-0000-000000000003";
    private static final String CLAIMANT = "sub-claimant-esc";
    // The realm-provisioned supervisor (fixed subject, no app_user row — see decisions.md).
    private static final String SUB_SUPERVISOR = "10000000-0000-0000-0000-000000000004";

    private static final String ABOVE_L2 = "12000.00";

    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
            DockerImageName.parse("axllent/mailpit:v1.22"))
            .withExposedPorts(1025, 8025);

    private static final Path UPLOADS = createUploadsDir();

    static {
        MAILPIT.start();
    }

    private static Path createUploadsDir() {
        try {
            return Files.createTempDirectory("claims-escalation-test-uploads");
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

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clearMailbox() throws Exception {
        http.send(HttpRequest.newBuilder(URI.create(mailpitUrl() + "/api/v1/messages"))
                .DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    // --- supervisor approval closes with one payment + audit + email --------------

    @Test
    void supervisorApprovalRecordsOnePaymentAndCloses() throws Exception {
        String claimNumber = escalatedClaimNumber();

        HttpResponse<String> response = postJson("/api/claims/" + claimNumber
                + "/escalation-decision", supervisorBearer(),
                "{\"decision\":\"APPROVED\",\"indemnityAmount\":" + ABOVE_L2
                        + ",\"rationale\":\"Approved under full authority.\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"decision\":\"APPROVED\""), response.body());
        assertTrue(response.body().contains("\"indemnityAmount\":"), response.body());

        Long claimId = idOf(claimNumber);
        assertEquals("CLOSED", statusOf(claimNumber));
        assertEquals("APPROVED", jdbcTemplate.queryForObject(
                "SELECT decision FROM claim WHERE id = ?", String.class, claimId));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT closed_at FROM claim WHERE id = ?", java.sql.Timestamp.class, claimId));
        // Exactly one payment, amount == indemnity, authorized_by NULL: the supervisor has
        // no app_user row (their identity rides the audit row instead).
        assertEquals(1, count("SELECT count(*) FROM payment WHERE claim_id = ?", claimId));
        assertEquals(0, new BigDecimal(ABOVE_L2).compareTo(jdbcTemplate.queryForObject(
                "SELECT amount FROM payment WHERE claim_id = ?", BigDecimal.class, claimId)));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT authorized_by_id FROM payment WHERE claim_id = ?", Long.class, claimId),
                "a supervisor approval has no app_user authorizer");
        // DECISION audit with the supervisor subject + rationale, before = the escalated state.
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'DECISION' "
                + "AND entity_id = ? AND actor_sub = ? AND rationale = ?", claimId,
                SUB_SUPERVISOR, "Approved under full authority."));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'DECISION' "
                + "AND entity_id = ? AND before::text LIKE ?", claimId,
                "%ESCALATED_SUPERVISOR%"));

        // Decision email to the verified holder address (approval copy), carrying the amount.
        String inbox = mailpitFetch();
        assertTrue(inbox.contains("Decision on claim " + claimNumber), inbox);
        assertTrue(inbox.contains("approved"), inbox);
        assertTrue(inbox.contains("We will pay"), inbox);
        assertTrue(inbox.contains(ABOVE_L2), "the approval email states the amount: " + inbox);

        // The claimant's view of the supervisor-closed claim (slice 6): decision + the
        // approved amount, wall intact — a supervisor approval reaches the claimant the
        // same way an adjuster's does.
        String claimantView = get("/api/claims/" + claimNumber, claimantBearer()).body();
        assertTrue(claimantView.contains("\"status\":\"CLOSED\""), claimantView);
        assertTrue(claimantView.contains("\"decision\":\"APPROVED\""), claimantView);
        assertTrue(claimantView.contains("\"indemnityAmount\":" + ABOVE_L2), claimantView);
        assertFalse(claimantView.contains("decisionRemarks"), claimantView);
        assertTrue(claimantView.contains("Under review"), claimantView);
        assertFalse(claimantView.contains("reserveAmount"), claimantView);
        assertFalse(claimantView.contains("\"notes\""), claimantView);
        assertFalse(claimantView.contains("\"assignedTo\""), claimantView);
        assertFalse(claimantView.contains("\"coverage\""), claimantView);
    }

    // --- supervisor denial closes with remarks -------------------------------------

    @Test
    void supervisorDenialClosesWithRemarksAndNoPayment() throws Exception {
        String claimNumber = escalatedClaimNumber();

        HttpResponse<String> response = postJson("/api/claims/" + claimNumber
                + "/escalation-decision", supervisorBearer(),
                "{\"decision\":\"DENIED\",\"rationale\":\"Coverage excludes the reported damage.\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"decision\":\"DENIED\""), response.body());

        Long claimId = idOf(claimNumber);
        assertEquals("CLOSED", statusOf(claimNumber));
        assertEquals("Coverage excludes the reported damage.", jdbcTemplate.queryForObject(
                "SELECT decision_remarks FROM claim WHERE id = ?", String.class, claimId),
                "the denial rationale is the claimant-visible remarks");
        assertEquals(0, count("SELECT count(*) FROM payment WHERE claim_id = ?", claimId));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'DECISION' "
                + "AND entity_id = ? AND actor_sub = ? AND rationale = ?", claimId,
                SUB_SUPERVISOR, "Coverage excludes the reported damage."));

        // The claimant's view of the supervisor-denied claim (slice 6): denial + remarks
        // verbatim, no amount, wall intact.
        String claimantView = get("/api/claims/" + claimNumber, claimantBearer()).body();
        assertTrue(claimantView.contains("\"decision\":\"DENIED\""), claimantView);
        assertTrue(claimantView.contains("\"decisionRemarks\":\"Coverage excludes the "
                + "reported damage.\""), "the supervisor rationale is the claimant-visible "
                + "remarks: " + claimantView);
        assertFalse(claimantView.contains("indemnityAmount"), claimantView);
        assertFalse(claimantView.contains("reserveAmount"), claimantView);
        assertFalse(claimantView.contains("\"notes\""), claimantView);

        // The denial email carries the remarks in the body.
        String inbox = mailpitFetch();
        assertTrue(inbox.contains("not been approved"),
                "denial email is sent: " + inbox);
        assertTrue(inbox.contains("Coverage excludes the reported damage."),
                "the denial email states the remarks: " + inbox);
    }

    // --- validation ---------------------------------------------------------------

    @Test
    void missingRationaleIsRejectedForApproveAndDeny() throws Exception {
        String claimNumber = escalatedClaimNumber();
        String path = "/api/claims/" + claimNumber + "/escalation-decision";

        HttpResponse<String> approve = postJson(path, supervisorBearer(),
                "{\"decision\":\"APPROVED\",\"indemnityAmount\":" + ABOVE_L2 + "}");
        assertEquals(400, approve.statusCode(), approve.body());
        assertTrue(approve.body().contains("rationale"), approve.body());

        HttpResponse<String> deny = postJson(path, supervisorBearer(),
                "{\"decision\":\"DENIED\"}");
        assertEquals(400, deny.statusCode(), deny.body());
        assertTrue(deny.body().contains("rationale"), deny.body());

        assertEquals("ESCALATED_SUPERVISOR", statusOf(claimNumber),
                "a rejected request must not move the claim");
        assertEquals(0, count("SELECT count(*) FROM payment"));
    }

    @Test
    void invalidDecisionsAndAmountsAreRejected() throws Exception {
        String claimNumber = escalatedClaimNumber();
        String path = "/api/claims/" + claimNumber + "/escalation-decision";
        String bearer = supervisorBearer();

        assertEquals(400, postJson(path, bearer,
                "{\"decision\":\"MAYBE\",\"indemnityAmount\":100,\"rationale\":\"x\"}")
                .statusCode());
        assertEquals(400, postJson(path, bearer,
                "{\"decision\":\"APPROVED\",\"rationale\":\"x\"}").statusCode());
        assertEquals(400, postJson(path, bearer,
                "{\"decision\":\"APPROVED\",\"indemnityAmount\":0,\"rationale\":\"x\"}")
                .statusCode());
        assertEquals(400, postJson(path, bearer,
                "{\"decision\":\"APPROVED\",\"indemnityAmount\":1.234,\"rationale\":\"x\"}")
                .statusCode());
        assertEquals(400, postJson(path, bearer,
                "{\"decision\":\"DENIED\",\"indemnityAmount\":100,\"rationale\":\"x\"}")
                .statusCode());
    }

    @Test
    void aSecondEscalationDecisionIsRejectedAndPaymentStaysUnique() throws Exception {
        String claimNumber = escalatedClaimNumber();
        String path = "/api/claims/" + claimNumber + "/escalation-decision";
        String body = "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                + ABOVE_L2 + ",\"rationale\":\"first\"}";

        assertEquals(200, postJson(path, supervisorBearer(), body).statusCode());
        HttpResponse<String> second = postJson(path, supervisorBearer(), body);
        assertEquals(400, second.statusCode(), second.body());
        assertTrue(second.body().contains("already been decided"), second.body());
        assertEquals(1, count("SELECT count(*) FROM payment WHERE claim_id = ?",
                idOf(claimNumber)));
    }

    // --- supervisor-only rules ----------------------------------------------------

    @Test
    void escalationDecisionEndpointAuthMatrix() throws Exception {
        String claimNumber = escalatedClaimNumber();
        String path = "/api/claims/" + claimNumber + "/escalation-decision";
        String body = "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                + ABOVE_L2 + ",\"rationale\":\"x\"}";

        // Only SUPERVISOR acts here: anonymous 401; claimant and both adjuster levels 403.
        assertEquals(401, postJson(path, null, body).statusCode());
        assertEquals(403, postJson(path, claimantBearer(), body).statusCode());
        assertEquals(403, postJson(path, JwtTestConfig.tokenFor(SUB_L1_ONE, "adjuster_l1"), body)
                .statusCode());
        assertEquals(403, postJson(path, JwtTestConfig.tokenFor(SUB_L2, "adjuster_l2"), body)
                .statusCode());

        // Unknown claim -> 404; a claim not on the supervisor -> 400 with an actionable
        // message (the supervisor can see team claims, so the state is the problem).
        assertEquals(404, postJson("/api/claims/CLM-999999/escalation-decision",
                supervisorBearer(), body).statusCode());
        String plainClaim = fileHomeFnol();
        HttpResponse<String> notEscalated = postJson("/api/claims/" + plainClaim
                + "/escalation-decision", supervisorBearer(), body);
        assertEquals(400, notEscalated.statusCode(), notEscalated.body());
        assertTrue(notEscalated.body().contains("not awaiting a supervisor decision"),
                notEscalated.body());
        assertEquals("UNDER_REVIEW", statusOf(plainClaim),
                "a rejected non-escalated request must not move the claim");
    }

    @Test
    void theEscalationQueueIsSupervisorOnlyAndListsOnlyEscalatedClaims() throws Exception {
        String escalated = escalatedClaimNumber();
        String plain = fileHomeFnol();

        // The supervisor's escalation queue shows the escalated claim, never the open
        // claim still in the adjuster's hands.
        HttpResponse<String> supervisorList = get("/api/escalations", supervisorBearer());
        assertEquals(200, supervisorList.statusCode(), supervisorList.body());
        assertTrue(supervisorList.body().contains(escalated), supervisorList.body());
        assertFalse(supervisorList.body().contains("\"" + plain + "\""),
                "an under-review claim is not an escalation: " + supervisorList.body());

        // Everyone else is blocked.
        assertEquals(401, get("/api/escalations", null).statusCode());
        assertEquals(403, get("/api/escalations", claimantBearer()).statusCode());
        assertEquals(403, get("/api/escalations",
                JwtTestConfig.tokenFor(SUB_L1_ONE, "adjuster_l1")).statusCode());
        assertEquals(403, get("/api/escalations",
                JwtTestConfig.tokenFor(SUB_L2, "adjuster_l2")).statusCode());
    }

    @Test
    void aDecidedEscalationLeavesTheEscalationQueue() throws Exception {
        String claimNumber = escalatedClaimNumber();

        HttpResponse<String> list = get("/api/escalations", supervisorBearer());
        assertTrue(list.body().contains(claimNumber), list.body());

        assertEquals(200, postJson("/api/claims/" + claimNumber + "/escalation-decision",
                supervisorBearer(), "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                        + ABOVE_L2 + ",\"rationale\":\"Approved.\"}").statusCode());

        HttpResponse<String> after = get("/api/escalations", supervisorBearer());
        assertFalse(after.body().contains("\"" + claimNumber + "\""),
                "a decided escalation is CLOSED and drops out of the queue: " + after.body());
    }

    // --- claimant-visible aging step (Flow 6) --------------------------------------

    @Test
    void theClaimantSeesTheEscalatedProcessStepWhileTheClaimAwaitsTheSupervisor()
            throws Exception {
        String claimNumber = escalatedClaimNumber();

        String claimantView = get("/api/claims/" + claimNumber, claimantBearer()).body();
        assertTrue(claimantView.contains("ESCALATED_SUPERVISOR"), claimantView);
        assertTrue(claimantView.contains("Escalated"), claimantView);
        // The visibility wall holds on the escalated claim: no internal fields leak.
        assertFalse(claimantView.contains("reserveAmount"), claimantView);
        assertFalse(claimantView.contains("\"notes\""), claimantView);
        // Slice 6: an undecided (open) claim carries no decision content at all — the
        // decision fields serialize only once a decision exists.
        assertFalse(claimantView.contains("\"decision\""),
                "an open claim must not carry decision fields: " + claimantView);
    }

    // --- helpers ---------------------------------------------------------------------

    /** An FNOL claim pushed to ESCALATED_SUPERVISOR by an above-L2 approval (slice-4 flow). */
    private String escalatedClaimNumber() throws Exception {
        String claimNumber = fileHomeFnol();
        HttpResponse<String> escalation = postJson("/api/claims/" + claimNumber + "/decision",
                adjusterOneBearer(), "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                        + ABOVE_L2 + ",\"rationale\":\"Exceptional loss.\"}");
        assertEquals(200, escalation.statusCode(), escalation.body());
        assertTrue(escalation.body().contains("\"escalatedTo\":\"SUPERVISOR\""),
                escalation.body());
        return claimNumber;
    }

    private String fileHomeFnol() throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", "Kitchen flooded after a pipe burst.")));
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

    private HttpResponse<String> post(String path, String bearer, byte[] body)
            throws Exception {
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

    private String statusOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM claim WHERE claim_number = ?", String.class, claimNumber);
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

    private String supervisorBearer() {
        return JwtTestConfig.tokenFor(SUB_SUPERVISOR, "supervisor");
    }
}
