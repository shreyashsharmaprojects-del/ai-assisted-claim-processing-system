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
import java.util.LinkedHashMap;
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
 * S4 acceptance: document metadata + supersede. Same-claim replacesId links
 * the row and renders "supersedes: &lt;name&gt;" on the timeline; cross-claim
 * replacesId is 404; a chain of 3 renders in order; docType is advisory and
 * independent of the S3 docKey auto-link.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AttachmentSupersedeIntegrationTest extends ClaimTableResettingTest {

    private static final byte[] PNG = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};

    private static final String CLAIMANT = "sub-claimant-supersede";

    private static final AtomicInteger LOSS_DAY = new AtomicInteger(11);

    private static final Path UPLOADS = createUploadsDir();

    private static Path createUploadsDir() {
        try {
            return Files.createTempDirectory("claims-supersede-test-uploads");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @org.springframework.test.context.DynamicPropertySource
    static void datasourceProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("claims.uploads.dir", () -> UPLOADS.toString());
    }

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void sameClaimReplaceLinksRowAndTimelineNamesTheOriginal() throws Exception {
        String claimNumber = fileFnol("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        String bearer = holderBearer(claimNumber);

        Long v1 = attachAs(bearer, claimNumber, "bill-v1.png", "Bill v1",
                Map.of("docType", "FINAL_BILL"));
        String full = get("/api/claims/" + claimNumber + "/full", bearer).body();
        assertTrue(full.contains("\"docType\":\"FINAL_BILL\""), full);

        Map<String, String> v2Parts = new LinkedHashMap<>();
        v2Parts.put("docType", "FINAL_BILL");
        v2Parts.put("replacesId", String.valueOf(v1));
        Long v2 = attachAs(bearer, claimNumber, "bill-v2.png", "Bill v2", v2Parts);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT doc_type, replaces_attachment_id FROM attachment WHERE id = ?", v2);
        assertEquals("FINAL_BILL", row.get("doc_type"));
        assertEquals(v1.longValue(),
                ((Number) row.get("replaces_attachment_id")).longValue());

        String timeline = get("/api/claims/" + claimNumber + "/timeline", bearer).body();
        assertTrue(timeline.contains("supersedes: bill-v1.png"),
                "timeline must name the superseded file: " + timeline);
    }

    @Test
    void crossClaimReplacesIdIs404AndUnknownIdIs404() throws Exception {
        String claimNumber = fileFnol("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        String other = fileFnol("POL-30001", "Ravi Menon", "ravi.menon@example.test");
        Long foreign = attachAs(holderBearer(other), other, "bill.png", "Bill",
                Map.of());

        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("replacesId", String.valueOf(foreign));
        assertEquals(404, postFile("/api/claims/" + claimNumber + "/attachments",
                holderBearer(claimNumber), "bill-v2.png", "Bill v2", "image/png", PNG, parts)
                .statusCode(), "cross-claim replacesId must be 404, never a leak");

        Map<String, String> unknown = new LinkedHashMap<>();
        unknown.put("replacesId", "999999");
        assertEquals(404, postFile("/api/claims/" + claimNumber + "/attachments",
                holderBearer(claimNumber), "bill-v2.png", "Bill v2", "image/png", PNG, unknown)
                .statusCode(), "unknown replacesId must be 404");
    }

    @Test
    void docTypeOver60CharsIs400() throws Exception {
        String claimNumber = fileFnol("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("docType", "x".repeat(61));
        assertEquals(400, postFile("/api/claims/" + claimNumber + "/attachments",
                holderBearer(claimNumber), "bill.png", "Bill", "image/png", PNG, parts)
                .statusCode(), "docType over 60 chars must be 400");
        assertEquals(0, count("SELECT count(*) FROM attachment WHERE doc_type IS NOT NULL"),
                "rejected upload must persist nothing new beyond the FNOL photo");
    }

    @Test
    void docKeyAutoLinkStillWorksAlongsideDocType() throws Exception {
        String claimNumber = fileFnol("POL-20002", "Grace Hopper",
                "grace.hopper@example.test");
        String bearer = holderBearer(claimNumber);
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("docKey", "PHOTOS");
        parts.put("docType", "PHOTOS");
        Long attachmentId = attachAs(bearer, claimNumber, "damage.png", "Damage", parts);
        assertEquals(1, count("SELECT count(*) FROM claim_document_check cdc "
                + "JOIN required_document rd ON rd.id = cdc.required_document_id "
                + "WHERE cdc.claim_id = ? AND rd.doc_key = 'PHOTOS' "
                + "AND cdc.status = 'RECEIVED' AND cdc.attachment_id = ?",
                idOf(claimNumber), attachmentId),
                "S3 docKey auto-link must keep working when docType rides along");
    }

    @Test
    void chainOfThreeRendersInOrder() throws Exception {
        String claimNumber = fileFnol("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        String bearer = holderBearer(claimNumber);

        Long v1 = attachAs(bearer, claimNumber, "chain-v1.png", "Chain v1", Map.of());
        Map<String, String> v2Parts = new LinkedHashMap<>();
        v2Parts.put("replacesId", String.valueOf(v1));
        Long v2 = attachAs(bearer, claimNumber, "chain-v2.png", "Chain v2", v2Parts);
        Map<String, String> v3Parts = new LinkedHashMap<>();
        v3Parts.put("replacesId", String.valueOf(v2));
        attachAs(bearer, claimNumber, "chain-v3.png", "Chain v3", v3Parts);

        String timeline = get("/api/claims/" + claimNumber + "/timeline", bearer).body();
        int plain = timeline.indexOf("Attached: Chain v1");
        int second = timeline.indexOf("supersedes: chain-v1.png");
        int third = timeline.indexOf("supersedes: chain-v2.png");
        assertTrue(plain >= 0 && second >= 0 && third >= 0,
                "all three chain links must render: " + timeline);
        assertTrue(plain < second && second < third,
                "chain must render oldest-first: " + timeline);
    }

    @Test
    void claimantNeedInfoUploadCarriesDocTypeAndReplacesId() throws Exception {
        String claimNumber = fileFnol("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        String bearer = holderBearer(claimNumber);
        Long v1 = attachAs(bearer, claimNumber, "bill-v1.png", "Bill v1", Map.of());
        assertEquals(200, postJson("/api/claims/" + claimNumber + "/review", bearer,
                "{\"action\":\"NEED_INFO\",\"requestedItems\":\"Upload a clearer bill.\"}")
                .statusCode());

        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("docType", "FINAL_BILL");
        parts.put("replacesId", String.valueOf(v1));
        HttpResponse<String> uploaded = postFile("/api/claims/" + claimNumber + "/documents",
                claimantBearer(), "bill-v2.png", "Bill v2", "image/png", PNG, parts);
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        Long v2 = Long.parseLong(
                uploaded.body().replaceAll(".*\"id\":(\\d+).*", "$1"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT doc_type, replaces_attachment_id FROM attachment WHERE id = ?", v2);
        assertEquals("FINAL_BILL", row.get("doc_type"));
        assertEquals(v1.longValue(),
                ((Number) row.get("replaces_attachment_id")).longValue());

        // Cross-claim replacesId on the claimant path is 404 too.
        String other = fileFnol("POL-30001", "Ravi Menon", "ravi.menon@example.test");
        Long foreign = attachAs(holderBearer(other), other, "bill.png", "Bill", Map.of());
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("replacesId", String.valueOf(foreign));
        assertEquals(404, postFile("/api/claims/" + claimNumber + "/documents",
                claimantBearer(), "bill-v3.png", "Bill v3", "image/png", PNG, bad)
                .statusCode());
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
                        "lossDescription", "Loss for the supersede chain."),
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

    private Long attachAs(String bearer, String claimNumber, String filename,
            String label, Map<String, String> extraParts) throws Exception {
        HttpResponse<String> attached = postFile(
                "/api/claims/" + claimNumber + "/attachments", bearer,
                filename, label, "image/png", PNG, extraParts);
        assertEquals(200, attached.statusCode(), attached.body());
        Long attachmentId = Long.parseLong(
                attached.body().replaceAll(".*\"id\":(\\d+).*", "$1"));
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
                .header("Content-Type", "multipart/form-data; boundary=----SupersedeBoundary20")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postFile(String path, String bearer, String filename,
            String label, String contentType, byte[] bytes, Map<String, String> extraParts)
            throws Exception {
        String partBoundary = "----SupersedeFile20";
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
        for (Map.Entry<String, String> part : extraParts.entrySet()) {
            out.write(("--" + partBoundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + part.getKey()
                    + "\"\r\n\r\n" + part.getValue() + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
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
            String boundary = "----SupersedeBoundary20";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Map.Entry<String, String> field : fields.entrySet()) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + field.getKey()
                        + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write((field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            if (withPhoto) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"photos\"; filename=\"damage.png\"\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(PNG);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }

    private String claimantBearer() {
        return JwtTestConfig.tokenFor(CLAIMANT, "claimant");
    }
}
