package com.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.claims.TestcontainersConfiguration;
import com.claims.support.JwtTestConfig;

/**
 * FNOL acceptance: real HTTP multipart submissions through the real Spring context against
 * a real PostgreSQL, with a real SMTP capture (Mailpit via Testcontainers — the plan's
 * mail catcher) and signed test JWTs for auth. Schema and seed data come from Flyway.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FnolApiIntegrationTest {

    private static final String BOUNDARY = "----ClaimsTestBoundary42";
    private static final byte[] PHOTO = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};

    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
            DockerImageName.parse("axllent/mailpit:v1.22"))
            .withExposedPorts(1025, 8025);

    private static final Path UPLOADS = createUploadsDir();

    static {
        MAILPIT.start();
    }

    private static Path createUploadsDir() {
        try {
            return Files.createTempDirectory("claims-test-uploads");
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
    private ClaimRepository claims;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clearMailbox() throws Exception {
        http.send(HttpRequest.newBuilder(
                        URI.create(mailpitUrl() + "/api/v1/messages"))
                        .DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    // --- success paths ---------------------------------------------------------

    @Test
    void homePolicyFnolReturnsClaimNumberStoresEverythingAndEmails() throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-08-31",
                        "lossLocation", "London",
                        "lossDescription", "Kitchen flooded after a pipe burst.",
                        "remarks", "Happened overnight."),
                        true));

        assertEquals(201, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"status\":\"UNDER_REVIEW\""), response.body());
        assertTrue(response.body().matches(".*\"claimNumber\":\"CLM-\\d{6}\".*"), response.body());
        // The claimant view must structurally lack internals on the wire (visibility wall).
        for (String internal : new String[] {"claimantSub", "policyId", "lossDescription", "level",
                "assignedTo", "assignedAdjusterId"}) {
            assertFalse(response.body().contains("\"" + internal + "\""),
                    "claimant response must not contain internal field " + internal + ": " + response.body());
        }

        String claimNumber = response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
        Claim claim = findClaim(claimNumber);
        assertEquals("L1", claim.getLevel(), "HOME routes to L1");
        assertEquals("sub-claimant-1", claim.getClaimantSub());
        assertEquals("Happened overnight.", claim.getClaimantRemarks());
        // Slice 2: the claim was assigned at FNOL to an L1 adjuster and moved to under review.
        assertEquals("UNDER_REVIEW", claim.getStatus());
        assertEquals(1, count("SELECT count(*) FROM claim WHERE id = ? "
                + "AND assigned_adjuster_id IS NOT NULL AND assigned_at IS NOT NULL", claim.getId()));
        assertEquals("L1", jdbcTemplate.queryForObject(
                "SELECT a.level FROM claim c JOIN app_user a ON a.id = c.assigned_adjuster_id WHERE c.id = ?",
                String.class, claim.getId()),
                "an L1 claim must be assigned to an L1 adjuster");

        // Attachment row + the file really on disk under the upload dir.
        String path = jdbcTemplate.queryForObject(
                "SELECT storage_path FROM attachment WHERE claim_id = ?", String.class, claim.getId());
        assertNotNull(path);
        assertTrue(Files.exists(Path.of(path)), "uploaded photo file should exist on disk");
        assertEquals(1, count("SELECT count(*) FROM attachment WHERE claim_id = ?", claim.getId()));

        // Audit entry: claim created, actor recorded (C1: the writer's first write).
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE entity_type = 'CLAIM' "
                + "AND entity_id = ? AND action = 'CLAIM_CREATED' AND actor_sub = ?",
                claim.getId(), "sub-claimant-1"));
        // And the assignment, recorded as a system action.
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE entity_type = 'CLAIM' "
                + "AND entity_id = ? AND action = 'CLAIM_ASSIGNED' AND actor_sub IS NULL",
                claim.getId()));

        // FNOL + assignment emails to the verified policy-holder address with the claim number.
        String inbox = get(mailpitUrl() + "/api/v1/messages");
        assertTrue(inbox.contains(claimNumber), "mailpit should hold the FNOL email: " + inbox);
        assertTrue(inbox.contains("ada.lovelace@example.test"), inbox);
        assertTrue(inbox.contains("is now with an adjuster"),
                "an assignment email must be sent: " + inbox);
        assertTrue(inbox.contains("priya.sharma@claims.test") || inbox.contains("marcus.webb@claims.test"),
                "the assignment email names the assigned adjuster: " + inbox);
    }

    @Test
    void autoPolicyFnolClassifiesL2() throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(Map.of(
                        "policyNumber", "POL-20002",
                        "holderName", "Grace Hopper",
                        "holderEmail", "grace.hopper@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "Manchester",
                        "lossDescription", "Rear-ended at a roundabout."),
                        false));

        assertEquals(201, response.statusCode(), response.body());
        String claimNumber = response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
        Claim claim = findClaim(claimNumber);
        assertEquals("L2", claim.getLevel(), "AUTO routes to L2");
        assertEquals("UNDER_REVIEW", claim.getStatus());
        assertEquals("L2", jdbcTemplate.queryForObject(
                "SELECT a.level FROM claim c JOIN app_user a ON a.id = c.assigned_adjuster_id WHERE c.id = ?",
                String.class, claim.getId()),
                "an L2 claim must be assigned to an L2 adjuster");
    }

    @Test
    void policyNumberIsMatchedLenientlyAndHolderDetailsCaseInsensitively() throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(Map.of(
                        "policyNumber", "  pol-10001 ",
                        "holderName", "ada lovelace",
                        "holderEmail", "ADA.LOVELACE@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", "Storm damage"),
                        false));

        assertEquals(201, response.statusCode(), response.body());
    }

    // --- rejection paths -------------------------------------------------------

    @Test
    void fnolMissingRequiredLossDetailsIsRejected() throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "not-a-date",
                        "lossLocation", "",
                        "lossDescription", ""),
                        false));

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("description of what happened"), response.body());
        assertTrue(response.body().contains("yyyy-MM-dd"), response.body());
        assertTrue(get(mailpitUrl() + "/api/v1/messages").contains("\"total\":0"),
                "no email for a rejected FNOL");
    }

    @Test
    void fnolForUnknownPolicyReturns404() throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(Map.of(
                        "policyNumber", "POL-99999",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", "Storm damage"),
                        false));

        assertEquals(404, response.statusCode(), response.body());
        assertTrue(response.body().contains("could not match"), response.body());
    }

    @Test
    void fnolForMatchingPolicyButWrongHolderReturns404() throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Someone Else",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", "Storm damage"),
                        false));

        assertEquals(404, response.statusCode(), response.body());
    }

    @Test
    void nonImageAttachmentIsRejected() throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipartCount(Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", "Storm damage"),
                        1, "damage.txt", "text/plain"));

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("must be image files"), response.body());
    }

    @Test
    void tooManyPhotosAreRejected() throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipartCount(Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", "Storm damage"),
                        6, "damage.png", "image/png"));

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("At most 5 photos"), response.body());
    }

    // --- auth -------------------------------------------------------------------

    @Test
    void fnolWithoutTokenIs401() throws Exception {
        HttpResponse<String> response = post("/api/claims", null,
                multipart(Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", "Storm damage"),
                        false));

        assertEquals(401, response.statusCode(), response.body());
    }

    @Test
    void fnolWithNonClaimantRoleIs403() throws Exception {
        HttpResponse<String> response = post("/api/claims",
                JwtTestConfig.tokenFor("sub-adjuster-1", "adjuster_l1"),
                multipart(Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", "Storm damage"),
                        false));

        assertEquals(403, response.statusCode(), response.body());
    }

    @Test
    void fnolWithTokenLackingClaimantRoleIs403() throws Exception {
        HttpResponse<String> response = post("/api/claims",
                JwtTestConfig.tokenFor("sub-no-role"),
                multipart(Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", "Storm damage"),
                        false));

        assertEquals(403, response.statusCode(), response.body());
    }

    // --- helpers ---------------------------------------------------------------

    private Claim findClaim(String claimNumber) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM claim WHERE claim_number = ?", Long.class, claimNumber);
        return claims.findById(id).orElseThrow();
    }

    private String mailpitUrl() {
        return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
    }

    private String get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private String claimantBearer() {
        return JwtTestConfig.tokenFor("sub-claimant-1", "claimant");
    }

    private HttpResponse<String> post(String path, String bearer, byte[] body) throws Exception {
        int port = Integer.parseInt(environment.getProperty("local.server.port"));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] multipart(Map<String, String> fields, boolean withPhoto) {
        return multipartCount(fields, withPhoto ? 1 : 0, "damage.png", "image/png");
    }

    private static byte[] multipartCount(Map<String, String> fields, int photoCount,
            String filename, String contentType) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Map.Entry<String, String> field : fields.entrySet()) {
                write(out, "--" + BOUNDARY + "\r\n");
                write(out, "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n");
                write(out, field.getValue() + "\r\n");
            }
            for (int i = 0; i < photoCount; i++) {
                write(out, "--" + BOUNDARY + "\r\n");
                write(out, "Content-Disposition: form-data; name=\"photos\"; filename=\""
                        + filename + "\"\r\n");
                write(out, "Content-Type: " + contentType + "\r\n\r\n");
                out.write(PHOTO);
                write(out, "\r\n");
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

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
