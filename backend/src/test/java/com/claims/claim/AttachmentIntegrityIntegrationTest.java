package com.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.security.MessageDigest;
import java.util.List;
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
 * S1 acceptance: PDF evidence end-to-end with integrity pins. FNOL with a
 * minimal 2-page PDF stores sha256 (64 hex) + size_bytes; adjuster attach
 * and claimant NEED_INFO uploads pin too; legacy-NULL rows still download;
 * a tampered file (bytes overwritten on disk) fails to download with 404.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AttachmentIntegrityIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----AttachmentIntegrityBoundary18";

    private static final String SUB_L1_ONE = "10000000-0000-0000-0000-000000000001";
    private static final String CLAIMANT = "sub-claimant-integrity";

    private static final Path UPLOADS = createUploadsDir();

    private static Path createUploadsDir() {
        try {
            return Files.createTempDirectory("claims-integrity-test-uploads");
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
    void fnolWithPdfStoresShaAndSize() throws Exception {
        byte[] pdf = twoPagePdf();
        String claimNumber = fileFnolWith("discharge.pdf", "application/pdf", pdf);
        Long claimId = idOf(claimNumber);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT sha256, size_bytes, content_type FROM attachment WHERE claim_id = ?",
                claimId);
        assertNotNull(row.get("sha256"), "FNOL upload must pin sha256");
        assertTrue(row.get("sha256").toString().matches("[0-9a-f]{64}"),
                "sha256 is 64 lowercase hex: " + row.get("sha256"));
        assertEquals((long) pdf.length, ((Number) row.get("size_bytes")).longValue());
        assertEquals(sha256Hex(pdf), row.get("sha256").toString());
        assertEquals("application/pdf", row.get("content_type"));

        // Download serves the same bytes.
        Long attachmentId = attachmentIdOf(claimNumber);
        HttpResponse<byte[]> download = getBytes(
                "/api/claims/" + claimNumber + "/attachments/" + attachmentId,
                adjusterOneBearer());
        assertEquals(200, download.statusCode(), "pinned PDF must download");
        assertTrue(java.util.Arrays.equals(pdf, download.body()));
    }

    @Test
    void adjusterAttachAndNeedInfoUploadPinToo() throws Exception {
        String claimNumber = fileFnolWith("photo.png", "image/png", png());
        Long claimId = idOf(claimNumber);
        String holderBearer = holderBearer(claimNumber);

        // Adjuster attach.
        byte[] bill = twoPagePdf();
        HttpResponse<String> attached = postFile(
                "/api/claims/" + claimNumber + "/attachments", holderBearer,
                "bill.pdf", "Hospital bill", "application/pdf", bill, null);
        assertEquals(200, attached.statusCode(), attached.body());
        assertEquals(2, count("SELECT count(*) FROM attachment WHERE claim_id = ?", claimId));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT sha256, size_bytes FROM attachment WHERE claim_id = ? ORDER BY id",
                claimId)) {
            assertNotNull(row.get("sha256"), "every new row pins sha256: " + row);
            assertNotNull(row.get("size_bytes"), "every new row pins size: " + row);
        }

        // Drive to NEED_INFO, then the claimant uploads.
        assertEquals(200, postJson("/api/claims/" + claimNumber + "/review", holderBearer,
                "{\"action\":\"NEED_INFO\",\"requestedItems\":\"Upload the bill.\"}")
                .statusCode());
        byte[] extra = twoPagePdf();
        HttpResponse<String> doc = postFile(
                "/api/claims/" + claimNumber + "/documents", claimantBearer(),
                "extra.pdf", "Extra bill", "application/pdf", extra, null);
        assertEquals(200, doc.statusCode(), doc.body());
        assertEquals(3, count("SELECT count(*) FROM attachment WHERE claim_id = ?", claimId));
        assertEquals(0, count(
                "SELECT count(*) FROM attachment WHERE claim_id = ? "
                        + "AND (sha256 IS NULL OR size_bytes IS NULL)", claimId),
                "adjuster + NEED_INFO uploads all pin sha/size");
    }

    @Test
    void legacyNullRowsStillDownload() throws Exception {
        String claimNumber = fileFnolWith("photo.png", "image/png", png());
        Long attachmentId = attachmentIdOf(claimNumber);

        // Simulate a pre-V18 row: strip the pin.
        jdbcTemplate.update(
                "UPDATE attachment SET sha256 = NULL, size_bytes = NULL WHERE id = ?",
                attachmentId);

        HttpResponse<byte[]> download = getBytes(
                "/api/claims/" + claimNumber + "/attachments/" + attachmentId,
                adjusterOneBearer());
        assertEquals(200, download.statusCode(), "legacy-NULL rows still download");
        assertTrue(java.util.Arrays.equals(png(), download.body()));
    }

    @Test
    void tamperedFileFailsToDownloadWith404() throws Exception {
        byte[] pdf = twoPagePdf();
        String claimNumber = fileFnolWith("discharge.pdf", "application/pdf", pdf);
        Long claimId = idOf(claimNumber);
        Long attachmentId = attachmentIdOf(claimNumber);

        // Overwrite the bytes on disk with same-length garbage.
        String key = jdbcTemplate.queryForObject(
                "SELECT storage_path FROM attachment WHERE id = ?", String.class, attachmentId);
        assertNotNull(key);
        byte[] tampered = pdf.clone();
        tampered[10] = (byte) (tampered[10] ^ 0xFF);
        tampered[20] = (byte) (tampered[20] ^ 0xFF);
        Files.write(UPLOADS.resolve(key), tampered);
        assertEquals(claimId, idOf(claimNumber), "sanity: claim untouched");

        HttpResponse<byte[]> download = getBytes(
                "/api/claims/" + claimNumber + "/attachments/" + attachmentId,
                adjusterOneBearer());
        assertEquals(404, download.statusCode(),
                "tampered bytes must never be served (404, never corrupt content)");
    }

    @Test
    void spoofedExecutableNamedPdfIsRejected() throws Exception {
        byte[] exe = new byte[] {0x4D, 0x5A, (byte) 0x90, 0, 3, 0, 0, 0, 1, 2, 3, 4};
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", "Storm damage"),
                        List.of(new NamedFile("bill.pdf", "application/pdf", exe))));
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("must be image or PDF files"), response.body());
    }

    // --- fixtures ------------------------------------------------------------

    /** Minimal 2-page PDF (just enough to be real PDF bytes). */
    private static byte[] twoPagePdf() {
        return ("%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>\nendobj\n"
                + "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n"
                + "4 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n"
                + "trailer\n<< /Root 1 0 R >>\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] png() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String fileFnolWith(String filename, String contentType, byte[] bytes)
            throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", "Kitchen flooded after a pipe burst."),
                        List.of(new NamedFile(filename, contentType, bytes))));
        assertEquals(201, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    private Long idOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM claim WHERE claim_number = ?", Long.class, claimNumber);
    }

    private Long attachmentIdOf(String claimNumber) throws Exception {
        String full = get("/api/claims/" + claimNumber + "/full", adjusterOneBearer()).body();
        return Long.parseLong(full.replaceAll(".*\"attachments\":\\[\\{\"id\":(\\d+).*", "$1"));
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private record NamedFile(String filename, String contentType, byte[] bytes) {
    }

    private static byte[] multipart(Map<String, String> fields, List<NamedFile> files) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Map.Entry<String, String> field : fields.entrySet()) {
                out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + field.getKey()
                        + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write((field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            for (NamedFile file : files) {
                out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"photos\"; filename=\""
                        + file.filename() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Type: " + file.contentType() + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(file.bytes());
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Multipart upload for adjuster attach / NEED_INFO document endpoints. */
    private HttpResponse<String> postFile(String path, String bearer, String filename,
            String label, String contentType, byte[] bytes, Long verificationId)
            throws Exception {
        String partBoundary = "----AttachmentIntegrityDoc9";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String fileHead = "--" + partBoundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        out.write(fileHead.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        String tail = "\r\n--" + partBoundary + "\r\n"
                + "Content-Disposition: form-data; name=\"label\"\r\n\r\n"
                + label + "\r\n";
        if (verificationId != null) {
            tail += "--" + partBoundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"verificationId\"\r\n\r\n"
                    + verificationId + "\r\n";
        }
        out.write((tail + "--" + partBoundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path))
                .header("Content-Type", "multipart/form-data; boundary=" + partBoundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
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

    private HttpResponse<byte[]> getBytes(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
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

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }

    private String claimantBearer() {
        return JwtTestConfig.tokenFor(CLAIMANT, "claimant");
    }

    private String adjusterOneBearer() {
        return JwtTestConfig.tokenFor(SUB_L1_ONE, "adjuster_l1");
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
}
