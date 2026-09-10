package com.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
 * V21 (V3 S5): optimistic concurrency on the six money-path writers. Real HTTP
 * against real Postgres: read v0 → write v0 ok → stale v0 write → 409 with the
 * single CONFLICT shape; a concurrent PUT reserve pair where the second is a
 * 409; a stale decision after a supervisor reassign → 409 (never a silent
 * overwrite).
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClaimConcurrencyIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----ClaimConcurrencyBoundary1";

    private static final String SUB_L1_ONE = "10000000-0000-0000-0000-000000000001";
    private static final String SUB_SUPERVISOR = "10000000-0000-0000-0000-000000000004";
    private static final String CLAIMANT = "sub-claimant-concurrency";

    private static final String CONFLICT_ERROR = "\"error\":\"CONFLICT\"";
    private static final String CONFLICT_MESSAGE =
            "\"message\":\"This claim changed since you opened it. Reload and retry.\"";

    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
            DockerImageName.parse("axllent/mailpit:v1.22"))
            .withExposedPorts(1025, 8025);

    private static final Path UPLOADS = createUploadsDir();

    static {
        MAILPIT.start();
    }

    private static Path createUploadsDir() {
        try {
            return Files.createTempDirectory("claims-concurrency-test-uploads");
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

    @Test
    void readWriteThenStaleReserveWriteIs409WithTheSingleConflictShape() throws Exception {
        String claimNumber = fileHomeFnol();
        String path = "/api/claims/" + claimNumber + "/reserve";

        // Read the loaded version (FNOL's own transaction bumps it to 1 — the
        // test reads the live value, never assumes v0).
        long loaded = versionOf(claimNumber);
        String full = get("/api/claims/" + claimNumber + "/full", adjusterOneBearer()).body();
        assertTrue(full.contains("\"version\":" + loaded), full);

        // Write with the loaded version ok: version bumps.
        HttpResponse<String> first = putJson(path, adjusterOneBearer(),
                "{\"amount\": 1500.00,\"expectedVersion\":" + loaded + "}");
        assertEquals(200, first.statusCode(), first.body());
        assertTrue(first.body().contains("\"version\":" + (loaded + 1)), first.body());
        assertEquals(loaded + 1, versionOf(claimNumber));

        // Stale write with the old version: 409 with the exact single shape.
        HttpResponse<String> stale = putJson(path, adjusterOneBearer(),
                "{\"amount\": 9999.00,\"expectedVersion\":" + loaded + "}");
        assertConflict(stale);
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT reserve_amount FROM claim WHERE claim_number = ?",
                java.math.BigDecimal.class, claimNumber)
                .compareTo(new java.math.BigDecimal("1500.00")) == 0,
                "the stale write must not move the reserve");

        // A write without a version is also stale: same single shape.
        assertConflict(putJson(path, adjusterOneBearer(), "{\"amount\": 1500.00}"));
    }

    @Test
    void concurrentReservePairSecondIs409() throws Exception {
        String claimNumber = fileHomeFnol();
        String path = "/api/claims/" + claimNumber + "/reserve";
        String bearer = adjusterOneBearer();
        long loaded = versionOf(claimNumber);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<HttpResponse<String>>> writes = new ArrayList<>();
            writes.add(() -> putJson(path, bearer,
                    "{\"amount\": 1000.00,\"expectedVersion\":" + loaded + "}"));
            writes.add(() -> putJson(path, bearer,
                    "{\"amount\": 2000.00,\"expectedVersion\":" + loaded + "}"));
            List<Future<HttpResponse<String>>> results = pool.invokeAll(writes);
            List<Integer> statuses = new ArrayList<>();
            for (Future<HttpResponse<String>> result : results) {
                statuses.add(result.get().statusCode());
            }
            assertTrue(statuses.contains(200), "one write wins: " + statuses);
            assertTrue(statuses.contains(409), "the loser is a 409: " + statuses);
            for (Future<HttpResponse<String>> result : results) {
                HttpResponse<String> response = result.get();
                if (response.statusCode() == 409) {
                    assertTrue(response.body().contains(CONFLICT_ERROR), response.body());
                    assertTrue(response.body().contains(CONFLICT_MESSAGE), response.body());
                }
            }
            // Exactly one reserve write landed: one audit row, version bumped once.
            assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'RESERVE_SET' "
                    + "AND entity_id = ?", idOf(claimNumber)));
            assertEquals(loaded + 1, versionOf(claimNumber));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staleDecisionAfterReassignIs409NotASilentOverwrite() throws Exception {
        String claimNumber = fileHomeFnol();
        String decisionPath = "/api/claims/" + claimNumber + "/decision";
        String originalBearer = holderBearer(claimNumber);
        long originalVersion = versionOf(claimNumber);

        // The supervisor reassigns (L1 -> L2): the claim row moves on, its assignee
        // and version change while the original actor still holds the old version.
        HttpResponse<String> reassigned = postJson(
                "/api/claims/" + claimNumber + "/reassign", supervisorBearer(),
                "{\"level\":\"L2\"}");
        assertEquals(200, reassigned.statusCode(), reassigned.body());
        assertTrue(versionOf(claimNumber) > originalVersion,
                "reassign must bump the version so a stale decision is detectable");

        // Stale decision with the pre-reassign version, as the CURRENT holder:
        // 409, never a silent overwrite. (The original actor is 404 — the wall
        // holds for non-holders; the version check runs after the access check.)
        assertEquals(404, postJson(decisionPath, originalBearer,
                "{\"decision\":\"APPROVED\",\"indemnityAmount\":1500.00,"
                        + "\"rationale\":\"stale attempt\",\"expectedVersion\":"
                        + originalVersion + "}").statusCode(),
                "the actor who lost the claim is 404, never 403");
        String holderBearer = holderBearer(claimNumber);
        HttpResponse<String> stale = postJson(decisionPath, holderBearer,
                "{\"decision\":\"APPROVED\",\"indemnityAmount\":1500.00,"
                        + "\"rationale\":\"stale attempt\",\"expectedVersion\":"
                        + originalVersion + "}");
        assertConflict(stale);
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        assertEquals(0, count("SELECT count(*) FROM audit_log WHERE action = 'DECISION' "
                + "AND entity_id = ?", idOf(claimNumber)),
                "no decision audit row for a conflicted write");
        assertEquals(0, count("SELECT count(*) FROM payment WHERE claim_id = ?",
                idOf(claimNumber)));

        // The current holder decides with the fresh version: closes normally.
        HttpResponse<String> decided = postJson(decisionPath, holderBearer,
                "{\"decision\":\"DENIED\",\"rationale\":\"Not covered by this policy.\","
                        + "\"expectedVersion\":" + versionOf(claimNumber) + "}");
        assertEquals(200, decided.statusCode(), decided.body());
        assertEquals("CLOSED", statusOf(claimNumber));
    }

    private void assertConflict(HttpResponse<String> response) {
        assertEquals(409, response.statusCode(), response.body());
        assertTrue(response.body().contains(CONFLICT_ERROR), response.body());
        assertTrue(response.body().contains(CONFLICT_MESSAGE), response.body());
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

    private HttpResponse<String> putJson(String path, String bearer, String body)
            throws Exception {
        return json("PUT", path, bearer, body);
    }

    private HttpResponse<String> postJson(String path, String bearer, String body)
            throws Exception {
        return json("POST", path, bearer, body);
    }

    private HttpResponse<String> json(String method, String path, String bearer, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
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
                out.write(("--" + BOUNDARY + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
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

    private Long idOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM claim WHERE claim_number = ?", Long.class, claimNumber);
    }

    private Long versionOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM claim WHERE claim_number = ?", Long.class, claimNumber);
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

    private String claimantBearer() {
        return JwtTestConfig.tokenFor(CLAIMANT, "claimant");
    }

    private String adjusterOneBearer() {
        return JwtTestConfig.tokenFor(SUB_L1_ONE, "adjuster_l1");
    }

    private String supervisorBearer() {
        return JwtTestConfig.tokenFor(SUB_SUPERVISOR, "supervisor");
    }

    /**
     * Bearer for whoever currently holds the claim (resolves the assignee from
     * the claim row so post-reassign decisions run as the new holder).
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
}
