package com.claims.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Slice-7 acceptance for the authority config surface: the supervisor reads and edits the
 * per-product authority rows (route level + L1/L2 limits) and the edits feed the two
 * consumers immediately — the authority gate at decision time and the classifier at FNOL.
 * Real HTTP against real Postgres (claim tables reset per test; {@code authority_config} is
 * a seed table, so every mutating test restores its row in {@code finally}).
 *
 * <p>Seeded state (V3/V6): HOME routes L1, AUTO routes L2, both with limits
 * 2500.00/10000.00. The L1 adjuster is adjuster.one (the first FNOL of a test lands there
 * deterministically).
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthorityConfigIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----AuthorityConfigBoundary7";

    private static final String SUB_L1_ONE = "10000000-0000-0000-0000-000000000001";
    private static final String SUB_L2 = "10000000-0000-0000-0000-000000000003";
    private static final String SUB_SUPERVISOR = "10000000-0000-0000-0000-000000000004";
    private static final String CLAIMANT = "sub-claimant-config";

    private static final BigDecimal SEED_L1 = new BigDecimal("2500.00");
    private static final BigDecimal SEED_L2 = new BigDecimal("10000.00");

    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
            DockerImageName.parse("axllent/mailpit:v1.22"))
            .withExposedPorts(1025, 8025);

    private static final Path UPLOADS = createUploadsDir();

    static {
        MAILPIT.start();
    }

    private static Path createUploadsDir() {
        try {
            return Files.createTempDirectory("claims-authority-config-test-uploads");
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

    // --- read -----------------------------------------------------------------

    @Test
    void getListsTheSeededConfigRowsByProductCode() throws Exception {
        HttpResponse<String> response = get("/api/config/authority", supervisorBearer());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"productCode\":\"AUTO\""), response.body());
        assertTrue(response.body().contains("\"productCode\":\"HOME\""), response.body());
        assertTrue(response.body().contains("\"routeLevel\":\"L1\""), response.body());
        assertTrue(response.body().contains("\"routeLevel\":\"L2\""), response.body());
        assertTrue(response.body().contains("\"l1LimitAmount\":2500.00"), response.body());
        assertTrue(response.body().contains("\"l2LimitAmount\":10000.00"), response.body());
        assertTrue(response.body().indexOf("\"AUTO\"") < response.body().indexOf("\"HOME\""),
                "rows are listed deterministically by product code: " + response.body());
    }

    @Test
    void configEndpointsAreSupervisorOnly() throws Exception {
        String body = "{\"routeLevel\":\"L1\",\"l1LimitAmount\":2500.00,"
                + "\"l2LimitAmount\":10000.00}";
        // Anonymous -> 401; claimant and both adjuster levels -> 403 on read and write.
        assertEquals(401, get("/api/config/authority", null).statusCode());
        assertEquals(403, get("/api/config/authority", claimantBearer()).statusCode());
        assertEquals(403, get("/api/config/authority",
                JwtTestConfig.tokenFor(SUB_L1_ONE, "adjuster_l1")).statusCode());
        assertEquals(403, get("/api/config/authority",
                JwtTestConfig.tokenFor(SUB_L2, "adjuster_l2")).statusCode());
        assertEquals(401, putJson("/api/config/authority/HOME", null, body).statusCode());
        assertEquals(403, putJson("/api/config/authority/HOME", claimantBearer(), body)
                .statusCode());
        assertEquals(403, putJson("/api/config/authority/HOME",
                JwtTestConfig.tokenFor(SUB_L1_ONE, "adjuster_l1"), body).statusCode());
    }

    // --- write ----------------------------------------------------------------

    @Test
    void putUpdatesTheRowsThresholdsAndRouteLevel() throws Exception {
        try {
            HttpResponse<String> response = putJson("/api/config/authority/HOME",
                    supervisorBearer(),
                    "{\"routeLevel\":\"L2\",\"l1LimitAmount\":3000.00,"
                            + "\"l2LimitAmount\":12000.00}");
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"productCode\":\"HOME\""), response.body());
            assertTrue(response.body().contains("\"routeLevel\":\"L2\""), response.body());
            assertTrue(response.body().contains("\"l1LimitAmount\":3000.00"), response.body());
            assertTrue(response.body().contains("\"l2LimitAmount\":12000.00"), response.body());

            assertEquals(0, new BigDecimal("3000.00").compareTo(limitOf("HOME", "l1_limit_amount")),
                    "the edited limit must be stored");
        } finally {
            restoreConfig("HOME", "L1", SEED_L1, SEED_L2);
        }
    }

    @Test
    void raisingTheL1LimitFeedsTheAuthorityGateImmediately() throws Exception {
        String claimNumber = fileHomeFnol();
        try {
            // 2800 is above the seeded L1 limit (2500) but within the new one: after the
            // supervisor raises HOME's L1 limit the same approval must close, not escalate.
            assertEquals(200, putJson("/api/config/authority/HOME", supervisorBearer(),
                    "{\"routeLevel\":\"L1\",\"l1LimitAmount\":3000.00,"
                            + "\"l2LimitAmount\":10000.00}").statusCode());

            HttpResponse<String> decision = postJson("/api/claims/" + claimNumber + "/decision",
                    adjusterOneBearer(),
                    "{\"decision\":\"APPROVED\",\"indemnityAmount\":2800.00,"
                            + "\"rationale\":\"Within the raised L1 limit.\"}");
            assertEquals(200, decision.statusCode(), decision.body());
            assertTrue(decision.body().contains("\"decision\":\"APPROVED\""), decision.body());
            assertEquals("CLOSED", jdbcTemplate.queryForObject(
                    "SELECT status FROM claim WHERE claim_number = ?", String.class, claimNumber));
            assertEquals(1, count("SELECT count(*) FROM payment WHERE claim_id = "
                    + "(SELECT id FROM claim WHERE claim_number = ?)", claimNumber));
        } finally {
            restoreConfig("HOME", "L1", SEED_L1, SEED_L2);
        }
    }

    @Test
    void changingTheRouteLevelFeedsClassificationImmediately() throws Exception {
        try {
            // AUTO is seeded to route L2; after the supervisor re-routes it to L1, a new
            // AUTO FNOL must classify L1 and land on an L1 adjuster.
            assertEquals(200, putJson("/api/config/authority/AUTO", supervisorBearer(),
                    "{\"routeLevel\":\"L1\",\"l1LimitAmount\":2500.00,"
                            + "\"l2LimitAmount\":10000.00}").statusCode());

            String claimNumber = fileAutoFnol();
            Long claimId = idOf(claimNumber);
            assertEquals("L1", jdbcTemplate.queryForObject(
                    "SELECT level FROM claim WHERE id = ?", String.class, claimId),
                    "AUTO must now classify L1 after the config edit");
            assertEquals("L1", jdbcTemplate.queryForObject(
                    "SELECT a.level FROM claim c JOIN app_user a ON a.id = c.assigned_adjuster_id "
                            + "WHERE c.id = ?", String.class, claimId),
                    "the re-routed claim must be assigned to an L1 adjuster");
        } finally {
            restoreConfig("AUTO", "L2", SEED_L1, SEED_L2);
        }
    }

    // --- validation -----------------------------------------------------------

    @Test
    void putRejectsInvalidConfigValuesAndLeavesTheRowUntouched() throws Exception {
        String[] badBodies = {
                // Unknown route level.
                "{\"routeLevel\":\"L3\",\"l1LimitAmount\":2500.00,\"l2LimitAmount\":10000.00}",
                // Amounts must be positive and within NUMERIC(14,2).
                "{\"routeLevel\":\"L1\",\"l1LimitAmount\":0,\"l2LimitAmount\":10000.00}",
                "{\"routeLevel\":\"L1\",\"l1LimitAmount\":-100,\"l2LimitAmount\":10000.00}",
                // More than 2 decimal places.
                "{\"routeLevel\":\"L1\",\"l1LimitAmount\":2500.001,\"l2LimitAmount\":10000.00}",
                // Over the column bound.
                "{\"routeLevel\":\"L1\",\"l1LimitAmount\":1000000000000.00,"
                        + "\"l2LimitAmount\":10000.00}",
                // An inverted ladder (l1 > l2) is a config error, not a legal state.
                "{\"routeLevel\":\"L1\",\"l1LimitAmount\":10000.00,\"l2LimitAmount\":5000.00}",
                // Missing fields.
                "{\"routeLevel\":\"L1\"}",
                // Unreadable body.
                "{"
        };
        for (String body : badBodies) {
            HttpResponse<String> response = putJson("/api/config/authority/HOME",
                    supervisorBearer(), body);
            assertEquals(400, response.statusCode(), "body " + body + " -> " + response.body());
            assertFalse(response.body().isBlank(), "every 400 carries a message: " + body);
        }
        assertEquals(0, SEED_L1.compareTo(limitOf("HOME", "l1_limit_amount")),
                "a rejected edit must not change the stored row");
        assertEquals("L1", jdbcTemplate.queryForObject(
                "SELECT route_level FROM authority_config WHERE product_code = 'HOME'",
                String.class));
    }

    @Test
    void putOfAnUnknownProductCodeIsNotFound() throws Exception {
        HttpResponse<String> response = putJson("/api/config/authority/NOPE",
                supervisorBearer(),
                "{\"routeLevel\":\"L1\",\"l1LimitAmount\":2500.00,\"l2LimitAmount\":10000.00}");
        assertEquals(404, response.statusCode(), response.body());
    }

    // --- helpers --------------------------------------------------------------

    private void restoreConfig(String productCode, String routeLevel, BigDecimal l1,
            BigDecimal l2) {
        jdbcTemplate.update("UPDATE authority_config SET route_level = ?, "
                + "l1_limit_amount = ?, l2_limit_amount = ? WHERE product_code = ?",
                routeLevel, l1, l2, productCode);
    }

    private BigDecimal limitOf(String productCode, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM authority_config WHERE product_code = ?",
                BigDecimal.class, productCode);
    }

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
}
