package com.claims.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * R3 acceptance: the supervisor-scoped JSON scrape. A supervisor sees an incrementing
 * FNOL counter after a filing; anonymous and claimant callers are denied (401/403).
 * Health and readiness stay public.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----MetricsTestBoundary3";
    private static final String SUB_SUPERVISOR = "10000000-0000-0000-0000-000000000004";

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void supervisorScrapesAnIncrementingFnolCounter() throws Exception {
        long before = fnolTotal(supervisorBearer());
        fileHomeFnol("sub-metrics-1");
        long after = fnolTotal(supervisorBearer());
        assertTrue(after > before,
                "claims_fnol_total must increment after an FNOL (" + before + " -> " + after + ")");

        String body = get("/api/metrics", supervisorBearer()).body();
        assertTrue(body.contains("claims_queue_depth"), body);
        assertTrue(body.contains("claims_decisions_total"), body);
        assertTrue(body.contains("UNDER_REVIEW"), body);
    }

    @Test
    void anonymousAndClaimantAreDeniedButHealthStaysPublic() throws Exception {
        assertEquals(401, get("/api/metrics", null).statusCode());
        assertEquals(403, get("/api/metrics",
                JwtTestConfig.tokenFor("sub-claimant-m", "claimant")).statusCode());
        assertEquals(403, get("/api/metrics",
                JwtTestConfig.tokenFor("10000000-0000-0000-0000-000000000001",
                        "adjuster_l1")).statusCode());
        assertEquals(200, get("/api/health", null).statusCode());
        assertEquals(200, get("/api/ready", null).statusCode());
    }

    // --- helpers ---------------------------------------------------------------

    private long fnolTotal(String bearer) throws Exception {
        HttpResponse<String> response = get("/api/metrics", bearer);
        assertEquals(200, response.statusCode(), response.body());
        String marker = "\"claims_fnol_total\":";
        int at = response.body().indexOf(marker);
        assertTrue(at >= 0, "scrape must carry claims_fnol_total: " + response.body());
        // The value may be last in the object (no trailing comma): scan to the first
        // delimiter of any kind.
        int start = at + marker.length();
        int end = start;
        while (end < response.body().length()
                && (Character.isDigit(response.body().charAt(end))
                        || response.body().charAt(end) == '-')) {
            end++;
        }
        assertTrue(end > start, "claims_fnol_total must carry a number: " + response.body());
        return Long.parseLong(response.body().substring(start, end).trim());
    }

    private void fileHomeFnol(String claimantSub) throws Exception {
        HttpResponse<String> response = post("/api/claims",
                JwtTestConfig.tokenFor(claimantSub, "claimant"),
                multipart(Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", "Storm damage to the property.")));
        assertEquals(201, response.statusCode(), response.body());
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
                out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
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

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }

    private String supervisorBearer() {
        return JwtTestConfig.tokenFor(SUB_SUPERVISOR, "supervisor");
    }
}
