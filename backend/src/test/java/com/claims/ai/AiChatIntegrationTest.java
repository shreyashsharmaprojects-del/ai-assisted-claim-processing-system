package com.claims.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import com.claims.TestcontainersConfiguration;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

/**
 * V4 S3 acceptance: the conversational AI chat surface over real HTTP against
 * real Postgres. No live provider runs here — {@code DEEPSEEK_API_KEY} is
 * blank in this box, so every POST takes the deterministic rules-only
 * fallback and must assert exactly that: {@code DEGRADED} / {@code rules-only}.
 *
 * <p>Style mirrors {@code AiAnalysisIntegrationTest}: real HTTP via
 * {@link HttpClient}, tokens from {@link JwtTestConfig}, claims filed via FNOL
 * on the HLTH-PLUS seed policy POL-30005. Unlike the DECISION-only advisory,
 * chat is deliberately stage-agnostic — the first test files FNOL and posts
 * WITHOUT driving any stages, proving there is no stage gate (and that the
 * claim is still at REVIEW afterwards).
 *
 * <p>Chat is stateless: nothing is persisted except one
 * {@code AI_CHAT_MESSAGE} audit row per call, so no snapshot cleanup is needed
 * (the base reset already truncates {@code claim_ai_analysis} anyway).
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiChatIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----AiChatBoundary1";

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

    // --- 1. chat at REVIEW (no stage gate) -----------------------------------------

    @Test
    void chatAtReviewReturnsDegradedRulesFallbackWithAudit() throws Exception {
        String claimNumber = fileMaternityFnol();
        String bearer = assigneeBearer(claimNumber);
        String path = "/api/claims/" + claimNumber + "/ai-chat";
        String question = "Is the maternity waiting period satisfied?";

        HttpResponse<String> response = postJson(path, bearer, chatBody("[]", question));
        assertEquals(200, response.statusCode(), response.body());
        String body = response.body();

        assertTrue(body.contains("\"status\":\"DEGRADED\""), body);
        assertTrue(body.contains("\"model\":\"rules-only\""), body);
        assertTrue(body.contains("provider not configured"), body);

        // Empty history + the new exchange: exactly the echoed question and
        // one assistant answer, in order.
        List<String> contents = contentsOf(body);
        assertEquals(2, contents.size(), body);
        assertEquals(question, contents.get(0));
        assertTrue(contents.get(1) != null && !contents.get(1).isBlank(),
                "assistant answer must be non-blank: " + body);
        assertTrue(contents.get(1).contains("AI chat is unavailable"), body);

        // Chat neither requires nor leaves a stage: the claim is untouched.
        assertEquals("REVIEW", jdbcTemplate.queryForObject(
                "SELECT stage FROM claim WHERE claim_number = ?", String.class,
                claimNumber));

        // One AI_CHAT_MESSAGE audit row for the claim.
        assertEquals(1, count(
                "SELECT count(*) FROM audit_log WHERE action = 'AI_CHAT_MESSAGE' "
                        + "AND entity_id = ?",
                idOf(claimNumber)), "exactly one audit row per chat call");
    }

    // --- 2. transcript round-trip ---------------------------------------------------

    @Test
    void transcriptRoundTripEchoesHistoryPlusNewExchange() throws Exception {
        String claimNumber = fileMaternityFnol();
        String bearer = assigneeBearer(claimNumber);
        String path = "/api/claims/" + claimNumber + "/ai-chat";

        String oldQ = "Is the maternity waiting period satisfied?";
        String oldA = "The prior answer mentioned waiting periods.";
        String followUp = "What documents are still missing?";
        String history = "[{\"role\":\"user\",\"content\":\"" + oldQ + "\"},"
                + "{\"role\":\"assistant\",\"content\":\"" + oldA + "\"}]";

        HttpResponse<String> response = postJson(path, bearer, chatBody(history, followUp));
        assertEquals(200, response.statusCode(), response.body());
        String body = response.body();

        assertTrue(body.contains("\"status\":\"DEGRADED\""), body);
        List<String> contents = contentsOf(body);
        assertEquals(4, contents.size(), body);
        assertEquals(oldQ, contents.get(0));
        assertEquals(oldA, contents.get(1));
        assertEquals(followUp, contents.get(2));
        assertTrue(contents.get(3) != null && !contents.get(3).isBlank(),
                "the new assistant answer must be non-blank: " + body);
        assertEquals(2, countOccurrences(body, "\"role\":\"user\""), body);
        assertEquals(2, countOccurrences(body, "\"role\":\"assistant\""), body);
    }

    // --- 3. validation ---------------------------------------------------------------

    @Test
    void chatValidationRejectsBlankLongAndBadRole() throws Exception {
        String claimNumber = fileMaternityFnol();
        String bearer = assigneeBearer(claimNumber);
        String path = "/api/claims/" + claimNumber + "/ai-chat";

        // Blank question.
        assertEquals(400, postJson(path, bearer,
                "{\"messages\":[],\"question\":\"   \"}").statusCode());

        // 2001-char question (limit is 2000).
        assertEquals(400, postJson(path, bearer,
                "{\"messages\":[],\"question\":\"" + "a".repeat(2001) + "\"}")
                .statusCode());

        // Bad role in history.
        assertEquals(400, postJson(path, bearer,
                "{\"messages\":[{\"role\":\"system\",\"content\":\"Be nice.\"}],"
                        + "\"question\":\"Hello?\"}").statusCode());

        // 21-entry history (limit is 20).
        StringBuilder longHistory = new StringBuilder("[");
        for (int i = 0; i < 21; i++) {
            if (i > 0) {
                longHistory.append(",");
            }
            longHistory.append("{\"role\":\"")
                    .append(i % 2 == 0 ? "user" : "assistant")
                    .append("\",\"content\":\"turn ").append(i).append("\"}");
        }
        longHistory.append("]");
        assertEquals(400, postJson(path, bearer, chatBody(
                longHistory.toString(), "Hello?")).statusCode());

        // 4001-char history content (limit is 4000).
        assertEquals(400, postJson(path, bearer,
                "{\"messages\":[{\"role\":\"user\",\"content\":\""
                        + "b".repeat(4001) + "\"}],\"question\":\"Hello?\"}")
                .statusCode());

        // Rejected calls write no audit rows.
        assertEquals(0, count(
                "SELECT count(*) FROM audit_log WHERE action = 'AI_CHAT_MESSAGE' "
                        + "AND entity_id = ?",
                idOf(claimNumber)), "400s must not audit");
    }

    // --- 4. auth matrix -----------------------------------------------------------------

    @Test
    void chatAuthMatrix() throws Exception {
        String claimNumber = fileMaternityFnol();
        String path = "/api/claims/" + claimNumber + "/ai-chat";
        String chat = chatBody("[]", "What is the claim stage?");

        // Stranger adjuster (assignee is someone else): 404, never revealing the claim.
        String assigneeSub = assigneeSubOf(claimNumber);
        String strangerSub = STRANGER_L1_A.equals(assigneeSub) ? STRANGER_L1_B : STRANGER_L1_A;
        String stranger = JwtTestConfig.tokenFor(strangerSub, "adjuster_l1");
        assertEquals(404, postJson(path, stranger, chat).statusCode());

        // Claimant: 403 at the URL role gate.
        String claimant = JwtTestConfig.tokenFor(
                "sub-claimant-chat-" + CLAIMANT_SEQ.incrementAndGet(), "claimant");
        assertEquals(403, postJson(path, claimant, chat).statusCode());

        // Unauthenticated: 401.
        assertEquals(401, postJson(path, null, chat).statusCode());

        // Supervisor: 200.
        HttpResponse<String> supervised = postJson(path, supervisorBearer(), chat);
        assertEquals(200, supervised.statusCode(), supervised.body());
        assertTrue(supervised.body().contains("\"status\":\"DEGRADED\""),
                supervised.body());
    }

    // --- 5. chat writes no advisory rows --------------------------------------------------

    @Test
    void chatDoesNotCreateAdvisoryRows() throws Exception {
        String claimNumber = fileMaternityFnol();
        String bearer = assigneeBearer(claimNumber);
        String path = "/api/claims/" + claimNumber + "/ai-chat";

        assertEquals(200, postJson(path, bearer,
                chatBody("[]", "First question about coverage?")).statusCode());
        assertEquals(200, postJson(path, bearer,
                chatBody("[]", "Second question about documents?")).statusCode());

        assertEquals(0, count("SELECT count(*) FROM claim_ai_analysis"),
                "chat is stateless — it must never write advisory snapshots");
    }

    // --- helpers ----------------------------------------------------------------------

    private String fileMaternityFnol() throws Exception {
        String claimantSub = "sub-claimant-chat-" + CLAIMANT_SEQ.incrementAndGet();
        // Unique loss date per filing: the duplicate guard keys on policy + loss
        // date + cover set within 24h.
        String lossDate = "2024-06-" + String.format("%02d",
                10 + LOSS_DAY.getAndIncrement() % 10);
        Map<String, String> fields = new HashMap<>();
        fields.put("policyNumber", HLTH_PLUS_POLICY);
        fields.put("holderName", HLTH_PLUS_HOLDER);
        fields.put("holderEmail", HLTH_PLUS_EMAIL);
        fields.put("lossDate", lossDate);
        fields.put("lossLocation", "Mumbai");
        fields.put("lossDescription", "Maternity admission for the AI chat path.");
        fields.put("covers", MATERNITY_COVERS);
        HttpResponse<String> response = post("/api/claims",
                JwtTestConfig.tokenFor(claimantSub, "claimant"), multipart(fields));
        assertEquals(201, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    private static String chatBody(String messagesJson, String question) {
        return "{\"messages\":" + messagesJson + ",\"question\":\"" + question + "\"}";
    }

    private static List<String> contentsOf(String body) {
        Matcher matcher = Pattern.compile("\"content\":\"([^\"]*)\"").matcher(body);
        List<String> out = new ArrayList<>();
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
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

    private Long idOf(String claimNumber) {
        return jdbcTemplate.queryForObject("SELECT id FROM claim WHERE claim_number = ?",
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
