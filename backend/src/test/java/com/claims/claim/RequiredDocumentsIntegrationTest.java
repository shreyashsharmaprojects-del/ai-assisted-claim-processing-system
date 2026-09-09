package com.claims.claim;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
 * S3 acceptance: the per-product required-documents checklist. Auto-seeded
 * PENDING rows at FNOL (per product family), the assignee/supervisor full
 * rows vs the claimant walled shape, link/waive auth + audit, docKey
 * auto-link on all three upload paths, and unknown-key 400s.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequiredDocumentsIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----RequiredDocsBoundary19";
    private static final byte[] PNG = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};

    private static final String SUB_L1_TWO = "10000000-0000-0000-0000-000000000002";
    private static final String CLAIMANT = "sub-claimant-reqdocs";

    private static final AtomicInteger LOSS_DAY = new AtomicInteger(3);

    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
            DockerImageName.parse("axllent/mailpit:v1.22"))
            .withExposedPorts(1025, 8025);

    private static final Path UPLOADS = createUploadsDir();

    static {
        MAILPIT.start();
    }

    private static Path createUploadsDir() {
        try {
            return Files.createTempDirectory("claims-reqdocs-test-uploads");
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
        http.send(HttpRequest.newBuilder(URI.create(mailpitUrl() + "/api/v1/messages")).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    // --- seed coverage per family ------------------------------------------------

    @Test
    void healthClaimSeedsThreeHealthKeys() throws Exception {
        String claimNumber = fileFnol("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        String body = get("/api/claims/" + claimNumber + "/required-documents",
                holderBearer(claimNumber)).body();
        assertTrue(body.contains("DISCHARGE_SUMMARY"), body);
        assertTrue(body.contains("FINAL_BILL"), body);
        assertTrue(body.contains("ID_PROOF"), body);
        assertTrue(body.contains("\"documentsTotal\":3"), body);
        assertTrue(body.contains("\"documentsReceived\":0"), body);
        assertEquals(3, count(
                "SELECT count(*) FROM claim_document_check WHERE claim_id = ? AND status = 'PENDING'",
                idOf(claimNumber)));
    }

    @Test
    void autoClaimSeedsThreeAutoKeys() throws Exception {
        String claimNumber = fileFnol("POL-20002", "Grace Hopper",
                "grace.hopper@example.test");
        String body = get("/api/claims/" + claimNumber + "/required-documents",
                holderBearer(claimNumber)).body();
        assertTrue(body.contains("PHOTOS"), body);
        assertTrue(body.contains("ESTIMATE"), body);
        assertTrue(body.contains("RC_COPY"), body);
        assertTrue(body.contains("\"documentsTotal\":3"), body);
    }

    @Test
    void propertyClaimSeedsThreePropertyKeys() throws Exception {
        String claimNumber = fileFnol("POL-30004", "Lakshmi Iyer",
                "lakshmi.iyer@example.test");
        String body = get("/api/claims/" + claimNumber + "/required-documents",
                holderBearer(claimNumber)).body();
        assertTrue(body.contains("PHOTOS"), body);
        assertTrue(body.contains("ESTIMATE"), body);
        assertTrue(body.contains("OWNERSHIP_PROOF"), body);
        assertTrue(body.contains("\"documentsTotal\":3"), body);
    }

    @Test
    void orphanProductSeedsAnEmptyChecklist() throws Exception {
        String claimNumber = fileFnol("POL-30009", "Orphan Holder",
                "orphan.holder@example.test");
        String body = get("/api/claims/" + claimNumber + "/required-documents",
                JwtTestConfig.tokenFor("sub-supervisor-o", "supervisor")).body();
        assertTrue(body.contains("\"documentsTotal\":0"), body);
        assertTrue(body.contains("\"documentsReceived\":0"), body);
    }

    // --- auth matrix -------------------------------------------------------------

    @Test
    void strangerSees404AndClaimantSeesWalledShapeWithoutDecidedBy() throws Exception {
        String claimNumber = fileFnol("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");

        assertEquals(404, get("/api/claims/" + claimNumber + "/required-documents",
                JwtTestConfig.tokenFor(SUB_L1_TWO, "adjuster_l1")).statusCode());
        assertEquals(404, get("/api/claims/" + claimNumber + "/required-documents",
                JwtTestConfig.tokenFor("sub-claimant-other", "claimant")).statusCode());

        HttpResponse<String> claimant = get("/api/claims/" + claimNumber + "/required-documents",
                claimantBearer());
        assertEquals(200, claimant.statusCode(), claimant.body());
        assertTrue(claimant.body().contains("\"documentsTotal\":3"), claimant.body());
        assertTrue(claimant.body().contains("Discharge summary"), claimant.body());
        assertTrue(claimant.body().contains("\"status\":\"PENDING\""), claimant.body());
        for (String internal : new String[] {"decided_by", "decidedBy", "attachmentId",
                "attachment_id", "checkId", "docKey", "DOC_LINKED", "DOC_WAIVED"}) {
            assertFalse(claimant.body().contains("\"" + internal + "\""),
                    "claimant checklist must not contain internal field " + internal
                            + ": " + claimant.body());
        }
        assertFalse(claimant.body().contains("decided_by"), claimant.body());
        assertFalse(claimant.body().contains("decidedBy"), claimant.body());
    }

    @Test
    void claimantTrackerCarriesCountsButNoInternals() throws Exception {
        String claimNumber = fileFnol("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        HttpResponse<String> status = get("/api/claims/" + claimNumber, claimantBearer());
        assertEquals(200, status.statusCode(), status.body());
        assertTrue(status.body().contains("\"documentsTotal\":3"), status.body());
        assertTrue(status.body().contains("\"documentsReceived\":0"), status.body());
        assertTrue(status.body().contains("Discharge summary"), status.body());
        assertFalse(status.body().contains("decided_by"), status.body());
        assertFalse(status.body().contains("decidedBy"), status.body());
    }

    @Test
    void internalViewsCarryDocumentCounts() throws Exception {
        String claimNumber = fileFnol("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        String bearer = holderBearer(claimNumber);
        String full = get("/api/claims/" + claimNumber + "/full", bearer).body();
        assertTrue(full.contains("\"documentsTotal\":3"), full);
        assertTrue(full.contains("\"documentsReceived\":0"), full);
        String staged = get("/api/claims/" + claimNumber + "/staged", bearer).body();
        assertTrue(staged.contains("\"documentsTotal\":3"), staged);
        assertTrue(staged.contains("\"documentsReceived\":0"), staged);
    }

    // --- link / waive ------------------------------------------------------------

    @Test
    void assigneeLinksAnAttachmentWithAuditAndClaimantSeesItReceived() throws Exception {
        String claimNumber = fileFnol("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        String bearer = holderBearer(claimNumber);
        Long attachmentId = attachAs(bearer, claimNumber, null);

        Long checkId = checkIdOf(claimNumber, "DISCHARGE_SUMMARY");
        HttpResponse<String> linked = postJson(
                "/api/claims/" + claimNumber + "/required-documents/" + checkId + "/link",
                bearer, "{\"attachmentId\":" + attachmentId + "}");
        assertEquals(200, linked.statusCode(), linked.body());
        assertTrue(linked.body().contains("\"status\":\"RECEIVED\""), linked.body());
        assertTrue(linked.body().contains("\"attachmentId\":" + attachmentId), linked.body());
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'DOC_LINKED' "
                + "AND entity_id = ?", idOf(claimNumber)));

        HttpResponse<String> claimant = get("/api/claims/" + claimNumber + "/required-documents",
                claimantBearer());
        assertEquals(200, claimant.statusCode(), claimant.body());
        assertTrue(claimant.body().contains("\"documentsReceived\":1"), claimant.body());
        assertFalse(claimant.body().contains("decidedBy"), claimant.body());
    }

    @Test
    void linkRejectsForeignAttachmentsAndWaiveNeedsARationale() throws Exception {
        String claimNumber = fileFnol("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        String bearer = holderBearer(claimNumber);
        String other = fileFnol("POL-30001", "Ravi Menon", "ravi.menon@example.test");
        Long foreignAttachment = attachAs(holderBearer(other), other, null);

        Long checkId = checkIdOf(claimNumber, "FINAL_BILL");
        assertEquals(404, postJson(
                "/api/claims/" + claimNumber + "/required-documents/" + checkId + "/link",
                bearer, "{\"attachmentId\":" + foreignAttachment + "}").statusCode());
        assertEquals(400, postJson(
                "/api/claims/" + claimNumber + "/required-documents/" + checkId + "/waive",
                bearer, "{\"rationale\":\"   \"}").statusCode());
    }

    @Test
    void assigneeWaivesWithAuditAndClaimantIsForbidden() throws Exception {
        String claimNumber = fileFnol("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        String bearer = holderBearer(claimNumber);
        Long checkId = checkIdOf(claimNumber, "ID_PROOF");

        // Slice matrix: the claimant is 403 on waive (role gate), the stranger 404.
        // (Claimant POST link is likewise 403 at the role gate — like every
        // claimant-on-internal-URL probe in ClaimWorkIntegrationTest.)
        assertEquals(403, postJson(
                "/api/claims/" + claimNumber + "/required-documents/" + checkId + "/waive",
                claimantBearer(), "{\"rationale\":\"Already on file.\"}").statusCode());
        assertEquals(403, postJson(
                "/api/claims/" + claimNumber + "/required-documents/" + checkId + "/link",
                claimantBearer(), "{\"attachmentId\":1}").statusCode());
        assertEquals(404, postJson(
                "/api/claims/" + claimNumber + "/required-documents/" + checkId + "/waive",
                JwtTestConfig.tokenFor(SUB_L1_TWO, "adjuster_l1"),
                "{\"rationale\":\"Already on file.\"}").statusCode());
        assertEquals(404, postJson(
                "/api/claims/" + claimNumber + "/required-documents/" + checkId + "/link",
                JwtTestConfig.tokenFor(SUB_L1_TWO, "adjuster_l1"),
                "{\"attachmentId\":1}").statusCode());

        HttpResponse<String> waived = postJson(
                "/api/claims/" + claimNumber + "/required-documents/" + checkId + "/waive",
                bearer, "{\"rationale\":\"ID verified at intake.\"}");
        assertEquals(200, waived.statusCode(), waived.body());
        assertTrue(waived.body().contains("\"status\":\"WAIVED\""), waived.body());
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'DOC_WAIVED' "
                + "AND entity_id = ? AND rationale = 'ID verified at intake.'",
                idOf(claimNumber)));
    }

    // --- docKey auto-link ---------------------------------------------------------

    @Test
    void adjusterAttachWithDocKeyAutoLinks() throws Exception {
        String claimNumber = fileFnol("POL-20002", "Grace Hopper",
                "grace.hopper@example.test");
        String bearer = holderBearer(claimNumber);
        attachAs(bearer, claimNumber, "PHOTOS");
        assertEquals(1, count("SELECT count(*) FROM claim_document_check cdc "
                + "JOIN required_document rd ON rd.id = cdc.required_document_id "
                + "WHERE cdc.claim_id = ? AND rd.doc_key = 'PHOTOS' AND cdc.status = 'RECEIVED'",
                idOf(claimNumber)));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'DOC_LINKED' "
                + "AND entity_id = ?", idOf(claimNumber)));
        String staged = get("/api/claims/" + claimNumber + "/staged", bearer).body();
        assertTrue(staged.contains("\"documentsReceived\":1"), staged);
    }

    @Test
    void unknownDocKeyIs400NamingValidKeys() throws Exception {
        String claimNumber = fileFnol("POL-20002", "Grace Hopper",
                "grace.hopper@example.test");
        HttpResponse<String> response = postFile(
                "/api/claims/" + claimNumber + "/attachments", holderBearer(claimNumber),
                "estimate.png", "Estimate", "image/png", PNG, "BOGUS_KEY");
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("PHOTOS"), response.body());
        assertTrue(response.body().contains("ESTIMATE"), response.body());
        assertTrue(response.body().contains("RC_COPY"), response.body());
    }

    @Test
    void claimantUploadWithDocKeyAutoLinksOnNeedInfo() throws Exception {
        String claimNumber = fileFnol("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        String bearer = holderBearer(claimNumber);
        assertEquals(200, postJson("/api/claims/" + claimNumber + "/review", bearer,
                "{\"action\":\"NEED_INFO\",\"requestedItems\":\"Upload the final bill.\"}")
                .statusCode());

        HttpResponse<String> uploaded = postFile(
                "/api/claims/" + claimNumber + "/documents", claimantBearer(),
                "bill.png", "Final bill", "image/png", PNG, "FINAL_BILL");
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        assertEquals(1, count("SELECT count(*) FROM claim_document_check cdc "
                + "JOIN required_document rd ON rd.id = cdc.required_document_id "
                + "WHERE cdc.claim_id = ? AND rd.doc_key = 'FINAL_BILL' "
                + "AND cdc.status = 'RECEIVED'", idOf(claimNumber)));

        HttpResponse<String> badKey = postFile(
                "/api/claims/" + claimNumber + "/documents", claimantBearer(),
                "bill.png", "Final bill", "image/png", PNG, "WRONG_KEY");
        assertEquals(400, badKey.statusCode(), badKey.body());
        assertTrue(badKey.body().contains("DISCHARGE_SUMMARY"), badKey.body());
    }

    @Test
    void fnolWithDocKeyAutoLinksTheFirstPhoto() throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipartWithDocKey(Map.of(
                        "policyNumber", "POL-20002",
                        "holderName", "Grace Hopper",
                        "holderEmail", "grace.hopper@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "Manchester",
                        "lossDescription", "Rear-ended at a roundabout."),
                        "RC_COPY"));
        assertEquals(201, response.statusCode(), response.body());
        String claimNumber = response.body().replaceAll(
                ".*\"claimNumber\":\"([^\"]+)\".*", "$1");
        assertEquals(1, count("SELECT count(*) FROM claim_document_check cdc "
                + "JOIN required_document rd ON rd.id = cdc.required_document_id "
                + "WHERE cdc.claim_id = ? AND rd.doc_key = 'RC_COPY' "
                + "AND cdc.status = 'RECEIVED'", idOf(claimNumber)));
        assertTrue(response.body().contains("\"documentsReceived\":1"), response.body());
    }

    // --- helpers ---------------------------------------------------------------

    private String fileFnol(String policyNumber, String holderName, String holderEmail)
            throws Exception {
        String lossDate = "2026-08-" + String.format("%02d",
                LOSS_DAY.getAndIncrement() % 27 + 1);
        HttpResponse<String> response = post("/api/claims",
                JwtTestConfig.tokenFor(CLAIMANT, "claimant"),
                multipart(Map.of(
                        "policyNumber", policyNumber,
                        "holderName", holderName,
                        "holderEmail", holderEmail,
                        "lossDate", lossDate,
                        "lossLocation", "London",
                        "lossDescription", "Loss for the required-documents checklist."),
                        true));
        assertEquals(201, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
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

    private Long checkIdOf(String claimNumber, String docKey) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT cdc.id FROM claim_document_check cdc "
                        + "JOIN required_document rd ON rd.id = cdc.required_document_id "
                        + "JOIN claim c ON c.id = cdc.claim_id "
                        + "WHERE c.claim_number = ? AND rd.doc_key = ?",
                Long.class, claimNumber, docKey);
        if (value == null) {
            throw new IllegalStateException("no check for " + docKey + " on " + claimNumber);
        }
        return value;
    }

    private Long attachAs(String bearer, String claimNumber, String docKey) throws Exception {
        HttpResponse<String> attached = postFile(
                "/api/claims/" + claimNumber + "/attachments", bearer,
                "evidence.png", "Evidence", "image/png", PNG, docKey);
        assertEquals(200, attached.statusCode(), attached.body());
        Long attachmentId = Long.parseLong(
                attached.body().replaceAll(".*\"id\":(\\d+).*", "$1"));
        // The /full view echoes the attachment id first — cross-check the parse.
        String full = get("/api/claims/" + claimNumber + "/full", bearer).body();
        assertTrue(full.contains("\"id\":" + attachmentId), full);
        return attachmentId;
    }

    private Long idOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM claim WHERE claim_number = ?", Long.class, claimNumber);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path)).GET();
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

    private HttpResponse<String> postFile(String path, String bearer, String filename,
            String label, String contentType, byte[] bytes, String docKey) throws Exception {
        String partBoundary = "----RequiredDocsFile19";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + partBoundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write(("\r\n--" + partBoundary + "\r\n"
                + "Content-Disposition: form-data; name=\"label\"\r\n\r\n"
                + label + "\r\n").getBytes(StandardCharsets.UTF_8));
        if (docKey != null) {
            out.write(("--" + partBoundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"docKey\"\r\n\r\n"
                    + docKey + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + partBoundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path))
                .header("Content-Type", "multipart/form-data; boundary=" + partBoundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] multipart(Map<String, String> fields, boolean withPhoto) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Map.Entry<String, String> field : fields.entrySet()) {
                out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + field.getKey()
                        + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write((field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            if (withPhoto) {
                out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"photos\"; filename=\"damage.png\"\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(PNG);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] multipartWithDocKey(Map<String, String> fields, String docKey) {
        try {
            Map<String, String> ordered = new LinkedHashMap<>(fields);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Map.Entry<String, String> field : ordered.entrySet()) {
                out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + field.getKey()
                        + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write((field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"docKey\"\r\n\r\n"
                    + docKey + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"photos\"; filename=\"rc.png\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(PNG);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
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
}
