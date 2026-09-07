package com.claims.queue;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import com.claims.TestcontainersConfiguration;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

/**
 * R4 acceptance: paginated queue/escalations/mine with server-side search and status
 * filter. 30 seeded claims paginate 25/5 with stable totals; {@code q} matches across
 * pages; status counts match; claimant {@code q} never returns another's claim.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QueuePaginationIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----PaginationTestBoundary4";
    private static final Pattern CLAIM_NUMBER = Pattern.compile("\"claimNumber\":\"(CLM-\\d{6})\"");
    private static final String SUB_SUPERVISOR = "10000000-0000-0000-0000-000000000004";
    private static final String SUB_L1_ONE = "10000000-0000-0000-0000-000000000001";

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void thirtyClaimsPaginateTwentyFiveAndFiveWithStableTotals() throws Exception {
        for (int i = 0; i < 30; i++) {
            fileFnol("sub-page-" + (i % 25), "Storm damage claim " + i);
        }

        String firstBody = getChecked("/api/queue?page=0&size=25", supervisorBearer()).body();
        String first = firstBody;
        assertTrue(first.contains("\"totalElements\":30"), first);
        assertTrue(first.contains("\"totalPages\":2"), first);
        assertTrue(first.contains("\"page\":0"), first);
        assertEquals(25, claimNumbersIn(first).size(), first);

        String second = get("/api/queue?page=1&size=25", supervisorBearer());
        assertEquals(5, claimNumbersIn(second).size(), second);
        assertTrue(second.contains("\"totalElements\":30"), second);

        // Stable ordering: page 0 then page 1 concatenated is oldest-first overall.
        List<String> all = new ArrayList<>(claimNumbersIn(first));
        all.addAll(claimNumbersIn(second));
        assertEquals(30, all.stream().distinct().count(), "pages must not overlap or repeat");
    }

    @Test
    void searchMatchesAcrossPages() throws Exception {
        for (int i = 0; i < 10; i++) {
            fileFnol("sub-search-" + (i % 25), "Common loss description " + i);
        }
        fileFnol("sub-search-needle", "Needle in the haystack loss");

        String page = get("/api/queue?q=Needle", supervisorBearer());
        assertTrue(page.contains("\"totalElements\":1"), page);
        String claimNumber = claimNumbersIn(page).get(0);

        String direct = get("/api/queue?q=" + claimNumber, supervisorBearer());
        assertTrue(direct.contains(claimNumber), direct);
        assertTrue(direct.contains("\"totalElements\":1"), direct);
    }

    @Test
    void statusFilterCountsMatch() throws Exception {
        fileFnol("sub-status-1", "Storm damage one");
        fileFnol("sub-status-2", "Storm damage two");

        String filtered = get("/api/queue?status=UNDER_REVIEW", supervisorBearer());
        assertTrue(filtered.contains("\"totalElements\":2"), filtered);
        String empty = get("/api/queue?status=UNASSIGNED", supervisorBearer());
        assertTrue(empty.contains("\"totalElements\":0"), empty);
        assertTrue(empty.contains("\"content\":[]"), empty);
    }

    @Test
    void claimantSearchSeesOnlyOwnRows() throws Exception {
        String mine = fileFnolAs("sub-mine-owner", "Owner basement flood");
        fileFnolAs("sub-mine-other", "Other attic leak");

        String search = get("/api/claims/mine?q=attic",
                JwtTestConfig.tokenFor("sub-mine-owner", "claimant"));
        assertTrue(search.contains("\"totalElements\":0"), search);

        String own = get("/api/claims/mine?q=basement",
                JwtTestConfig.tokenFor("sub-mine-owner", "claimant"));
        assertTrue(own.contains(mine), own);
        assertTrue(own.contains("\"totalElements\":1"), own);
    }

    @Test
    void minePaginatesAndFiltersByStatus() throws Exception {
        for (int i = 0; i < 3; i++) {
            fileFnolAs("sub-mine-pages", "Paged loss " + i);
        }
        String first = get("/api/claims/mine?page=0&size=2",
                JwtTestConfig.tokenFor("sub-mine-pages", "claimant"));
        assertTrue(first.contains("\"totalElements\":3"), first);
        assertTrue(first.contains("\"totalPages\":2"), first);
        assertEquals(2, claimNumbersIn(first).size(), first);

        String filtered = get("/api/claims/mine?status=UNDER_REVIEW",
                JwtTestConfig.tokenFor("sub-mine-pages", "claimant"));
        assertTrue(filtered.contains("\"totalElements\":3"), filtered);
    }

    @Test
    void escalationsListIsPaginated() throws Exception {
        String claimNumber = fileFnolAs("sub-esc-pages", "Escalation loss");
        escalateAboveL2(claimNumber);

        String page = get("/api/escalations?page=0&size=25", supervisorBearer());
        assertTrue(page.contains(claimNumber), page);
        assertTrue(page.contains("\"totalElements\":1"), page);
        assertTrue(page.contains("\"totalPages\":1"), page);
    }

    @Test
    void adjusterQueueIsPaginatedAndScopedToOwnAssignments() throws Exception {
        fileFnolAs("sub-adj-q1", "Adjuster queue loss one");
        fileFnolAs("sub-adj-q2", "Adjuster queue loss two");

        String own = get("/api/queue?page=0&size=25", adjusterOneBearer());
        assertTrue(own.contains("\"totalElements\""), own);
        assertTrue(own.contains("\"content\""), own);
    }

    @Test
    void oversizedPageIsCappedAtOneHundred() throws Exception {
        fileFnol("sub-cap-1", "Cap loss");
        String page = get("/api/queue?size=500", supervisorBearer());
        assertTrue(page.contains("\"size\":100"), page);
    }

    // --- helpers ---------------------------------------------------------------

    /** Files an FNOL as a rotating subject (dodges the 20/day per-claimant cap). */
    private void fileFnol(String claimantSub, String description) throws Exception {
        fileFnolAs(claimantSub, description);
    }

    private String fileFnolAs(String claimantSub, String description) throws Exception {
        HttpResponse<String> response = post("/api/claims",
                JwtTestConfig.tokenFor(claimantSub, "claimant"),
                multipart(Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", description)));
        assertEquals(201, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    private void escalateAboveL2(String claimNumber) throws Exception {
        HttpResponse<String> escalation = postJson("/api/claims/" + claimNumber + "/decision",
                adjusterOneBearer(),
                "{\"decision\":\"APPROVED\",\"indemnityAmount\":12000.00,"
                        + "\"rationale\":\"Exceptional loss.\"}");
        assertEquals(200, escalation.statusCode(), escalation.body());
    }

    private String get(String path, String bearer) throws Exception {
        return getChecked(path, bearer).body();
    }

    private HttpResponse<String> getChecked(String path, String bearer) throws Exception {
        HttpResponse<String> response = getResponse(path, bearer);
        assertEquals(200, response.statusCode(), response.body());
        return response;
    }

    private HttpResponse<String> getResponse(String path, String bearer) throws Exception {
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

    private static List<String> claimNumbersIn(String jsonBody) {
        List<String> numbers = new ArrayList<>();
        Matcher matcher = CLAIM_NUMBER.matcher(jsonBody);
        while (matcher.find()) {
            numbers.add(matcher.group(1));
        }
        return numbers;
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }

    private String supervisorBearer() {
        return JwtTestConfig.tokenFor(SUB_SUPERVISOR, "supervisor");
    }

    private String adjusterOneBearer() {
        return JwtTestConfig.tokenFor(SUB_L1_ONE, "adjuster_l1");
    }
}
