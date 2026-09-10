/**
 * V23 (V3 S8): structured decisions + audit export over real HTTP against real
 * Postgres. Short-rationale 400, codeless-reject 400 (naming the cover), valid
 * close persisting denial codes, both exports as text/csv with headers + rows
 * + attachment disposition, and the claimant wall (remarks visible, codes never).
 */
package com.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StructuredDecisionIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----StructuredDecisionBoundary1";

    private static final String SUB_SUPERVISOR = "10000000-0000-0000-0000-000000000004";
    private static final String CLAIMANT = "sub-claimant-structured";

    private static final String GOOD_RATIONALE =
            "Bills verified against the submitted documents; closing now.";

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void shortRationaleClosureIs400AndLeavesTheClaimOpen() throws Exception {
        String claimNumber = driveToDecision();
        String bearer = holderBearer(claimNumber);

        HttpResponse<String> decided = postJson(
                "/api/claims/" + claimNumber + "/cover-decision", bearer,
                "{\"rationale\":\"too short\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":80000,\"remarks\":\"Bills verified.\"},"
                        + "{\"coverCode\":\"OPD\",\"decision\":\"REJECTED\","
                        + "\"remarks\":\"Pre-dates the waiting period.\","
                        + "\"denialReason\":\"NOT_COVERED\"}]}");
        assertEquals(400, decided.statusCode(), decided.body());
        assertTrue(decided.body().contains("at least 20 characters"), decided.body());
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        assertEquals(0, count("SELECT count(*) FROM payment WHERE claim_id = ?",
                idOf(claimNumber)));
    }

    @Test
    void rejectWithoutDenialCodeIs400NamingTheCover() throws Exception {
        String claimNumber = driveToDecision();
        String bearer = holderBearer(claimNumber);

        HttpResponse<String> decided = postJson(
                "/api/claims/" + claimNumber + "/cover-decision", bearer,
                "{\"rationale\":\"" + GOOD_RATIONALE + "\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":80000,\"remarks\":\"Bills verified.\"},"
                        + "{\"coverCode\":\"OPD\",\"decision\":\"REJECTED\","
                        + "\"remarks\":\"Pre-dates the waiting period.\"}]}");
        assertEquals(400, decided.statusCode(), decided.body());
        assertTrue(decided.body().contains("OPD"), decided.body());
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        assertEquals(0, count("SELECT count(*) FROM payment WHERE claim_id = ?",
                idOf(claimNumber)));
    }

    @Test
    void unknownDenialCodeIs400NamingValidCodes() throws Exception {
        String claimNumber = driveToDecision();
        String bearer = holderBearer(claimNumber);

        HttpResponse<String> decided = postJson(
                "/api/claims/" + claimNumber + "/cover-decision", bearer,
                "{\"rationale\":\"" + GOOD_RATIONALE + "\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":80000,\"remarks\":\"Bills verified.\"},"
                        + "{\"coverCode\":\"OPD\",\"decision\":\"REJECTED\","
                        + "\"remarks\":\"Pre-dates the waiting period.\","
                        + "\"denialReason\":\"NO_SUCH_CODE\"}]}");
        assertEquals(400, decided.statusCode(), decided.body());
        assertTrue(decided.body().contains("NOT_COVERED"), decided.body());
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
    }

    @Test
    void validClosePersistsCodesAndExportsStayRegulatorReady() throws Exception {
        String claimNumber = driveToDecision();
        String bearer = holderBearer(claimNumber);

        HttpResponse<String> decided = postJson(
                "/api/claims/" + claimNumber + "/cover-decision", bearer,
                "{\"rationale\":\"" + GOOD_RATIONALE + "\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":80000,\"remarks\":\"Bills verified.\"},"
                        + "{\"coverCode\":\"OPD\",\"decision\":\"REJECTED\","
                        + "\"remarks\":\"Pre-dates the waiting period.\","
                        + "\"denialReason\":\"NOT_COVERED\"}]}");
        assertEquals(200, decided.statusCode(), decided.body());
        assertEquals("CLOSED", statusOf(claimNumber));
        assertEquals("NOT_COVERED", jdbcTemplate.queryForObject(
                "SELECT denial_reason FROM claim_cover WHERE claim_id = ? "
                        + "AND cover_code = 'OPD'",
                String.class, idOf(claimNumber)));

        // Audit export: text/csv + header + rows + attachment disposition.
        HttpResponse<String> audit = get(
                "/api/audit/export?claimNumber=" + claimNumber, supervisorBearer());
        assertEquals(200, audit.statusCode(), audit.body());
        assertTrue(contentTypeOf(audit).contains("text/csv"),
                "audit export is text/csv: " + contentTypeOf(audit));
        assertTrue(dispositionOf(audit).contains("attachment"), dispositionOf(audit));
        assertTrue(dispositionOf(audit).contains("audit-" + claimNumber + ".csv"),
                dispositionOf(audit));
        assertTrue(audit.body().contains("at,actor,action,before,after,rationale"),
                audit.body());
        assertTrue(audit.body().contains("DECISION"), audit.body());

        // Decisions export: closures in range as text/csv with the denial code.
        HttpResponse<String> closures = get(
                "/api/decisions/export?from=2020-01-01&to=2030-01-01",
                supervisorBearer());
        assertEquals(200, closures.statusCode(), closures.body());
        assertTrue(contentTypeOf(closures).contains("text/csv"),
                "decisions export is text/csv: " + contentTypeOf(closures));
        assertTrue(dispositionOf(closures).contains("attachment"),
                dispositionOf(closures));
        assertTrue(closures.body()
                .contains("claim,policy,product,aggregate,totals,decider,rationale,"
                        + "denialCodes"),
                closures.body());
        assertTrue(closures.body().contains(claimNumber), closures.body());
        assertTrue(closures.body().contains("OPD=NOT_COVERED"), closures.body());

        // Bad date format is a 400.
        assertEquals(400, get("/api/decisions/export?from=soon&to=2030-01-01",
                supervisorBearer()).statusCode());

        // Claimant wall: remarks visible, codes never.
        String claimantView = get("/api/claims/" + claimNumber,
                claimantBearer()).body();
        assertTrue(claimantView.contains("Pre-dates the waiting period."),
                claimantView);
        assertFalse(claimantView.contains("NOT_COVERED"), claimantView);
        assertFalse(claimantView.contains("denialReason"), claimantView);
        assertFalse(claimantView.contains("denialCodes"), claimantView);
    }

    @Test
    void exportsAreSupervisorOnlyAndUnknownClaimsAre404() throws Exception {
        String claimNumber = closeValid(driveToDecision());

        // Adjuster + claimant are 403 on both exports.
        assertEquals(403, get("/api/audit/export?claimNumber=" + claimNumber,
                holderBearer(claimNumber)).statusCode());
        assertEquals(403, get("/api/audit/export?claimNumber=" + claimNumber,
                claimantBearer()).statusCode());
        assertEquals(403, get("/api/decisions/export?from=2020-01-01&to=2030-01-01",
                claimantBearer()).statusCode());

        // An unknown claim is a 404, never a 403.
        assertEquals(404, get("/api/audit/export?claimNumber=CLM-999999",
                supervisorBearer()).statusCode());
    }

    // --- drivers -------------------------------------------------------------------

    private String closeValid(String claimNumber) throws Exception {
        String bearer = holderBearer(claimNumber);
        HttpResponse<String> decided = postJson(
                "/api/claims/" + claimNumber + "/cover-decision", bearer,
                "{\"rationale\":\"" + GOOD_RATIONALE + "\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":80000,\"remarks\":\"Bills verified.\"},"
                        + "{\"coverCode\":\"OPD\",\"decision\":\"REJECTED\","
                        + "\"remarks\":\"Pre-dates the waiting period.\","
                        + "\"denialReason\":\"NOT_COVERED\"}]}");
        assertEquals(200, decided.statusCode(), decided.body());
        return claimNumber;
    }

    private static final java.util.concurrent.atomic.AtomicInteger LOSS_DAY =
            new java.util.concurrent.atomic.AtomicInteger(20);

    private String fileCoverFnol() throws Exception {
        String lossDate = "2026-06-" + String.format("%02d",
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

    /** Files, advances, verifies, completes and assesses: returns a claim at DECISION. */
    private String driveToDecision() throws Exception {
        String claimNumber = fileCoverFnol();
        String bearer = holderBearer(claimNumber);
        assertEquals(200, postJson("/api/claims/" + claimNumber + "/review", bearer,
                "{\"action\":\"ADVANCE\",\"rationale\":\"Verifying the claim.\"}")
                .statusCode());
        HttpResponse<String> created = postJson(
                "/api/claims/" + claimNumber + "/verifications", bearer,
                "{\"type\":\"DIGITAL\",\"notes\":\"Checking records.\"}");
        assertEquals(200, created.statusCode(), created.body());
        completeAllVerifications(claimNumber, bearer);
        HttpResponse<String> assessed = putJson(
                "/api/claims/" + claimNumber + "/assessment", bearer,
                "{\"rationale\":\"Bills verified.\",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\","
                        + "\"assessedAmount\":180000},"
                        + "{\"coverCode\":\"OPD\",\"assessedAmount\":25000}],"
                        + "\"expectedVersion\":" + versionOf(claimNumber) + "}");
        assertEquals(200, assessed.statusCode(), assessed.body());
        return claimNumber;
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
                                + "\"notes\":\"Checked and verified.\"}");
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

    private String contentTypeOf(HttpResponse<String> response) {
        return response.headers().firstValue("content-type").orElse("");
    }

    private String dispositionOf(HttpResponse<String> response) {
        return response.headers().firstValue("content-disposition").orElse("");
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
