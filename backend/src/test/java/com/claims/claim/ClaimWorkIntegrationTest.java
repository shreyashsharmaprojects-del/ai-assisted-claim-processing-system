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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.claims.TestcontainersConfiguration;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

/**
 * Slice-3 acceptance: the visibility wall and the adjuster's work on a claim. Real HTTP,
 * real Postgres (claim tables reset per test by {@link ClaimTableResettingTest}, so the
 * first L1 FNOL deterministically lands on adjuster.one), real SMTP capture. The claimant
 * status surface, the internal full view, reserve/notes writes, and photo downloads are
 * each asserted for success, validation, and the 404-for-non-assignee rule.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClaimWorkIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----ClaimWorkTestBoundary7";
    private static final byte[] PHOTO = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};

    private static final String SUB_L1_ONE = "10000000-0000-0000-0000-000000000001"; // adjuster.one
    private static final String SUB_L1_TWO = "10000000-0000-0000-0000-000000000002"; // adjuster.two
    private static final String CLAIMANT = "sub-claimant-work";

    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
            DockerImageName.parse("axllent/mailpit:v1.22"))
            .withExposedPorts(1025, 8025);

    private static final Path UPLOADS = createUploadsDir();

    static {
        MAILPIT.start();
    }

    private static Path createUploadsDir() {
        try {
            return Files.createTempDirectory("claims-work-test-uploads");
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
        // Claim tables were reset by the shared base; just empty the mailbox.
        http.send(HttpRequest.newBuilder(URI.create(mailpitUrl() + "/api/v1/messages")).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    // --- claimant status surface ----------------------------------------------

    @Test
    void claimantReadsTheirOwnClaimAndNeverSeesInternals() throws Exception {
        String claimNumber = fileHomeFnolWithPhoto();

        HttpResponse<String> own = get("/api/claims/" + claimNumber, claimantBearer());
        assertEquals(200, own.statusCode(), own.body());
        assertTrue(own.body().contains("\"claimNumber\":\"" + claimNumber + "\""), own.body());
        assertTrue(own.body().contains("UNDER_REVIEW"), own.body());
        for (String internal : new String[] {"reserveAmount", "reserve", "internalNote", "notes",
                "assignedTo", "policyNumber", "coverage"}) {
            assertFalse(own.body().contains("\"" + internal + "\""),
                    "the claimant status response must not contain internal field " + internal
                            + ": " + own.body());
        }
    }

    @Test
    void claimantCannotReadAnotherClaimantsClaim() throws Exception {
        String claimNumber = fileHomeFnol();
        HttpResponse<String> other = get("/api/claims/" + claimNumber,
                JwtTestConfig.tokenFor("sub-claimant-other", "claimant"));
        assertEquals(404, other.statusCode(), other.body());
    }

    @Test
    void claimantStatusEndpointIsAuthenticated() throws Exception {
        String claimNumber = fileHomeFnol();
        assertEquals(401, get("/api/claims/" + claimNumber, null).statusCode());
    }

    // --- internal full view ----------------------------------------------------

    @Test
    void assignedAdjusterAndSupervisorSeeTheFullClaimWithCoverage() throws Exception {
        String claimNumber = fileHomeFnolWithPhoto();

        String assigneeView = get("/api/claims/" + claimNumber + "/full", adjusterOneBearer()).body();
        assertTrue(assigneeView.contains(claimNumber), assigneeView);
        assertTrue(assigneeView.contains("POL-10001"), assigneeView);
        assertTrue(assigneeView.contains("\"coverage\""), "full view carries the policy coverage: " + assigneeView);
        assertTrue(assigneeView.contains("\"type\":\"home\""), assigneeView);
        assertTrue(assigneeView.contains("reserveAmount"), assigneeView);
        assertTrue(assigneeView.contains("kitchen.png"), "full view lists the photo: " + assigneeView);
        assertTrue(assigneeView.contains("Ada Lovelace"), assigneeView);

        String supervisorView = get("/api/claims/" + claimNumber + "/full",
                JwtTestConfig.tokenFor("sub-supervisor-1", "supervisor")).body();
        assertTrue(supervisorView.contains(claimNumber), supervisorView);
    }

    @Test
    void nonAssigneeAdjusterGets404OnTheFullView() throws Exception {
        String claimNumber = fileHomeFnol();
        HttpResponse<String> response = get("/api/claims/" + claimNumber + "/full",
                JwtTestConfig.tokenFor(SUB_L1_TWO, "adjuster_l1"));
        assertEquals(404, response.statusCode(),
                "an adjuster must never see a claim that is not theirs (404, not 403): " + response.body());
    }

    @Test
    void fullViewOfUnknownClaimIs404AndClaimantIsRoleBlocked() throws Exception {
        assertEquals(404, get("/api/claims/CLM-999999/full", adjusterOneBearer()).statusCode());
        assertEquals(403, get("/api/claims/CLM-000001/full", claimantBearer()).statusCode());
        assertEquals(401, get("/api/claims/CLM-000001/full", null).statusCode());
    }

    // --- reserve ---------------------------------------------------------------

    @Test
    void assigneeSetsAndUpdatesTheReserveWithAudit() throws Exception {
        String claimNumber = fileHomeFnol();

        HttpResponse<String> first = putJson("/api/claims/" + claimNumber + "/reserve",
                adjusterOneBearer(), "{\"amount\": 1500.00}");
        assertEquals(200, first.statusCode(), first.body());
        assertTrue(first.body().contains("\"reserveAmount\":1500.00"), first.body());

        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'RESERVE_SET' "
                + "AND entity_id = ? AND actor_sub = ?", idOf(claimNumber), SUB_L1_ONE));

        HttpResponse<String> updated = putJson("/api/claims/" + claimNumber + "/reserve",
                adjusterOneBearer(), "{\"amount\": 1800.50}");
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(updated.body().contains("\"reserveAmount\":1800.50"), updated.body());
        assertEquals(2, count("SELECT count(*) FROM audit_log WHERE action = 'RESERVE_SET' "
                + "AND entity_id = ?", idOf(claimNumber)));
    }

    @Test
    void supervisorMaySetTheReserveButNonAssigneeAndNegativeAmountsCannot() throws Exception {
        String claimNumber = fileHomeFnol();

        assertEquals(200, putJson("/api/claims/" + claimNumber + "/reserve",
                JwtTestConfig.tokenFor("sub-supervisor-1", "supervisor"),
                "{\"amount\": 500}").statusCode());

        assertEquals(404, putJson("/api/claims/" + claimNumber + "/reserve",
                JwtTestConfig.tokenFor(SUB_L1_TWO, "adjuster_l1"), "{\"amount\": 500}").statusCode(),
                "a non-assignee adjuster gets 404 on reserve, never 403");

        HttpResponse<String> negative = putJson("/api/claims/" + claimNumber + "/reserve",
                adjusterOneBearer(), "{\"amount\": -1}");
        assertEquals(400, negative.statusCode(), negative.body());
        assertTrue(negative.body().contains("zero or more"), negative.body());
    }

    // --- internal notes --------------------------------------------------------

    @Test
    void assigneeWritesNotesThatAppearInTheFullView() throws Exception {
        String claimNumber = fileHomeFnol();

        HttpResponse<String> first = postJson("/api/claims/" + claimNumber + "/notes",
                adjusterOneBearer(), "{\"body\": \"Coverage confirmed; awaiting builder quote.\"}");
        assertEquals(200, first.statusCode(), first.body());
        assertTrue(first.body().contains("Priya Sharma"), "note author comes from the staff cache: " + first.body());
        assertTrue(first.body().contains("Coverage confirmed"), first.body());

        assertEquals(200, postJson("/api/claims/" + claimNumber + "/notes",
                adjusterOneBearer(), "{\"body\": \"Second note.\"}").statusCode());

        String full = get("/api/claims/" + claimNumber + "/full", adjusterOneBearer()).body();
        int firstAt = full.indexOf("Coverage confirmed");
        int secondAt = full.indexOf("Second note.");
        assertTrue(firstAt >= 0 && secondAt > firstAt, "notes are listed oldest first: " + full);
    }

    @Test
    void noteValidationAndAuthorization() throws Exception {
        String claimNumber = fileHomeFnol();

        HttpResponse<String> blank = postJson("/api/claims/" + claimNumber + "/notes",
                adjusterOneBearer(), "{\"body\": \"   \"}");
        assertEquals(400, blank.statusCode(), blank.body());
        assertTrue(blank.body().contains("Note text is required"), blank.body());

        assertEquals(404, postJson("/api/claims/" + claimNumber + "/notes",
                JwtTestConfig.tokenFor(SUB_L1_TWO, "adjuster_l1"),
                "{\"body\": \"not mine to annotate\"}").statusCode());
    }

    // --- photos ----------------------------------------------------------------

    @Test
    void assigneeDownloadsThePhotoAsAnAttachmentAndOthersCannot() throws Exception {
        String claimNumber = fileHomeFnolWithPhoto();
        String full = get("/api/claims/" + claimNumber + "/full", adjusterOneBearer()).body();
        Long attachmentId = Long.parseLong(full.replaceAll(".*\"attachments\":\\[\\{\"id\":(\\d+).*", "$1"));

        HttpResponse<byte[]> download = getBytes("/api/claims/" + claimNumber + "/attachments/" + attachmentId,
                adjusterOneBearer());
        assertEquals(200, download.statusCode());
        assertEquals("image/png", download.headers().firstValue("Content-Type").orElse(""));
        assertTrue(download.headers().firstValue("Content-Disposition").orElse("")
                .startsWith("attachment"), "photos must be served as attachments, never inline");
        assertTrue(download.headers().firstValue("Content-Disposition").orElse("")
                .contains("kitchen.png"));
        assertTrue(java.util.Arrays.equals(PHOTO, download.body()), "downloaded bytes equal the upload");

        assertEquals(404, get("/api/claims/" + claimNumber + "/attachments/" + attachmentId,
                JwtTestConfig.tokenFor(SUB_L1_TWO, "adjuster_l1")).statusCode(),
                "a non-assignee adjuster cannot download someone else's photo");
        assertEquals(404, get("/api/claims/" + claimNumber + "/attachments/999999",
                adjusterOneBearer()).statusCode());
    }

    // --- helpers ---------------------------------------------------------------

    private String fileHomeFnol() throws Exception {
        return fileHomeFnol(false);
    }

    private String fileHomeFnolWithPhoto() throws Exception {
        return fileHomeFnol(true);
    }

    private String fileHomeFnol(boolean withPhoto) throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", "Kitchen flooded after a pipe burst."),
                        withPhoto));
        assertEquals(201, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    private HttpResponse<String> putJson(String path, String bearer, String body) throws Exception {
        return json("PUT", path, bearer, body);
    }

    private HttpResponse<String> postJson(String path, String bearer, String body) throws Exception {
        return json("POST", path, bearer, body);
    }

    private HttpResponse<String> json(String method, String path, String bearer, String body) throws Exception {
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
        return sendGet(path, bearer, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String path, String bearer) throws Exception {
        return sendGet(path, bearer, HttpResponse.BodyHandlers.ofByteArray());
    }

    private <T> HttpResponse<T> sendGet(String path, String bearer,
            HttpResponse.BodyHandler<T> handler) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), handler);
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

    private static byte[] multipart(Map<String, String> fields, boolean withPhoto) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Map.Entry<String, String> field : fields.entrySet()) {
                write(out, "--" + BOUNDARY + "\r\n");
                write(out, "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n");
                write(out, field.getValue() + "\r\n");
            }
            if (withPhoto) {
                write(out, "--" + BOUNDARY + "\r\n");
                write(out, "Content-Disposition: form-data; name=\"photos\"; filename=\"kitchen.png\"\r\n");
                write(out, "Content-Type: image/png\r\n\r\n");
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
}
