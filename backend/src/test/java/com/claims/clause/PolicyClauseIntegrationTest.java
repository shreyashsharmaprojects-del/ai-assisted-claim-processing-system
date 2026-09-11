package com.claims.clause;

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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import com.claims.TestcontainersConfiguration;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

/**
 * V28 (V4 S1) acceptance: the policy-clause reference read path over real HTTP
 * against real Postgres. Loss-date clause selection in both directions round
 * the 2024-04-01 MATERNITY 7.1 supersession (270d -&gt; 180d), claim-cover
 * scoping of the claim view, the auth matrix (stranger 404 / claimant 403 /
 * anonymous 401 / assignee+supervisor 200), the browsable catalogue, and the
 * HLTH-ORPHAN seed smoke.
 *
 * <p>Style mirrors {@code StagedWorkflowIntegrationTest}: real HTTP via
 * {@link HttpClient}, tokens from {@link JwtTestConfig}, claims filed via FNOL
 * on the HLTH-PLUS seed policy POL-30005 (which carries a MATERNITY cover).
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PolicyClauseIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----PolicyClauseBoundary7";

    private static final String HLTH_PLUS_POLICY = "POL-30005";
    private static final String HLTH_PLUS_HOLDER = "Arjun Nair";
    private static final String HLTH_PLUS_EMAIL = "arjun.nair@example.test";
    private static final String MATERNITY_COVERS =
            "[{\"coverCode\":\"MATERNITY\",\"claimedAmount\":50000}]";

    private static final String SUB_SUPERVISOR = "10000000-0000-0000-0000-000000000004";
    private static final String STRANGER_L1_A = "10000000-0000-0000-0000-000000000001";
    private static final String STRANGER_L1_B = "10000000-0000-0000-0000-000000000002";

    private static final AtomicInteger CLAIMANT_SEQ = new AtomicInteger(0);

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    // --- 1. maternity loss-date selection, both directions ---------------------

    @Test
    void maternityLossBeforeCutoverSees270DayRule() throws Exception {
        String claimNumber = fileMaternityFnol("2024-03-15");
        HttpResponse<String> response = get("/api/claims/" + claimNumber + "/policy-clauses",
                assigneeBearer(claimNumber));
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"clauseRef\":\"7.1\""), response.body());
        assertTrue(response.body().contains("\"waitingPeriodDays\":270"), response.body());
        assertFalse(response.body().contains("\"waitingPeriodDays\":180"),
                "pre-cutover wording must be the 270-day rule: " + response.body());
    }

    @Test
    void maternityLossAfterCutoverSees180DayRule() throws Exception {
        String claimNumber = fileMaternityFnol("2024-06-01");
        HttpResponse<String> response = get("/api/claims/" + claimNumber + "/policy-clauses",
                assigneeBearer(claimNumber));
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"clauseRef\":\"7.1\""), response.body());
        assertTrue(response.body().contains("\"waitingPeriodDays\":180"), response.body());
        assertFalse(response.body().contains("\"waitingPeriodDays\":270"),
                "post-cutover wording must be the 180-day rule: " + response.body());
    }

    // --- 2. claim-cover scoping -------------------------------------------------

    @Test
    void claimViewHoldsProductLevelPlusOwnCoversOnly() throws Exception {
        String claimNumber = fileMaternityFnol("2024-06-01");
        HttpResponse<String> response = get("/api/claims/" + claimNumber + "/policy-clauses",
                assigneeBearer(claimNumber));
        assertEquals(200, response.statusCode(), response.body());
        // Own cover present ...
        assertTrue(response.body().contains("\"coverCode\":\"MATERNITY\""), response.body());
        // ... product-level rows present (e.g. the 1.1 hospital definition) ...
        assertTrue(response.body().contains("\"coverCode\":null"), response.body());
        assertTrue(response.body().contains("\"clauseRef\":\"1.1\""), response.body());
        // ... and no other cover's clauses leak in.
        for (String foreign : new String[] {"HOSPITALIZATION", "ROOM_RENT", "DAYCARE", "OPD"}) {
            assertFalse(response.body().contains("\"coverCode\":\"" + foreign + "\""),
                    "MATERNITY-only claim must not contain " + foreign + " clauses: "
                            + response.body());
        }
    }

    // --- 3. auth matrix ----------------------------------------------------------

    @Test
    void claimViewAuthMatrix() throws Exception {
        String claimNumber = fileMaternityFnol("2024-06-01");
        String path = "/api/claims/" + claimNumber + "/policy-clauses";

        // Stranger adjuster (assignee is someone else): 404, never revealing the claim.
        String assigneeSub = assigneeSubOf(claimNumber);
        String strangerSub = STRANGER_L1_A.equals(assigneeSub) ? STRANGER_L1_B : STRANGER_L1_A;
        assertEquals(404, get(path,
                JwtTestConfig.tokenFor(strangerSub, "adjuster_l1")).statusCode());

        // Claimant: 403 at the URL role gate.
        assertEquals(403, get(path,
                JwtTestConfig.tokenFor("sub-claimant-clause-x", "claimant")).statusCode());

        // Unauthenticated: 401.
        assertEquals(401, get(path, null).statusCode());

        // Supervisor and assignee: 200.
        assertEquals(200, get(path, supervisorBearer()).statusCode());
        assertEquals(200, get(path, assigneeBearer(claimNumber)).statusCode());

        // Unknown claim as supervisor: 404.
        assertEquals(404, get("/api/claims/NO-SUCH-CLAIM/policy-clauses",
                supervisorBearer()).statusCode());
    }

    // --- 4. catalogue -------------------------------------------------------------

    @Test
    void catalogueNarrowsToCoverPlusProductLevel() throws Exception {
        String adjuster = JwtTestConfig.tokenFor(
                "10000000-0000-0000-0000-000000000001", "adjuster_l1");
        HttpResponse<String> response = get(
                "/api/clauses?productCode=HLTH-PLUS&coverCode=MATERNITY", adjuster);
        assertEquals(200, response.statusCode(), response.body());
        // Both generations of 7.1 (the catalogue is not loss-date filtered) ...
        assertTrue(response.body().contains("\"waitingPeriodDays\":270"), response.body());
        assertTrue(response.body().contains("\"waitingPeriodDays\":180"), response.body());
        // ... the MATERNITY rows plus the product-level rows ...
        assertTrue(response.body().contains("\"coverCode\":\"MATERNITY\""), response.body());
        assertTrue(response.body().contains("\"coverCode\":null"), response.body());
        // ... and no other cover's rows.
        for (String foreign : new String[] {"HOSPITALIZATION", "ROOM_RENT", "DAYCARE", "OPD"}) {
            assertFalse(response.body().contains("\"coverCode\":\"" + foreign + "\""),
                    "MATERNITY-narrowed catalogue must not contain " + foreign + ": "
                            + response.body());
        }

        // Unknown product: 200 with an empty list, never a 404.
        HttpResponse<String> unknown = get("/api/clauses?productCode=NO-SUCH-PRODUCT", adjuster);
        assertEquals(200, unknown.statusCode(), unknown.body());
        assertEquals("[]", unknown.body().trim());

        // Blank product code: 400.
        assertEquals(400, get("/api/clauses?productCode=", adjuster).statusCode());

        // Missing productCode: 400.
        assertEquals(400, get("/api/clauses", adjuster).statusCode());

        // Claimant: 403 at the URL role gate.
        assertEquals(403, get("/api/clauses?productCode=HLTH-PLUS",
                JwtTestConfig.tokenFor("sub-claimant-clause-x", "claimant")).statusCode());

        // Unauthenticated: 401.
        assertEquals(401, get("/api/clauses?productCode=HLTH-PLUS", null).statusCode());
    }

    // --- 5. orphan seed smoke ------------------------------------------------------

    @Test
    void orphanCatalogueReturnsThreeCoverlessRows() throws Exception {
        String adjuster = JwtTestConfig.tokenFor(
                "10000000-0000-0000-0000-000000000001", "adjuster_l1");
        HttpResponse<String> response = get("/api/clauses?productCode=HLTH-ORPHAN", adjuster);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"clauseRef\":\"6.1\""), response.body());
        assertTrue(response.body().contains("\"clauseRef\":\"2.1\""), response.body());
        assertTrue(response.body().contains("\"clauseRef\":\"1.1\""), response.body());
        assertEquals(3, countOccurrences(response.body(), "\"coverCode\":null"), response.body());
        assertFalse(response.body().contains("\"coverCode\":\""),
                "orphan rows stay cover-less: " + response.body());
    }

    // --- helpers -------------------------------------------------------------------

    private String fileMaternityFnol(String lossDate) throws Exception {
        String claimantSub = "sub-claimant-clause-" + CLAIMANT_SEQ.incrementAndGet();
        Map<String, String> fields = new HashMap<>();
        fields.put("policyNumber", HLTH_PLUS_POLICY);
        fields.put("holderName", HLTH_PLUS_HOLDER);
        fields.put("holderEmail", HLTH_PLUS_EMAIL);
        fields.put("lossDate", lossDate);
        fields.put("lossLocation", "Mumbai");
        fields.put("lossDescription", "Maternity admission for the clause read path.");
        fields.put("covers", MATERNITY_COVERS);
        HttpResponse<String> response = post("/api/claims",
                JwtTestConfig.tokenFor(claimantSub, "claimant"), multipart(fields));
        assertEquals(201, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
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
