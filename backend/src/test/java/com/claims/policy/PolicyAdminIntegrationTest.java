package com.claims.policy;

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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import com.claims.TestcontainersConfiguration;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

/**
 * R1 acceptance: supervisor policy admin. Real HTTP through the real Spring context
 * against a real PostgreSQL (Flyway V9 lays down status/created_at). Create (incl.
 * duplicate-409 and unknown-product-400), mixed-CSV import with per-row results, retire +
 * retired-FNOL rejection, admin list (newest first, paged), and the auth matrix.
 *
 * <p>Note: {@link ClaimTableResettingTest} truncates claim tables but never policy —
 * tests that insert policies clean up after themselves to keep the seed state.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PolicyAdminIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----PolicyAdminTestBoundary1";

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    // --- create -----------------------------------------------------------------

    @Test
    void supervisorCanCreateAPolicyThatFilesAndAssigns() throws Exception {
        HttpResponse<String> created = postJson("/api/policies", supervisorBearer(),
                "{\"policyNumber\":\"POL-90001\",\"productCode\":\"HOME\","
                        + "\"holderName\":\"Test Holder\",\"holderEmail\":\"test.holder@example.test\","
                        + "\"coverage\":{\"type\":\"home\",\"sum_insured\":1000}}");
        assertEquals(200, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"policyNumber\":\"POL-90001\""), created.body());
        assertTrue(created.body().contains("\"status\":\"ACTIVE\""), created.body());
        try {
            // The new policy is fileable: FNOL classifies (HOME→L1) and assigns.
            HttpResponse<String> fnol = postFnol("POL-90001", "Test Holder",
                    "test.holder@example.test");
            assertEquals(201, fnol.statusCode(), fnol.body());
            assertTrue(fnol.body().contains("\"status\":\"UNDER_REVIEW\""), fnol.body());
        } finally {
            deletePolicy("POL-90001");
        }
    }

    @Test
    void duplicatePolicyNumberIsA409WithACleanMessage() throws Exception {
        HttpResponse<String> created = postJson("/api/policies", supervisorBearer(),
                "{\"policyNumber\":\"POL-90002\",\"productCode\":\"AUTO\","
                        + "\"holderName\":\"Dup Holder\",\"holderEmail\":\"dup@example.test\"}");
        assertEquals(200, created.statusCode(), created.body());
        try {
            HttpResponse<String> duplicate = postJson("/api/policies", supervisorBearer(),
                    "{\"policyNumber\":\"pol-90002\",\"productCode\":\"AUTO\","
                            + "\"holderName\":\"Dup Holder\",\"holderEmail\":\"dup@example.test\"}");
            assertEquals(409, duplicate.statusCode(), duplicate.body());
            assertTrue(duplicate.body().contains("already exists"), duplicate.body());
            assertFalse(duplicate.body().contains("PSQLException"),
                    "no internals leak: " + duplicate.body());
        } finally {
            deletePolicy("POL-90002");
        }
    }

    @Test
    void unknownProductCodeIsA400NamingValidCodes() throws Exception {
        HttpResponse<String> response = postJson("/api/policies", supervisorBearer(),
                "{\"policyNumber\":\"POL-90003\",\"productCode\":\"BOAT\","
                        + "\"holderName\":\"Boat Holder\",\"holderEmail\":\"boat@example.test\"}");
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("HOME"), response.body());
        assertTrue(response.body().contains("AUTO"), response.body());
        assertTrue(response.body().contains("HLTH-PLUS"), response.body());
    }

    // --- import -----------------------------------------------------------------

    @Test
    void mixedCsvImportPersistsValidRowsAndReportsRowErrors() throws Exception {
        String csv = "policy_number,product_code,holder_name,holder_email,coverage\n"
                + "POL-90101,HOME,Import One,one@example.test,\"{\"\"a\"\": 1}\"\n"
                + "POL-90102,BOAT,Import Two,two@example.test,\n"
                + "POL-10001,HOME,Ada Lovelace,ada.lovelace@example.test,\n"
                + ",HOME,No Number,nonumber@example.test,\n";
        try {
            HttpResponse<String> response = postImport(csv);
            assertEquals(200, response.statusCode(), response.body());
            // Per-row results: row 1 ok; row 2 bad product; row 3 duplicate seed; row 4
            // missing number.
            assertTrue(response.body().contains("\"row\":1"), response.body());
            assertTrue(response.body().contains("\"ok\":true"), response.body());
            assertTrue(response.body().contains("Unknown product"), response.body());
            assertTrue(response.body().contains("already exists"), response.body());
            // Only the valid row persisted.
            assertEquals("ACTIVE", statusOf("POL-90101"));
            assertEquals(0L, countPolicy("POL-90102"));
        } finally {
            deletePolicy("POL-90101");
        }
    }

    @Test
    void overLimitImportIsRejected() throws Exception {
        StringBuilder csv = new StringBuilder(
                "policy_number,product_code,holder_name,holder_email,coverage\n");
        for (int i = 0; i < 501; i++) {
            csv.append("POL-OVER-").append(i).append(",HOME,Holder ").append(i)
                    .append(",holder").append(i).append("@example.test,\n");
        }
        HttpResponse<String> response = postImport(csv.toString());
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("500"), response.body());
        assertEquals(0L, countPolicy("POL-OVER-0"));
    }

    // --- retire + retired FNOL ---------------------------------------------------

    @Test
    void retireThenFnolIsRejectedWithThePolicyMismatchShape() throws Exception {
        HttpResponse<String> created = postJson("/api/policies", supervisorBearer(),
                "{\"policyNumber\":\"POL-90201\",\"productCode\":\"HOME\","
                        + "\"holderName\":\"Retire Me\",\"holderEmail\":\"retire@example.test\"}");
        assertEquals(200, created.statusCode(), created.body());
        try {
            HttpResponse<String> retired = post("/api/policies/POL-90201/retire",
                    supervisorBearer());
            assertEquals(200, retired.statusCode(), retired.body());
            assertTrue(retired.body().contains("\"status\":\"RETIRED\""), retired.body());

            // FNOL against the retired policy: same shape as an unknown policy (404 +
            // the mismatch message), no new claimant-visible branch.
            HttpResponse<String> fnol = postFnol("POL-90201", "Retire Me",
                    "retire@example.test");
            assertEquals(404, fnol.statusCode(), fnol.body());
            assertTrue(fnol.body().contains("could not match"), fnol.body());
        } finally {
            deletePolicy("POL-90201");
        }
    }

    @Test
    void retiredPoliciesStayReadableForHistory() throws Exception {
        HttpResponse<String> created = postJson("/api/policies", supervisorBearer(),
                "{\"policyNumber\":\"POL-90202\",\"productCode\":\"HOME\","
                        + "\"holderName\":\"History Holder\",\"holderEmail\":\"history@example.test\"}");
        assertEquals(200, created.statusCode(), created.body());
        try {
            post("/api/policies/POL-90202/retire", supervisorBearer());
            HttpResponse<String> admin = getResponse("/api/policies/admin?status=RETIRED",
                    supervisorBearer());
            assertEquals(200, admin.statusCode(), admin.body());
            assertTrue(admin.body().contains("POL-90202"), admin.body());
        } finally {
            deletePolicy("POL-90202");
        }
    }

    // --- admin list ---------------------------------------------------------------

    @Test
    void adminListIsNewestFirstAndPaginated() throws Exception {
        postJson("/api/policies", supervisorBearer(),
                "{\"policyNumber\":\"POL-90301\",\"productCode\":\"HOME\","
                        + "\"holderName\":\"Old Holder\",\"holderEmail\":\"old@example.test\"}");
        postJson("/api/policies", supervisorBearer(),
                "{\"policyNumber\":\"POL-90302\",\"productCode\":\"HOME\","
                        + "\"holderName\":\"New Holder\",\"holderEmail\":\"new@example.test\"}");
        try {
            HttpResponse<String> page = getResponse("/api/policies/admin?size=1",
                    supervisorBearer());
            assertEquals(200, page.statusCode(), page.body());
            assertTrue(page.body().contains("\"content\""), page.body());
            assertTrue(page.body().contains("\"totalElements\""), page.body());
            assertTrue(page.body().contains("POL-90302"),
                    "newest first: " + page.body());
            assertFalse(page.body().contains("POL-90301"),
                    "size=1 pages the older row out: " + page.body());
        } finally {
            deletePolicy("POL-90301");
            deletePolicy("POL-90302");
        }
    }

    // --- auth matrix ---------------------------------------------------------------

    @Test
    void policyAdminEndpointsAreSupervisorOnly() throws Exception {
        String createBody = "{\"policyNumber\":\"POL-90401\",\"productCode\":\"HOME\","
                + "\"holderName\":\"Auth Holder\",\"holderEmail\":\"auth@example.test\"}";
        String csv = "policy_number,product_code,holder_name,holder_email,coverage\n";

        // Anonymous → 401 on all four.
        assertEquals(401, postJsonStatus("/api/policies", null, createBody));
        assertEquals(401, postImportStatus(csv, null));
        assertEquals(401, postStatus("/api/policies/POL-10001/retire", null));
        assertEquals(401, getStatus("/api/policies/admin", null));

        // Adjuster + claimant → 403 on all four.
        for (String bearer : new String[] {
                JwtTestConfig.tokenFor("sub-adj-auth", "adjuster_l1"),
                JwtTestConfig.tokenFor("sub-clm-auth", "claimant") }) {
            assertEquals(403, postJsonStatus("/api/policies", bearer, createBody), "create");
            assertEquals(403, postImportStatus(csv, bearer), "import");
            assertEquals(403, postStatus("/api/policies/POL-10001/retire", bearer), "retire");
            assertEquals(403, getStatus("/api/policies/admin", bearer), "admin");
        }
    }

    @Test
    void retiringAnUnknownPolicyIs404Not403() throws Exception {
        HttpResponse<String> response = post("/api/policies/POL-NOPE-1/retire",
                supervisorBearer());
        assertEquals(404, response.statusCode(), response.body());
    }

    // --- helpers -------------------------------------------------------------------

    private String supervisorBearer() {
        return JwtTestConfig.tokenFor("sub-supervisor-admin", "supervisor");
    }

    private HttpResponse<String> postJson(String path, String bearer, String body)
            throws Exception {
        int port = port();
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int postJsonStatus(String path, String bearer, String body) throws Exception {
        return postJson(path, bearer, body).statusCode();
    }

    private HttpResponse<String> post(String path, String bearer) throws Exception {
        int port = port();
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int postStatus(String path, String bearer) throws Exception {
        return post(path, bearer).statusCode();
    }

    private HttpResponse<String> postImport(String csv) throws Exception {
        return postImportRaw(csv, supervisorBearer());
    }

    private int postImportStatus(String csv, String bearer) throws Exception {
        return postImportRaw(csv, bearer).statusCode();
    }

    private HttpResponse<String> postImportRaw(String csv, String bearer) throws Exception {
        int port = port();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "--" + BOUNDARY + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"file\"; filename=\"policies.csv\"\r\n");
        write(out, "Content-Type: text/csv\r\n\r\n");
        out.write(csv.getBytes(StandardCharsets.UTF_8));
        write(out, "\r\n--" + BOUNDARY + "--\r\n");
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/policies/import"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postFnol(String policyNumber, String holderName,
            String holderEmail) throws Exception {
        int port = port();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : Map.of(
                "policyNumber", policyNumber,
                "holderName", holderName,
                "holderEmail", holderEmail,
                "lossDate", "2026-09-01",
                "lossLocation", "London",
                "lossDescription", "Test loss").entrySet()) {
            write(out, "--" + BOUNDARY + "\r\n");
            write(out, "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n");
            write(out, field.getValue() + "\r\n");
        }
        write(out, "--" + BOUNDARY + "--\r\n");
        return http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/claims"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer "
                        + JwtTestConfig.tokenFor("sub-claimant-admin", "claimant"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getResponse(String path, String bearer) throws Exception {
        int port = port();
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int getStatus(String path, String bearer) throws Exception {
        return getResponse(path, bearer).statusCode();
    }

    private String statusOf(String policyNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM policy WHERE policy_number = ?", String.class, policyNumber);
    }

    private long countPolicy(String policyNumber) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM policy WHERE policy_number = ?", Long.class, policyNumber);
        return value == null ? 0 : value;
    }

    private void deletePolicy(String policyNumber) {
        // Policies with filed claims cannot be deleted (claim FK) — and must not be: retire
        // keeps them readable for history instead. Tests that filed claims only retire;
        // claim-free rows delete cleanly.
        Long id = jdbcTemplate.query(
                "SELECT id FROM policy WHERE policy_number = ?",
                rs -> rs.next() ? rs.getLong(1) : null, policyNumber);
        if (id == null) {
            return;
        }
        long claims = countPolicyClaims(id);
        if (claims == 0) {
            jdbcTemplate.update("DELETE FROM policy WHERE id = ?", id);
        } else {
            jdbcTemplate.update("UPDATE policy SET status = 'RETIRED' WHERE id = ?", id);
        }
    }

    private long countPolicyClaims(long policyId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM claim WHERE policy_id = ?", Long.class, policyId);
        return value == null ? 0 : value;
    }

    private static void write(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }
}
