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
 * R5 acceptance: the storage seam. FNOL with a photo stores an object key
 * ({@code {claimId}/{uuid}{ext}}) — never an absolute path — in
 * {@code attachment.storage_path}; downloads are unchanged; a legacy absolute-path row
 * resolves (V11 converts it to key form). Rollback-delete still cleans the claim dir.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StorageSeamIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----StorageSeamBoundary11";
    private static final byte[] PHOTO = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
    private static final String SUB_L1_ONE = "10000000-0000-0000-0000-000000000001";

    private static final Path UPLOADS = createUploadsDir();

    static {
        // Keep the temp dir alive for the class; per-test cleanup happens via deleteClaimDir.
    }

    private static Path createUploadsDir() {
        try {
            return Files.createTempDirectory("claims-storage-seam-test-uploads");
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
    @Autowired
    private PhotoStorage photoStorage;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void fnolPhotoStoresAnObjectKeyNotAnAbsolutePath() throws Exception {
        String claimNumber = fileHomeFnolWithPhoto("sub-storage-1");
        Long claimId = idOf(claimNumber);
        String storagePath = jdbcTemplate.queryForObject(
                "SELECT storage_path FROM attachment WHERE claim_id = ?", String.class, claimId);

        assertTrue(storagePath != null && !storagePath.startsWith("/"),
                "storage_path must be a key, not an absolute path: " + storagePath);
        assertTrue(storagePath.matches("\\d+/[0-9a-f\\-]+\\.png"),
                "key shape is {claimId}/{uuid}{ext}: " + storagePath);
        assertTrue(storagePath.startsWith(claimId + "/"),
                "key is namespaced by claim id: " + storagePath);
        assertTrue(Files.exists(UPLOADS.resolve(storagePath)),
                "file resolves under the uploads base dir");
    }

    @Test
    void downloadServesTheSameBytesAfterTheSeam() throws Exception {
        String claimNumber = fileHomeFnolWithPhoto("sub-storage-2");
        String full = get("/api/claims/" + claimNumber + "/full", adjusterOneBearer()).body();
        Long attachmentId = Long.parseLong(
                full.replaceAll(".*\"attachments\":\\[\\{\"id\":(\\d+).*", "$1"));

        HttpResponse<byte[]> download = getBytes(
                "/api/claims/" + claimNumber + "/attachments/" + attachmentId,
                adjusterOneBearer());
        assertEquals(200, download.statusCode());
        assertTrue(java.util.Arrays.equals(PHOTO, download.body()),
                "downloaded bytes equal the upload");
    }

    @Test
    void legacyAbsolutePathRowStillServesAndMigratesToKeyForm() throws Exception {
        String claimNumber = fileHomeFnolWithPhoto("sub-storage-3");
        Long claimId = idOf(claimNumber);
        String key = jdbcTemplate.queryForObject(
                "SELECT storage_path FROM attachment WHERE claim_id = ?", String.class, claimId);

        // Simulate a pre-V11 row: rewrite the key as the absolute path it used to be.
        String legacyAbsolute = UPLOADS.resolve(key).toString();
        jdbcTemplate.update("UPDATE attachment SET storage_path = ? WHERE claim_id = ?",
                legacyAbsolute, claimId);

        // Downloads still resolve (dual-read), so a half-migrated DB keeps serving.
        String full = get("/api/claims/" + claimNumber + "/full", adjusterOneBearer()).body();
        Long attachmentId = Long.parseLong(
                full.replaceAll(".*\"attachments\":\\[\\{\"id\":(\\d+).*", "$1"));
        HttpResponse<byte[]> download = getBytes(
                "/api/claims/" + claimNumber + "/attachments/" + attachmentId,
                adjusterOneBearer());
        assertEquals(200, download.statusCode());
        assertTrue(java.util.Arrays.equals(PHOTO, download.body()));

        // V11 converts the legacy absolute path back to key form (null-safe).
        jdbcTemplate.execute("""
                UPDATE attachment
                SET storage_path = regexp_replace(storage_path, '^.*([^/]+/[^/]+)$', '\\1')
                WHERE storage_path IS NOT NULL
                  AND storage_path LIKE '/%'
                  AND storage_path LIKE '%/%/%'
                """);
        String migrated = jdbcTemplate.queryForObject(
                "SELECT storage_path FROM attachment WHERE claim_id = ?", String.class, claimId);
        assertEquals(key, migrated, "V11 restores the object key");
    }

    @Test
    void photoStorageBeanIsTheFilesystemImplementation() {
        assertTrue(photoStorage instanceof FilesystemPhotoStorage,
                "the only PhotoStorage is the filesystem one (bean name unchanged)");
    }

    @Test
    void rollbackDeleteCleansTheClaimDir() throws Exception {
        String claimNumber = fileHomeFnolWithPhoto("sub-storage-4");
        Long claimId = idOf(claimNumber);
        assertTrue(Files.exists(UPLOADS.resolve(claimId.toString())),
                "claim dir exists after FNOL");
        photoStorage.deleteClaimDir(claimId);
        assertTrue(!Files.exists(UPLOADS.resolve(claimId.toString())),
                "rollback-delete removes the claim dir");
    }

    // --- helpers ---------------------------------------------------------------

    private String fileHomeFnolWithPhoto(String subject) throws Exception {
        HttpResponse<String> response = post("/api/claims",
                JwtTestConfig.tokenFor(subject, "claimant"),
                multipart(Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", "Kitchen flooded after a pipe burst."),
                        true));
        assertEquals(201, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    private Long idOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM claim WHERE claim_number = ?", Long.class, claimNumber);
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
                out.write(("Content-Disposition: form-data; name=\"photos\"; "
                        + "filename=\"kitchen.png\"\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(PHOTO);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
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

    private String adjusterOneBearer() {
        return JwtTestConfig.tokenFor(SUB_L1_ONE, "adjuster_l1");
    }
}
