package com.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import com.claims.TestcontainersConfiguration;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

/**
 * V22 (V3 S6): supervisor reopen of a closed claim over real HTTP against real
 * Postgres. Closed → reopen (open at REVIEW, CLAIM_REOPENED audit, outbox PENDING,
 * assignee set) → re-decide (payment seq 2, claim totals reflect the latest
 * closure); guards (open-claim 400, short-rationale 400, adjuster 403,
 * unknown-claim 404, stale-version 409).
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClaimReopenIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----ClaimReopenBoundary1";

    private static final String SUB_SUPERVISOR = "10000000-0000-0000-0000-000000000004";
    private static final String CLAIMANT = "sub-claimant-reopen";

    private static final String RATIONALE =
            "New hospital evidence received after closure; re-examining.";

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    // --- the journey: close → reopen → re-decide -----------------------------------

    @Test
    void closedReopenThenRedecideKeepsPaymentHistoryWithLatestTotals() throws Exception {
        String claimNumber = fileCoverFnol();
        closeWithinAuthority(claimNumber);
        Long claimId = idOf(claimNumber);

        // First closure: payment seq 1 = Σ net (80000 − 10000 deductible).
        assertEquals(1, count("SELECT count(*) FROM payment WHERE claim_id = ?", claimId));
        assertEquals(1, intOf("SELECT seq FROM payment WHERE claim_id = ?", claimId));
        assertEquals(0, new java.math.BigDecimal("70000").compareTo(
                jdbcTemplate.queryForObject("SELECT amount FROM payment WHERE claim_id = ?",
                        java.math.BigDecimal.class, claimId)));

        // Supervisor reopens with a fresh version and a real rationale.
        HttpResponse<String> reopened = postJson("/api/claims/" + claimNumber + "/reopen",
                supervisorBearer(),
                "{\"rationale\":\"" + RATIONALE + "\",\"expectedVersion\":"
                        + versionOf(claimNumber) + "}");
        assertEquals(200, reopened.statusCode(), reopened.body());
        assertTrue(reopened.body().contains("\"status\":\"UNDER_REVIEW\""),
                reopened.body());
        assertTrue(reopened.body().contains("\"stage\":\"REVIEW\""), reopened.body());

        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        assertEquals("REVIEW", stageOf(claimNumber));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT decision FROM claim WHERE claim_number = ?", String.class,
                claimNumber));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT decision_remarks FROM claim WHERE claim_number = ?",
                String.class, claimNumber));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT closed_at FROM claim WHERE claim_number = ?",
                java.time.OffsetDateTime.class, claimNumber));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT assigned_adjuster_id FROM claim WHERE claim_number = ?",
                Long.class, claimNumber),
                "reopen reassigns through ClaimAssigner");
        assertEquals("L1", jdbcTemplate.queryForObject(
                "SELECT level FROM claim WHERE claim_number = ?", String.class,
                claimNumber),
                "reopen preserves the level");
        assertEquals(1, count("SELECT count(*) FROM audit_log "
                + "WHERE action = 'CLAIM_REOPENED' AND entity_id = ?", claimId));
        assertTrue(jdbcTemplate.queryForObject("SELECT rationale FROM audit_log "
                + "WHERE action = 'CLAIM_REOPENED' AND entity_id = ?", String.class,
                claimId).contains("New hospital evidence"),
                "the reopen audit row carries the rationale");
        assertTrue(jdbcTemplate.queryForObject("SELECT before::text FROM audit_log "
                + "WHERE action = 'CLAIM_REOPENED' AND entity_id = ?", String.class,
                claimId).contains("CLOSED"),
                "the reopen audit row records before=CLOSED");
        assertEquals(1, count("SELECT count(*) FROM email_outbox WHERE claim_id = ? "
                + "AND subject LIKE '%reopened%'", claimId),
                "the reopen notice is enqueued in the reopen transaction "
                        + "(the controller flushes it like every decision mail, "
                        + "so it may already be SENT where Mailpit is up)");
        assertTrue(jdbcTemplate.queryForObject("SELECT body FROM email_outbox "
                + "WHERE claim_id = ? AND subject LIKE '%reopened%'", String.class,
                claimId).contains("reopened for further review"),
                "the reopen notice carries the decision-mail tone");
        assertTrue(count("SELECT count(*) FROM verification WHERE claim_id = ?",
                claimId) > 0, "verification history is kept across reopen");
        String feed = get("/api/claims/" + claimNumber + "/timeline",
                holderBearer(claimNumber)).body();
        assertTrue(feed.contains("CLAIM_REOPENED"), feed);
        assertTrue(feed.contains("Reopened"), feed);
        assertTrue(feed.contains("New hospital evidence"), feed);

        // Re-work to a second closure with a different figure.
        String bearer = holderBearer(claimNumber);
        driveClaimToDecision(claimNumber, bearer);
        HttpResponse<String> redecided = postJson(
                "/api/claims/" + claimNumber + "/cover-decision", bearer,
                "{\"rationale\":\"Re-examined with new evidence.\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":90000,\"remarks\":\"Bills verified.\"},"
                        + "{\"coverCode\":\"OPD\",\"decision\":\"REJECTED\","
                        + "\"remarks\":\"Waiting period.\"}]}");
        assertEquals(200, redecided.statusCode(), redecided.body());
        assertEquals("CLOSED", statusOf(claimNumber));

        assertEquals(2, count("SELECT count(*) FROM payment WHERE claim_id = ?",
                claimId));
        assertEquals(2, intOf("SELECT max(seq) FROM payment WHERE claim_id = ?",
                claimId));
        assertEquals(0, new java.math.BigDecimal("80000").compareTo(
                jdbcTemplate.queryForObject("SELECT amount FROM payment "
                        + "WHERE claim_id = ? ORDER BY seq DESC LIMIT 1",
                        java.math.BigDecimal.class, claimId)),
                "the latest payment row carries the latest closure's net");
        assertEquals(0, new java.math.BigDecimal("90000").compareTo(
                jdbcTemplate.queryForObject(
                        "SELECT indemnity_amount FROM claim WHERE claim_number = ?",
                        java.math.BigDecimal.class, claimNumber)),
                "claim totals reflect the latest closure");
        assertEquals(2, count("SELECT count(*) FROM audit_log WHERE action = 'DECISION' "
                + "AND entity_id = ?", claimId));
    }

    // --- guards --------------------------------------------------------------------

    @Test
    void reopenOnAnOpenClaimIs400() throws Exception {
        String claimNumber = fileCoverFnol();
        driveClaimToDecision(claimNumber, holderBearer(claimNumber));
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));

        HttpResponse<String> reopened = postJson("/api/claims/" + claimNumber + "/reopen",
                supervisorBearer(),
                "{\"rationale\":\"" + RATIONALE + "\",\"expectedVersion\":"
                        + versionOf(claimNumber) + "}");
        assertEquals(400, reopened.statusCode(), reopened.body());
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        assertEquals(0, count("SELECT count(*) FROM audit_log "
                + "WHERE action = 'CLAIM_REOPENED' AND entity_id = ?", idOf(claimNumber)));
    }

    @Test
    void reopenWithAShortRationaleIs400() throws Exception {
        String claimNumber = closeWithinAuthority(fileCoverFnol());

        HttpResponse<String> reopened = postJson("/api/claims/" + claimNumber + "/reopen",
                supervisorBearer(),
                "{\"rationale\":\"too short\",\"expectedVersion\":"
                        + versionOf(claimNumber) + "}");
        assertEquals(400, reopened.statusCode(), reopened.body());
        assertEquals("CLOSED", statusOf(claimNumber));
        assertEquals(0, count("SELECT count(*) FROM audit_log "
                + "WHERE action = 'CLAIM_REOPENED' AND entity_id = ?", idOf(claimNumber)));

        HttpResponse<String> missing = postJson("/api/claims/" + claimNumber + "/reopen",
                supervisorBearer(),
                "{\"expectedVersion\":" + versionOf(claimNumber) + "}");
        assertEquals(400, missing.statusCode(), missing.body());
    }

    @Test
    void adjusterReopenIs403BeforeAnyClaimLogic() throws Exception {
        String claimNumber = closeWithinAuthority(fileCoverFnol());

        assertEquals(403, postJson("/api/claims/" + claimNumber + "/reopen",
                holderBearer(claimNumber),
                "{\"rationale\":\"" + RATIONALE + "\",\"expectedVersion\":"
                        + versionOf(claimNumber) + "}").statusCode());
        assertEquals("CLOSED", statusOf(claimNumber));
    }

    @Test
    void unknownClaimReopenIs404() throws Exception {
        assertEquals(404, postJson("/api/claims/CLM-999999/reopen", supervisorBearer(),
                "{\"rationale\":\"" + RATIONALE + "\",\"expectedVersion\":0}")
                .statusCode());
    }

    @Test
    void staleVersionReopenIs409ThenFreshVersionSucceeds() throws Exception {
        String claimNumber = fileCoverFnol();
        long openVersion = versionOf(claimNumber);
        closeWithinAuthority(claimNumber);
        assertTrue(versionOf(claimNumber) > openVersion,
                "closure must move the version so a stale reopen is detectable");

        // Stale version on an otherwise valid reopen: 409, never the guard 400.
        HttpResponse<String> stale = postJson("/api/claims/" + claimNumber + "/reopen",
                supervisorBearer(),
                "{\"rationale\":\"" + RATIONALE + "\",\"expectedVersion\":"
                        + openVersion + "}");
        assertEquals(409, stale.statusCode(), stale.body());
        assertTrue(stale.body().contains("\"error\":\"CONFLICT\""), stale.body());
        assertEquals("CLOSED", statusOf(claimNumber));

        // Fresh version reopens normally.
        HttpResponse<String> reopened = postJson("/api/claims/" + claimNumber + "/reopen",
                supervisorBearer(),
                "{\"rationale\":\"" + RATIONALE + "\",\"expectedVersion\":"
                        + versionOf(claimNumber) + "}");
        assertEquals(200, reopened.statusCode(), reopened.body());
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
    }

    // --- drivers -------------------------------------------------------------------

    private static final java.util.concurrent.atomic.AtomicInteger LOSS_DAY =
            new java.util.concurrent.atomic.AtomicInteger(10);

    private String fileCoverFnol() throws Exception {
        String lossDate = "2026-07-" + String.format("%02d",
                LOSS_DAY.getAndIncrement() % 27 + 1);
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        fields.put("policyNumber", "POL-10001");
        fields.put("holderName", "Ada Lovelace");
        fields.put("holderEmail", "ada.lovelace@example.test");
        fields.put("lossDate", lossDate);
        fields.put("lossLocation", "London");
        fields.put("lossDescription", "Hospital stay plus follow-up visits.");
        fields.put("covers",
                "[{\"coverCode\":\"HOSPITALIZATION\",\"claimedAmount\":200000},"
                        + "{\"coverCode\":\"OPD\",\"claimedAmount\":40000}]");
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(fields));
        assertEquals(201, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    /** Files are already filed by the caller: drives to DECISION and closes within authority. */
    private String closeWithinAuthority(String claimNumber) throws Exception {
        String bearer = holderBearer(claimNumber);
        driveClaimToDecision(claimNumber, bearer);
        HttpResponse<String> decided = postJson(
                "/api/claims/" + claimNumber + "/cover-decision", bearer,
                "{\"rationale\":\"Within authority.\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":80000,\"remarks\":\"Bills verified.\"},"
                        + "{\"coverCode\":\"OPD\",\"decision\":\"REJECTED\","
                        + "\"remarks\":\"Pre-dates the waiting period.\"}]}");
        assertEquals(200, decided.statusCode(), decided.body());
        assertEquals("CLOSED", statusOf(claimNumber));
        return claimNumber;
    }

    private void driveClaimToDecision(String claimNumber, String bearer) throws Exception {
        assertEquals(200, postJson("/api/claims/" + claimNumber + "/review", bearer,
                "{\"action\":\"ADVANCE\",\"rationale\":\"Verifying.\"}").statusCode());
        HttpResponse<String> created = postJson(
                "/api/claims/" + claimNumber + "/verifications", bearer,
                "{\"type\":\"DIGITAL\",\"notes\":\"Checking records.\"}");
        assertEquals(200, created.statusCode(), created.body());
        completeAllVerifications(claimNumber, bearer);
        String body = "{\"rationale\":\"Bills verified.\",\"covers\":["
                + "{\"coverCode\":\"HOSPITALIZATION\",\"assessedAmount\":180000},"
                + "{\"coverCode\":\"OPD\",\"assessedAmount\":25000}],"
                + "\"expectedVersion\":" + versionOf(claimNumber) + "}";
        HttpResponse<String> assessed = putJson(
                "/api/claims/" + claimNumber + "/assessment", bearer, body);
        assertEquals(200, assessed.statusCode(), assessed.body());
    }

    private void completeAllVerifications(String claimNumber, String bearer)
            throws Exception {
        String staged = get("/api/claims/" + claimNumber + "/staged", bearer).body();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(
                        "\"id\":(\\d+),\"type\":([^,]+),\"status\":\"([A-Z_]+)\"")
                        .matcher(staged);
        while (matcher.find()) {
            if (!"COMPLETE".equals(matcher.group(3))) {
                HttpResponse<String> completed = putJson("/api/claims/" + claimNumber
                        + "/verifications/" + matcher.group(1), bearer,
                        "{\"status\":\"COMPLETE\",\"outcome\":\"PASSED\","
                                + "\"notes\":\"Checked.\"}");
                assertEquals(200, completed.statusCode(), completed.body());
            }
        }
    }

    private Long idOf(String claimNumber) {
        return jdbcTemplate.queryForObject("SELECT id FROM claim WHERE claim_number = ?",
                Long.class, claimNumber);
    }

    private Long versionOf(String claimNumber) {
        return jdbcTemplate.queryForObject("SELECT version FROM claim WHERE claim_number = ?",
                Long.class, claimNumber);
    }

    private String statusOf(String claimNumber) {
        return jdbcTemplate.queryForObject("SELECT status FROM claim WHERE claim_number = ?",
                String.class, claimNumber);
    }

    private String stageOf(String claimNumber) {
        return jdbcTemplate.queryForObject("SELECT stage FROM claim WHERE claim_number = ?",
                String.class, claimNumber);
    }

    private int intOf(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

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

    private String claimantBearer() {
        return JwtTestConfig.tokenFor(CLAIMANT, "claimant");
    }

    private String supervisorBearer() {
        return JwtTestConfig.tokenFor(SUB_SUPERVISOR, "supervisor");
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

    private HttpResponse<String> putJson(String path, String bearer, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
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

    private static byte[] multipart(java.util.Map<String, String> fields) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (java.util.Map.Entry<String, String> field : fields.entrySet()) {
                out.write(("--" + BOUNDARY + "\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + field.getKey()
                        + "\"\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.write((field.getValue() + "\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            out.write(("--" + BOUNDARY + "--\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }
}
