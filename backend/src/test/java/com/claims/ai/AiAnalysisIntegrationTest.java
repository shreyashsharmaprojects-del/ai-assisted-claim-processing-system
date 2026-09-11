package com.claims.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import com.claims.TestcontainersConfiguration;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

/**
 * V30 (V4 S2) acceptance: the DECISION-stage AI advisory surface over real HTTP
 * against real Postgres. No live provider runs here — {@code DEEPSEEK_API_KEY}
 * is blank in this box, so every POST takes the deterministic rules-only
 * fallback and must assert exactly that: {@code DEGRADED} / {@code rules-only}.
 *
 * <p>Style mirrors {@code PolicyClauseIntegrationTest}: real HTTP via
 * {@link HttpClient}, tokens from {@link JwtTestConfig}, claims filed via FNOL
 * on the HLTH-PLUS seed policy POL-30005 (which carries a MATERNITY cover) and
 * driven to DECISION with the exact staged sequence from
 * {@code StagedWorkflowIntegrationTest#driveClaimToDecision} (review ADVANCE,
 * DIGITAL verification, full checklist COMPLETE, assessment with
 * expectedVersion).
 *
 * <p>Orchestrator ruling (kept here so the assertions read as intentional, not
 * accidental): the HTTP-level DEGRADED suggestion for HLTH-PLUS/MATERNITY is
 * {@code NEEDS_MORE_INFO} with a null payable — NOT the 90000→75000 sub-limit
 * cap — because (1) the assessment guard
 * ({@code StagedWorkflowService}, assessed &gt; policy sub-limit is a 400)
 * means 90000 on the 75000 MATERNITY cover never reaches DECISION, and
 * (2) {@code CoverageRules} Rule 1 fires first whenever any waiting-period
 * clause is served, and HLTH-PLUS always serves them (product-level 3.1 plus
 * MATERNITY 7.1). Rule-1-first is the conservative-correct design (waiting
 * periods need the inception date the snapshot deliberately omits). The
 * sub-limit cap (90000→75000) and the 10% co-pay chain (→67500.00) are covered
 * as pure-unit {@code CoverageRules} tests in {@code AiOutputValidatorTest},
 * where hand-built clause inputs can isolate the cap path.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiAnalysisIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----AiAnalysisBoundary1";

    private static final String HLTH_PLUS_POLICY = "POL-30005";
    private static final String HLTH_PLUS_HOLDER = "Arjun Nair";
    private static final String HLTH_PLUS_EMAIL = "arjun.nair@example.test";
    private static final String MATERNITY_COVERS =
            "[{\"coverCode\":\"MATERNITY\",\"claimedAmount\":50000}]";

    private static final String SUB_SUPERVISOR = "10000000-0000-0000-0000-000000000004";
    private static final String STRANGER_L1_A = "10000000-0000-0000-0000-000000000001";
    private static final String STRANGER_L1_B = "10000000-0000-0000-0000-000000000002";

    private static final AtomicInteger CLAIMANT_SEQ = new AtomicInteger(0);
    private static final AtomicInteger LOSS_DAY = new AtomicInteger(0);

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * {@code claim_ai_analysis} is per-claim snapshot state, not reference data.
     * The base reset is a final method with a fixed truncate list, so the extra
     * table is truncated here in this class's own lifecycle hook (runs after the
     * base reset: superclass {@code @BeforeEach} first, then this one).
     */
    @BeforeEach
    void truncateAiSnapshotsBetweenTests() {
        jdbcTemplate.execute("TRUNCATE claim_ai_analysis RESTART IDENTITY CASCADE");
    }

    // --- 1. pre-DECISION POST ---------------------------------------------------

    @Test
    void preDecisionPostIsRejectedNamingDecisionStage() throws Exception {
        String claimNumber = fileMaternityFnol();
        String path = "/api/claims/" + claimNumber + "/ai-analysis";

        HttpResponse<String> response = postEmpty(path, assigneeBearer(claimNumber));
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().toLowerCase().contains("decision stage"),
                "the 400 must name the decision stage: " + response.body());
    }

    // --- 2. DECISION POST: DEGRADED rules-only -----------------------------------

    @Test
    void decisionPostReturnsDegradedRulesFallbackWithAudit() throws Exception {
        String claimNumber = fileMaternityFnol();
        String bearer = assigneeBearer(claimNumber);
        driveClaimToDecision(claimNumber, bearer);
        String path = "/api/claims/" + claimNumber + "/ai-analysis";

        HttpResponse<String> response = postEmpty(path, bearer);
        assertEquals(200, response.statusCode(), response.body());
        String body = response.body();

        assertTrue(body.contains("\"status\":\"DEGRADED\""), body);
        assertTrue(body.contains("\"model\":\"rules-only\""), body);

        // Exactly the claim's covers: one MATERNITY suggestion, no more, no less.
        assertEquals(1, countOccurrences(body, "\"coverCode\":\"MATERNITY\""), body);

        // Per the orchestrator ruling: HLTH-PLUS always serves waiting-period
        // clauses (3.1 product-level + MATERNITY 7.1), so Rule 1 fires first and
        // the suggestion is NEEDS_MORE_INFO with a null payable — the assessed
        // figure (50000, within the 75000 sub-limit) never reaches the cap path.
        assertTrue(body.contains("\"recommendation\":\"NEEDS_MORE_INFO\""), body);
        assertTrue(body.contains("\"estimatedPayable\":null"), body);

        // Provider outcome is recorded, not hidden.
        assertTrue(body.contains("provider not configured"), body);
        assertFalse(body.contains("\"errorMessage\":null"),
                "errorMessage must be non-blank: " + body);

        // One AI_ANALYSIS_REQUESTED audit row for the claim.
        assertEquals(1, count(
                "SELECT count(*) FROM audit_log WHERE action = 'AI_ANALYSIS_REQUESTED' "
                        + "AND entity_id = ?",
                idOf(claimNumber)), "exactly one audit row per first computation");
    }

    // --- 3. dedupe replay ---------------------------------------------------------

    @Test
    void secondPostReplaysStoredRowWithoutNewAudit() throws Exception {
        String claimNumber = fileMaternityFnol();
        String bearer = assigneeBearer(claimNumber);
        driveClaimToDecision(claimNumber, bearer);
        String path = "/api/claims/" + claimNumber + "/ai-analysis";

        HttpResponse<String> first = postEmpty(path, bearer);
        assertEquals(200, first.statusCode(), first.body());
        HttpResponse<String> second = postEmpty(path, bearer);
        assertEquals(200, second.statusCode(), second.body());

        assertEquals(firstId(first.body()), firstId(second.body()),
                "replay must return the stored row id");
        assertTrue(second.body().contains("\"status\":\"DEGRADED\""), second.body());
        assertEquals(claimVersion(first.body()), claimVersion(second.body()),
                "replay must carry the same claim version");
        assertEquals(1, count(
                "SELECT count(*) FROM audit_log WHERE action = 'AI_ANALYSIS_REQUESTED' "
                        + "AND entity_id = ?",
                idOf(claimNumber)), "replay must not write a second audit row");
    }

    // --- 4. auth matrix -------------------------------------------------------------

    @Test
    void aiAnalysisAuthMatrix() throws Exception {
        String claimNumber = fileMaternityFnol();
        driveClaimToDecision(claimNumber, assigneeBearer(claimNumber));
        String path = "/api/claims/" + claimNumber + "/ai-analysis";

        // Stranger adjuster (assignee is someone else): 404, never revealing the claim.
        String assigneeSub = assigneeSubOf(claimNumber);
        String strangerSub = STRANGER_L1_A.equals(assigneeSub) ? STRANGER_L1_B : STRANGER_L1_A;
        String stranger = JwtTestConfig.tokenFor(strangerSub, "adjuster_l1");
        assertEquals(404, postEmpty(path, stranger).statusCode());
        assertEquals(404, get(path, stranger).statusCode());

        // Claimant: 403 at the URL role gate.
        String claimant = JwtTestConfig.tokenFor(
                "sub-claimant-ai-" + CLAIMANT_SEQ.incrementAndGet(), "claimant");
        assertEquals(403, postEmpty(path, claimant).statusCode());
        assertEquals(403, get(path, claimant).statusCode());

        // Unauthenticated: 401.
        assertEquals(401, postEmpty(path, null).statusCode());
        assertEquals(401, get(path, null).statusCode());

        // Supervisor: 200 on both verbs (POST computes, GET replays the row).
        HttpResponse<String> posted = postEmpty(path, supervisorBearer());
        assertEquals(200, posted.statusCode(), posted.body());
        assertEquals(200, get(path, supervisorBearer()).statusCode());

        // Assignee: 200 (replays the supervisor's stored row for the same version).
        assertEquals(200, get(path, assigneeBearer(claimNumber)).statusCode());

        // Unknown claim as supervisor: 404, never a 403 or an empty 200.
        assertEquals(404, get("/api/claims/NO-SUCH-CLAIM/ai-analysis",
                supervisorBearer()).statusCode());
    }

    // --- 5. GET latest: 404 before, stored row after ---------------------------------

    @Test
    void getLatestIs404BeforePostAndStoredRowAfter() throws Exception {
        String claimNumber = fileMaternityFnol();
        String bearer = assigneeBearer(claimNumber);
        driveClaimToDecision(claimNumber, bearer);
        String path = "/api/claims/" + claimNumber + "/ai-analysis";

        HttpResponse<String> before = get(path, bearer);
        assertEquals(404, before.statusCode(), before.body());

        HttpResponse<String> posted = postEmpty(path, bearer);
        assertEquals(200, posted.statusCode(), posted.body());

        HttpResponse<String> after = get(path, bearer);
        assertEquals(200, after.statusCode(), after.body());
        assertEquals(firstId(posted.body()), firstId(after.body()),
                "GET latest must return the stored row");
    }

    // --- helpers ----------------------------------------------------------------------

    private String fileMaternityFnol() throws Exception {
        String claimantSub = "sub-claimant-ai-" + CLAIMANT_SEQ.incrementAndGet();
        // Unique loss date per filing: the duplicate guard keys on policy + loss
        // date + cover set within 24h. Post-cutover (180-day 7.1 wording served —
        // either generation yields Rule 1 NEEDS_MORE_INFO, so the exact day is
        // immaterial to the assertions).
        String lossDate = "2024-06-" + String.format("%02d",
                10 + LOSS_DAY.getAndIncrement() % 10);
        Map<String, String> fields = new HashMap<>();
        fields.put("policyNumber", HLTH_PLUS_POLICY);
        fields.put("holderName", HLTH_PLUS_HOLDER);
        fields.put("holderEmail", HLTH_PLUS_EMAIL);
        fields.put("lossDate", lossDate);
        fields.put("lossLocation", "Mumbai");
        fields.put("lossDescription", "Maternity admission for the AI advisory path.");
        fields.put("covers", MATERNITY_COVERS);
        HttpResponse<String> response = post("/api/claims",
                JwtTestConfig.tokenFor(claimantSub, "claimant"), multipart(fields));
        assertEquals(201, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    /**
     * The exact drive sequence from {@code StagedWorkflowIntegrationTest}: review
     * ADVANCE, one DIGITAL verification, the whole checklist COMPLETE, then the
     * assessment (single MATERNITY cover, assessed 50000 — within both the
     * claimed 50000 and the 75000 policy sub-limit, so the claim lands at
     * DECISION instead of tripping the assessment guard).
     */
    private void driveClaimToDecision(String claimNumber, String bearer) throws Exception {
        assertEquals(200, postJson("/api/claims/" + claimNumber + "/review", bearer,
                "{\"action\":\"ADVANCE\",\"rationale\":\"Verifying.\"}").statusCode());
        HttpResponse<String> created = postJson(
                "/api/claims/" + claimNumber + "/verifications", bearer,
                "{\"type\":\"DIGITAL\",\"notes\":\"Checking records.\"}");
        assertEquals(200, created.statusCode(), created.body());
        long verificationId = Long.parseLong(
                created.body().replaceAll(".*\"id\":(\\d+).*", "$1"));
        HttpResponse<String> completed = putJson(
                "/api/claims/" + claimNumber + "/verifications/" + verificationId, bearer,
                "{\"status\":\"COMPLETE\",\"outcome\":\"PASSED\",\"notes\":\"Match.\"}");
        assertEquals(200, completed.statusCode(), completed.body());
        completeAllVerifications(claimNumber, bearer);
        String body = "{\"rationale\":\"Bills verified.\",\"covers\":["
                + "{\"coverCode\":\"MATERNITY\",\"assessedAmount\":50000}],"
                + "\"expectedVersion\":" + versionOf(claimNumber) + "}";
        HttpResponse<String> assessed = putJson(
                "/api/claims/" + claimNumber + "/assessment", bearer, body);
        assertEquals(200, assessed.statusCode(), assessed.body());
    }

    private void completeAllVerifications(String claimNumber, String bearer)
            throws Exception {
        String staged = get("/api/claims/" + claimNumber + "/staged", bearer).body();
        Matcher matcher = Pattern.compile(
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

    private String assigneeSubOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT a.keycloak_sub FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
    }

    private String assigneeBearer(String claimNumber) {
        String level = jdbcTemplate.queryForObject(
                "SELECT a.level FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        return JwtTestConfig.tokenFor(assigneeSubOf(claimNumber),
                "adjuster_" + level.toLowerCase());
    }

    private String supervisorBearer() {
        return JwtTestConfig.tokenFor(SUB_SUPERVISOR, "supervisor");
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static long firstId(String body) {
        Matcher matcher = Pattern.compile("\"id\":(\\d+)").matcher(body);
        assertTrue(matcher.find(), "expected an \"id\" in: " + body);
        return Long.parseLong(matcher.group(1));
    }

    private static long claimVersion(String body) {
        Matcher matcher = Pattern.compile("\"claimVersion\":(\\d+)").matcher(body);
        assertTrue(matcher.find(), "expected a \"claimVersion\" in: " + body);
        return Long.parseLong(matcher.group(1));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) != -1) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postEmpty(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port() + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
                out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + field.getKey()
                        + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write((field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }
}
