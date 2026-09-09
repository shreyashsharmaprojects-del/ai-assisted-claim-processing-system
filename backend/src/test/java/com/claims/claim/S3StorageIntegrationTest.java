package com.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.claims.TestcontainersConfiguration;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * S2: the S3 backend behind a fake-S3 {@link HttpServer} stub (no Testcontainers
 * MinIO — the image is not cached and the build is offline). Runs with
 * {@code claims.storage.backend=s3} pointed at the stub: asserts the SigV4
 * {@code Authorization} header is present, keys keep the
 * {@code {claimId}/{uuid}{ext}} shape, and store → exists → load → delete
 * round-trips. The filesystem default suite ({@link StorageSeamIntegrationTest})
 * is unchanged.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3StorageIntegrationTest extends ClaimTableResettingTest {

    private static final byte[] PNG = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};

    /** In-memory fake-S3: key → bytes. Path-style: /{bucket}/{key}. */
    private static final Map<String, byte[]> OBJECTS = new ConcurrentHashMap<>();
    /** Every Authorization header the stub observed (SigV4 assertion). */
    private static final List<String> AUTHORIZATIONS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private static HttpServer fakeS3;
    private static volatile String fakeS3Endpoint;

    static {
        // Start the stub BEFORE the Spring context resolves claims.s3.endpoint:
        // DynamicPropertySource suppliers read this field at context build time.
        try {
            OBJECTS.clear();
            AUTHORIZATIONS.clear();
            fakeS3 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            fakeS3.createContext("/", S3StorageIntegrationTest::handleFakeS3);
            fakeS3.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            fakeS3.start();
            fakeS3Endpoint = "http://127.0.0.1:" + fakeS3.getAddress().getPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not start fake-S3 stub", ex);
        }
    }

    @Autowired
    private PhotoStorage photoStorage;
    @Autowired
    private org.springframework.core.env.Environment environment;

    @BeforeAll
    static void clearFakeS3() {
        OBJECTS.clear();
        AUTHORIZATIONS.clear();
    }

    @AfterAll
    static void stopFakeS3() {
        if (fakeS3 != null) {
            fakeS3.stop(0);
        }
    }

    @DynamicPropertySource
    static void s3BackendProps(DynamicPropertyRegistry registry) {
        registry.add("claims.storage.backend", () -> "s3");
        registry.add("claims.s3.endpoint", () -> fakeS3Endpoint);
        registry.add("claims.s3.bucket", () -> "claims-evidence");
        registry.add("claims.s3.region", () -> "us-east-1");
        registry.add("claims.s3.access-key", () -> "test-key");
        registry.add("claims.s3.secret-key", () -> "test-secret");
    }

    @Test
    void s3BackendBeanIsActive() {
        assertTrue(photoStorage instanceof S3PhotoStorage,
                "claims.storage.backend=s3 must activate the S3 bean, got "
                        + photoStorage.getClass());
    }

    @Test
    void storeSendsSigV4AndKeyShape() {
        S3PhotoStorage s3 = (S3PhotoStorage) photoStorage;
        OBJECTS.clear();
        AUTHORIZATIONS.clear();

        List<StoredPhoto> stored = s3.store(
                List.of(mockFile("kitchen.png", "image/png", PNG)), 4242L);
        assertEquals(1, stored.size());
        String key = stored.get(0).storagePath();
        assertTrue(key.matches("4242/[0-9a-f\\-]+\\.png"),
                "key shape is {claimId}/{uuid}{ext}: " + key);
        assertTrue(key.startsWith("4242/"), "key is namespaced by claim id: " + key);
        assertNotNull(OBJECTS.get(key), "PUT must land the key in the bucket");

        assertTrue(!AUTHORIZATIONS.isEmpty(), "stub must observe requests");
        for (String auth : AUTHORIZATIONS) {
            assertTrue(auth != null && auth.startsWith("AWS4-HMAC-SHA256 "),
                    "SigV4 Authorization header present: " + auth);
            assertTrue(auth.contains("SignedHeaders=") && auth.contains("Signature="),
                    "SigV4 header carries signed headers + signature: " + auth);
        }
        AUTHORIZATIONS.clear();

        assertEquals(64, stored.get(0).sha256().length());
        assertTrue(stored.get(0).sha256().matches("[0-9a-f]{64}"));
        assertEquals(PNG.length, stored.get(0).sizeBytes());
    }

    @Test
    void storeExistsLoadDeleteRoundTrip() {
        S3PhotoStorage s3 = (S3PhotoStorage) photoStorage;
        OBJECTS.clear();

        List<StoredPhoto> stored = s3.store(
                List.of(mockFile("bill.pdf", "application/pdf", minimalPdf())), 9001L);
        String key = stored.get(0).storagePath();
        assertTrue(s3.exists(key), "key exists after store");
        assertTrue(!s3.exists("9001/does-not-exist.png"), "missing key reports absent");

        byte[] loaded = s3.load(key);
        assertTrue(java.util.Arrays.equals(minimalPdf(), loaded),
                "load returns the stored bytes");

        s3.deleteClaimDir(9001L);
        assertTrue(!s3.exists(key), "deleteClaimDir removes the claim prefix");
    }

    @Test
    void s3ValidationMatchesFilesystemRules() {
        S3PhotoStorage s3 = (S3PhotoStorage) photoStorage;
        // Spoofed content rejected with the same S1 message.
        com.claims.api.FnolValidationException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.claims.api.FnolValidationException.class,
                        () -> s3.store(List.of(mockFile("damage.png", "image/png",
                                "not an image".getBytes(StandardCharsets.UTF_8))), 9002L));
        assertTrue(ex.getMessage().contains("must be image or PDF files"), ex.getMessage());
    }

    @Test
    void fnolStoresToS3EndToEnd() throws Exception {
        OBJECTS.clear();
        HttpClient http = HttpClient.newHttpClient();
        String boundary = "----S3FnolBoundary99";
        byte[] body = multipart(boundary, true);
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port() + "/api/claims"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization",
                        "Bearer " + JwtTestConfig.tokenFor("sub-s3-fnol-1", "claimant"))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        java.net.http.HttpResponse<String> response =
                http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        String claimNumber = response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
        Long claimId = jdbcTemplate.queryForObject(
                "SELECT id FROM claim WHERE claim_number = ?", Long.class, claimNumber);
        String key = jdbcTemplate.queryForObject(
                "SELECT storage_path FROM attachment WHERE claim_id = ?", String.class, claimId);
        assertTrue(key.matches("\\d+/[0-9a-f\\-]+\\.png"), "FNOL stores the S3 key: " + key);
        assertNotNull(OBJECTS.get(key), "FNOL bytes land in the bucket");
    }

    // --- fake-S3 stub ----------------------------------------------------------

    private static void handleFakeS3(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null) {
            AUTHORIZATIONS.add(auth);
        }
        // Path-style: /{bucket}/{key...}; query list-type=2&prefix= for LIST.
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/", 3);
        String bucket = parts.length > 1 ? parts[1] : "";
        String key = parts.length > 2 ? parts[2] : "";
        String query = exchange.getRequestURI().getRawQuery();
        byte[] responseBytes;
        int status;
        switch (exchange.getRequestMethod()) {
            case "PUT" -> {
                if (!"claims-evidence".equals(bucket)) {
                    status = 404;
                    responseBytes = "<Error><Code>NoSuchBucket</Code></Error>"
                            .getBytes(StandardCharsets.UTF_8);
                } else {
                    OBJECTS.put(key, exchange.getRequestBody().readAllBytes());
                    status = 200;
                    responseBytes = new byte[0];
                }
            }
            case "GET" -> {
                if (query != null && query.contains("list-type=2")) {
                    String prefix = "";
                    for (String param : query.split("&")) {
                        if (param.startsWith("prefix=")) {
                            prefix = java.net.URLDecoder.decode(param.substring(7),
                                    StandardCharsets.UTF_8);
                        }
                    }
                    StringBuilder xml = new StringBuilder(
                            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                    + "<ListBucketResult><Name>claims-evidence</Name>");
                    for (String k : OBJECTS.keySet()) {
                        if (k.startsWith(prefix)) {
                            xml.append("<Contents><Key>").append(k)
                                    .append("</Key><Size>").append(OBJECTS.get(k).length)
                                    .append("</Size></Contents>");
                        }
                    }
                    xml.append("</ListBucketResult>");
                    status = 200;
                    responseBytes = xml.toString().getBytes(StandardCharsets.UTF_8);
                } else if (OBJECTS.containsKey(key)) {
                    status = 200;
                    responseBytes = OBJECTS.get(key);
                } else {
                    status = 404;
                    responseBytes = "<Error><Code>NoSuchKey</Code></Error>"
                            .getBytes(StandardCharsets.UTF_8);
                }
            }
            case "HEAD" -> {
                if (OBJECTS.containsKey(key)) {
                    status = 200;
                    responseBytes = new byte[0];
                } else {
                    status = 404;
                    responseBytes = new byte[0];
                }
            }
            case "DELETE" -> {
                OBJECTS.remove(key);
                status = 204;
                responseBytes = new byte[0];
            }
            default -> {
                status = 400;
                responseBytes = new byte[0];
            }
        }
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, responseBytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(responseBytes);
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static MockMultipartFile mockFile(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("photos", name, contentType, bytes);
    }

    private static byte[] minimalPdf() {
        return ("%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private byte[] multipart(String boundary, boolean withPhoto) throws IOException {
        Map<String, String> fields = new HashMap<>();
        fields.put("policyNumber", "POL-10001");
        fields.put("holderName", "Ada Lovelace");
        fields.put("holderEmail", "ada.lovelace@example.test");
        fields.put("lossDate", "2026-09-01");
        fields.put("lossLocation", "London");
        fields.put("lossDescription", "Kitchen flooded after a pipe burst.");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"" + field.getKey()
                    + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write((field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (withPhoto) {
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"photos\"; "
                    + "filename=\"kitchen.png\"\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(PNG);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }
}
