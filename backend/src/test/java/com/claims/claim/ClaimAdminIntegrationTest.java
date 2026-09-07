package com.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.claims.TestcontainersConfiguration;
import com.claims.audit.AuditLogWriter;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

/**
 * Slice-7 acceptance for the supervisor's claim-admin surface: the immutable audit-log view
 * for a claim (append-only enforced at the data layer — raw UPDATE/DELETE are rejected) and
 * the reassign endpoint (supervisor moves a claim to a target level, routed to that level's
 * least-loaded adjuster, with a CLAIM_REASSIGNED audit row).
 *
 * <p>Seeded staff: adjuster.one/two are L1, adjuster.three is L2. With claim tables reset
 * per test the first FNOL deterministically lands on adjuster.one; a reassign to L1 then
 * deterministically lands on adjuster.two (fewest open claims).
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClaimAdminIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----ClaimAdminBoundary3";

    private static final String SUB_L1_ONE = "10000000-0000-0000-0000-000000000001";
    private static final String SUB_L1_TWO = "10000000-0000-0000-0000-000000000002";
    private static final String SUB_L2 = "10000000-0000-0000-0000-000000000003";
    private static final String SUB_SUPERVISOR = "10000000-0000-0000-0000-000000000004";
    private static final String CLAIMANT = "sub-claimant-admin";

    private static final String WITHIN_L1 = "1500.00";
    private static final String ABOVE_L2 = "1200000.00";  // V2-1: exceeds HLTH-PLUS L2 (400000)

    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
            DockerImageName.parse("axllent/mailpit:v1.22"))
            .withExposedPorts(1025, 8025);

    private static final Path UPLOADS = createUploadsDir();

    static {
        MAILPIT.start();
    }

    private static Path createUploadsDir() {
        try {
            return Files.createTempDirectory("claims-admin-test-uploads");
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
    private AuditLogWriter auditLog;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clearMailbox() throws Exception {
        http.send(HttpRequest.newBuilder(URI.create(mailpitUrl() + "/api/v1/messages"))
                .DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    // --- audit log read -------------------------------------------------------

    @Test
    void theSupervisorReadsTheClaimsAuditLogOldestFirst() throws Exception {
        String claimNumber = fileHomeFnol();
        approve(claimNumber, WITHIN_L1, "Quotes verified; within my authority.");

        HttpResponse<String> response = get("/api/claims/" + claimNumber + "/audit",
                supervisorBearer());
        assertEquals(200, response.statusCode(), response.body());

        // The story reads chronologically: created (by the claimant), assigned (system
        // actor), decision (by the adjuster, with the rationale).
        int created = response.body().indexOf("\"action\":\"CLAIM_CREATED\"");
        int assigned = response.body().indexOf("\"action\":\"CLAIM_ASSIGNED\"");
        int decision = response.body().indexOf("\"action\":\"DECISION\"");
        assertTrue(created >= 0, response.body());
        assertTrue(assigned > created, response.body());
        assertTrue(decision > assigned, response.body());
        assertTrue(response.body().contains("\"actorSub\":\"" + CLAIMANT + "\""), response.body());
        assertTrue(response.body().contains("\"actorSub\":null"),
                "the system assignment row has no actor: " + response.body());
        assertTrue(response.body().contains("\"actorSub\":\"" + SUB_L1_ONE + "\""), response.body());
        assertTrue(response.body().contains("\"rationale\":\"Quotes verified; within my "
                + "authority.\""), response.body());
        assertTrue(response.body().contains("\"decision\":\"APPROVED\""),
                "the decision payload rides the log: " + response.body());
        // The payload amount is the jsonb-stored figure (Postgres jsonb normalizes the
        // scale of stored numerics — 1500.00 is recorded as 1500.0).
        assertTrue(response.body().contains("\"indemnityAmount\":1500.0"), response.body());
        assertTrue(response.body().contains("\"before\":"), response.body());
        assertTrue(response.body().contains("\"after\":"), response.body());
    }

    @Test
    void auditEndpointIsSupervisorOnlyAndUnknownClaimsAre404() throws Exception {
        String claimNumber = fileHomeFnol();
        String path = "/api/claims/" + claimNumber + "/audit";

        assertEquals(401, get(path, null).statusCode());
        // Not even the claim's own claimant or its assigned adjuster: the audit log is the
        // supervisor's compliance surface.
        assertEquals(403, get(path, claimantBearer()).statusCode());
        assertEquals(403, get(path, adjusterOneBearer()).statusCode());
        assertEquals(404, get("/api/claims/CLM-999999/audit", supervisorBearer()).statusCode());
    }

    @Test
    void auditLogIsAppendOnlyAndRejectsRawUpdatesAndDeletes() throws Exception {
        fileHomeFnol();
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM audit_log WHERE action = 'CLAIM_CREATED'", Long.class);
        long before = count("SELECT count(*) FROM audit_log");

        // A raw UPDATE or DELETE is rejected by the data layer, not just by app discipline.
        assertThrows(DataAccessException.class,
                () -> jdbcTemplate.update("UPDATE audit_log SET rationale = 'tampered' "
                        + "WHERE id = ?", id));
        assertThrows(DataAccessException.class,
                () -> jdbcTemplate.update("DELETE FROM audit_log WHERE id = ?", id));
        assertEquals(before, count("SELECT count(*) FROM audit_log"),
                "a rejected write must leave the log untouched");

        // Appending (INSERT) still works — the log is append-only, not frozen.
        auditLog.append("sub-actor", "TEST_ACTION", "CLAIM", id, null, null, null);
        assertEquals(before + 1, count("SELECT count(*) FROM audit_log"));
    }

    // --- reassign -------------------------------------------------------------

    @Test
    void supervisorReassignsAnL1ClaimToTheOtherL1AdjusterAndLogsIt() throws Exception {
        String claimNumber = fileHomeFnol();
        // First FNOL of a reset test lands on adjuster.one; the claim is under his hands.
        assertEquals("Priya Sharma", assignedTo(claimNumber));

        HttpResponse<String> response = postJson("/api/claims/" + claimNumber + "/reassign",
                supervisorBearer(), "{\"level\":\"L1\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"claimNumber\":\"" + claimNumber + "\""),
                response.body());
        assertTrue(response.body().contains("\"status\":\"UNDER_REVIEW\""), response.body());
        assertTrue(response.body().contains("\"level\":\"L1\""), response.body());
        // Three L1 adjusters now (V2-1 staff): the reassign picks a least-loaded one
        // that is not the current holder.
        String newAssignee = jdbcTemplate.queryForObject(
                "SELECT a.display_name FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        assertTrue("Marcus Webb".equals(newAssignee) || "Aisha Verma".equals(newAssignee),
                "the reassign picks a least-loaded L1 adjuster, not the holder: "
                        + response.body());

        // The audit records the supervisor as the actor with the before/after assignees.
        String audit = get("/api/claims/" + claimNumber + "/audit", supervisorBearer()).body();
        assertTrue(audit.contains("\"action\":\"CLAIM_REASSIGNED\""), audit);
        assertTrue(audit.contains("\"actorSub\":\"" + SUB_SUPERVISOR + "\""), audit);
        assertTrue(audit.contains("Priya Sharma"), audit);
        assertTrue(audit.contains(newAssignee), audit);

        // The claim leaves the original holder's hands and is visible to the new one.
        assertEquals(404, get("/api/claims/" + claimNumber + "/full", adjusterOneBearer())
                .statusCode());
        String newSub = jdbcTemplate.queryForObject(
                "SELECT a.keycloak_sub FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        assertEquals(200, get("/api/claims/" + claimNumber + "/full",
                JwtTestConfig.tokenFor(newSub, "adjuster_l1"))
                .statusCode());
    }

    @Test
    void supervisorReassignsToL2RelevelsTheClaimOntoAnL2Adjuster() throws Exception {
        String claimNumber = fileHomeFnol();
        assertEquals("L1", jdbcTemplate.queryForObject(
                "SELECT level FROM claim WHERE claim_number = ?", String.class, claimNumber));

        HttpResponse<String> response = postJson("/api/claims/" + claimNumber + "/reassign",
                supervisorBearer(), "{\"level\":\"L2\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"level\":\"L2\""), response.body());
        String newAssignee = jdbcTemplate.queryForObject(
                "SELECT a.display_name FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        assertTrue("Ines Kowalski".equals(newAssignee) || "Rahul Singh".equals(newAssignee),
                "the reassign picks a least-loaded L2 adjuster: " + response.body());

        // The claim's routing level follows its new holder (aging/gate coherence).
        assertEquals("L2", jdbcTemplate.queryForObject(
                "SELECT level FROM claim WHERE claim_number = ?", String.class, claimNumber));
        String audit = get("/api/claims/" + claimNumber + "/audit", supervisorBearer()).body();
        assertTrue(audit.contains("\"action\":\"CLAIM_REASSIGNED\""), audit);
    }

    @Test
    void reassignRejectsClosedAndSupervisorEscalatedClaims() throws Exception {
        // A decided claim is terminal: 400, never silently moved.
        String closed = fileHomeFnol();
        approve(closed, WITHIN_L1, "Approve first.");
        HttpResponse<String> closedResponse = postJson("/api/claims/" + closed + "/reassign",
                supervisorBearer(), "{\"level\":\"L1\"}");
        assertEquals(400, closedResponse.statusCode(), closedResponse.body());
        assertTrue(closedResponse.body().contains("already been decided"), closedResponse.body());

        // A claim awaiting the supervisor has no adjuster to re-hold it; pulling it out of
        // the escalation queue silently would strand it.
        String escalated = fileHomeFnol();
        escalateAboveL2(escalated);
        HttpResponse<String> escalatedResponse = postJson("/api/claims/" + escalated
                + "/reassign", supervisorBearer(), "{\"level\":\"L2\"}");
        assertEquals(400, escalatedResponse.statusCode(), escalatedResponse.body());
        assertTrue(escalatedResponse.body().contains("awaiting a supervisor decision"),
                escalatedResponse.body());
        assertEquals("ESCALATED_SUPERVISOR", jdbcTemplate.queryForObject(
                "SELECT status FROM claim WHERE claim_number = ?", String.class, escalated),
                "a rejected reassign must not move the claim");
    }

    @Test
    void reassignRejectsInvalidLevelsAndUnknownClaims() throws Exception {
        String claimNumber = fileHomeFnol();
        String path = "/api/claims/" + claimNumber + "/reassign";

        HttpResponse<String> bad = postJson(path, supervisorBearer(), "{\"level\":\"L4\"}");
        assertEquals(400, bad.statusCode(), bad.body());
        assertTrue(bad.body().contains("L1, L2, or L3"), bad.body());

        HttpResponse<String> missing = postJson(path, supervisorBearer(), "{}");
        assertEquals(400, missing.statusCode(), missing.body());

        assertEquals(404, postJson("/api/claims/CLM-999999/reassign", supervisorBearer(),
                "{\"level\":\"L1\"}").statusCode());
    }

    @Test
    void reassignEndpointIsSupervisorOnly() throws Exception {
        String claimNumber = fileHomeFnol();
        String path = "/api/claims/" + claimNumber + "/reassign";
        String body = "{\"level\":\"L1\"}";

        // Anonymous -> 401. Claimant, the assigned L1 holder, and the L2 adjuster -> 403:
        // only a supervisor reassigns (the URL rule, before any claim logic).
        assertEquals(401, postJson(path, null, body).statusCode());
        assertEquals(403, postJson(path, claimantBearer(), body).statusCode());
        assertEquals(403, postJson(path, adjusterOneBearer(), body).statusCode());
        assertEquals(403, postJson(path, JwtTestConfig.tokenFor(SUB_L2, "adjuster_l2"), body)
                .statusCode());
    }

    @Test
    void reassignWithNoAdjusterOfTheRequestedLevelIsRejected() throws Exception {
        // The supervisor explicitly asked for L2; with no L2 adjuster provisioned the
        // reassign must be an actionable 400, not a silent no-op or a stranded claim.
        String claimNumber = fileHomeFnol();
        appUsersDeleteL2();
        try {
            HttpResponse<String> response = postJson("/api/claims/" + claimNumber + "/reassign",
                    supervisorBearer(), "{\"level\":\"L2\"}");
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(response.body().contains("L2 adjuster is provisioned"), response.body());
            assertEquals("Priya Sharma", assignedTo(claimNumber),
                    "the claim stays with its current assignee after the rejected reassign");
        } finally {
            appUsersRestoreL2();
        }
    }

    @Test
    void aSupervisorCanReassignAStrandedUnassignedClaim() throws Exception {        // A claim left UNASSIGNED by the FNOL provisioning gap (no L1 adjuster existed at
        // filing time) is exactly what a supervisor reassign rescues — it must be accepted,
        // handed to the least-loaded L1 adjuster once one exists, and audited with a null
        // previous holder.
        String claimNumber;
        appUsersDeleteL1s();
        try {
            claimNumber = fileHomeFnol();
            assertEquals("UNASSIGNED", jdbcTemplate.queryForObject(
                    "SELECT status FROM claim WHERE claim_number = ?", String.class, claimNumber),
                    "with no L1 adjuster provisioned the FNOL stays UNASSIGNED");
            assertNull(jdbcTemplate.queryForObject(
                    "SELECT assigned_adjuster_id FROM claim WHERE claim_number = ?",
                    Long.class, claimNumber));
        } finally {
            appUsersRestoreL1s();
        }

        // Both L1 adjusters are back (fresh ids, same identities); the stranded claim can
        // now be handed out — the lowest-id adjuster of the level wins the tie at zero open
        // claims, i.e. the restored adjuster.one (Priya Sharma).
        HttpResponse<String> response = postJson("/api/claims/" + claimNumber + "/reassign",
                supervisorBearer(), "{\"level\":\"L1\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"status\":\"UNDER_REVIEW\""), response.body());
        assertTrue(response.body().contains("\"level\":\"L1\""), response.body());
        assertTrue(response.body().contains("\"assignedTo\":\"Priya Sharma\""), response.body());

        String audit = get("/api/claims/" + claimNumber + "/audit", supervisorBearer()).body();
        assertTrue(audit.contains("\"action\":\"CLAIM_REASSIGNED\""), audit);
        assertTrue(audit.contains("\"assignedTo\":\"Priya Sharma\""), audit);
    }

    // --- helpers --------------------------------------------------------------

    private void approve(String claimNumber, String amount, String rationale) throws Exception {
        String holder = jdbcTemplate.queryForObject(
                "SELECT a.keycloak_sub FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        String level = jdbcTemplate.queryForObject(
                "SELECT a.level FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        HttpResponse<String> decision = postJson("/api/claims/" + claimNumber + "/decision",
                JwtTestConfig.tokenFor(holder, "adjuster_" + level.toLowerCase()),
                "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                        + amount + ",\"rationale\":\"" + rationale + "\"}");
        assertEquals(200, decision.statusCode(), decision.body());
    }

    private void escalateAboveL2(String claimNumber) throws Exception {
        String holder = jdbcTemplate.queryForObject(
                "SELECT a.keycloak_sub FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        String level = jdbcTemplate.queryForObject(
                "SELECT a.level FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        HttpResponse<String> escalation = postJson("/api/claims/" + claimNumber + "/decision",
                JwtTestConfig.tokenFor(holder, "adjuster_" + level.toLowerCase()),
                "{\"decision\":\"APPROVED\",\"indemnityAmount\":"
                        + ABOVE_L2 + ",\"rationale\":\"Exceptional loss.\"}");
        assertEquals(200, escalation.statusCode(), escalation.body());
        assertEquals("ESCALATED_SUPERVISOR", jdbcTemplate.queryForObject(
                "SELECT status FROM claim WHERE claim_number = ?", String.class, claimNumber));
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

    private String assignedTo(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT a.display_name FROM claim c JOIN app_user a ON a.id = c.assigned_adjuster_id "
                        + "WHERE c.claim_number = ?", String.class, claimNumber);
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
                + "VALUES (?, ?, ?, ?)", "10000000-0000-0000-0000-000000000006",
                "Rahul Singh", "rahul.singh@claims.test", "L2");
    }

    /** Removes all L1 adjusters so an FNOL has no one to assign to; pair with restore. */
    private void appUsersDeleteL1s() {
        jdbcTemplate.update("DELETE FROM app_user WHERE level = 'L1'");
    }

    /** Restores the seeded L1 adjusters (fresh ids, same identities as the seeds). */
    private void appUsersRestoreL1s() {
        jdbcTemplate.update("INSERT INTO app_user (keycloak_sub, display_name, email, level) "
                + "VALUES (?, ?, ?, ?)", SUB_L1_ONE, "Priya Sharma",
                "priya.sharma@claims.test", "L1");
        jdbcTemplate.update("INSERT INTO app_user (keycloak_sub, display_name, email, level) "
                + "VALUES (?, ?, ?, ?)", SUB_L1_TWO, "Marcus Webb",
                "marcus.webb@claims.test", "L1");
        jdbcTemplate.update("INSERT INTO app_user (keycloak_sub, display_name, email, level) "
                + "VALUES (?, ?, ?, ?)", "10000000-0000-0000-0000-000000000005",
                "Aisha Verma", "aisha.verma@claims.test", "L1");
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
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

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }

    private String mailpitUrl() {
        return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
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
