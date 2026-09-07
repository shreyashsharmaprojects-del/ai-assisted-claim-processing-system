package com.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

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
 * V2-2 multi-cover FNOL: explicit cover selections persist as {@code claim_cover}
 * rows with a server-computed {@code claimed_total}; the duplicate guard returns the
 * existing number (409); unknown covers are 400 naming the valid codes; RETIRED and
 * EXPIRED policies reuse the 404 mismatch shape; above-limit filings are accepted
 * (flagged, never blocked); the legacy no-covers path persists nothing.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiCoverFnolIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----ClaimsTestBoundary43";

    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
            DockerImageName.parse("axllent/mailpit:v1.22"))
            .withExposedPorts(1025, 8025);

    static {
        MAILPIT.start();
    }

    @org.springframework.test.context.DynamicPropertySource
    static void mailProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    }

    @Autowired
    private Environment environment;
    @Autowired
    private ClaimRepository claims;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void multiCoverFilingPersistsRowsTotalAndAboveLimitFlag() throws Exception {
        Map<String, String> fields = baseFields("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        // OPD claimed 40k vs the 30k sub-limit: accepted, flagged.
        fields.put("covers",
                "[{\"coverCode\":\"HOSPITALIZATION\",\"claimedAmount\":200000},"
                        + "{\"coverCode\":\"OPD\",\"claimedAmount\":40000}]");
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(fields));

        assertEquals(201, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"coverCode\":\"HOSPITALIZATION\""),
                response.body());
        assertTrue(response.body().contains("\"aboveLimit\":true"), response.body());
        assertTrue(response.body().contains("\"claimedTotal\":240000"), response.body());

        String claimNumber = numberOf(response);
        Claim claim = findClaim(claimNumber);
        assertEquals(0, new BigDecimal("240000").compareTo(claim.getClaimedTotal()));
        assertEquals(2, count("SELECT count(*) FROM claim_cover WHERE claim_id = ?",
                claim.getId()));
        assertEquals(1, count("SELECT count(*) FROM claim_cover WHERE claim_id = ? "
                + "AND cover_code = 'OPD' AND claimed_amount = 40000", claim.getId()));
        // No internal money on the wire.
        for (String internal : new String[] {"assessedAmount", "approvedAmount",
                "netPayable", "reserveAmount", "assignedTo"}) {
            assertFalse(response.body().contains("\"" + internal + "\""),
                    "claimant response must not contain " + internal);
        }
    }

    @Test
    void duplicateCoverSetReturns409WithExistingNumber() throws Exception {
        Map<String, String> fields = baseFields("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        fields.put("covers", "[{\"coverCode\":\"OPD\",\"claimedAmount\":5000}]");
        HttpResponse<String> first = post("/api/claims", claimantBearer(),
                multipart(fields));
        assertEquals(201, first.statusCode(), first.body());
        String existing = numberOf(first);

        // Same set, different order + different amount, same loss date: still a duplicate.
        Map<String, String> retry = baseFields("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        retry.put("covers", "[{\"coverCode\":\"OPD\",\"claimedAmount\":9000}]");
        HttpResponse<String> second = post("/api/claims", claimantBearer(),
                multipart(retry));

        assertEquals(409, second.statusCode(), second.body());
        assertTrue(second.body().contains("\"claimNumber\":\"" + existing + "\""),
                second.body());
        // No second claim row was created.
        assertEquals(1, count("SELECT count(*) FROM claim"));
    }

    @Test
    void differentCoverSetIsNotADuplicate() throws Exception {
        Map<String, String> fields = baseFields("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        fields.put("covers", "[{\"coverCode\":\"OPD\",\"claimedAmount\":5000}]");
        assertEquals(201,
                post("/api/claims", claimantBearer(), multipart(fields)).statusCode());

        Map<String, String> other = baseFields("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        other.put("covers", "[{\"coverCode\":\"DAYCARE\",\"claimedAmount\":5000}]");
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(other));
        assertEquals(201, response.statusCode(), response.body());
    }

    @Test
    void unknownCoverIs400NamingValidCodes() throws Exception {
        Map<String, String> fields = baseFields("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        fields.put("covers", "[{\"coverCode\":\"PHARMACY\",\"claimedAmount\":15000}]");
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(fields));

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("HOSPITALIZATION"), response.body());
    }

    @Test
    void retiredAndExpiredPoliciesReuseThe404MismatchShape() throws Exception {
        for (String[] policy : new String[][] {
                {"POL-30007", "Retired Holder", "retired.holder@example.test"},
                {"POL-30008", "Expired Holder", "expired.holder@example.test"}}) {
            Map<String, String> fields = baseFields(policy[0], policy[1], policy[2]);
            HttpResponse<String> response = post("/api/claims", claimantBearer(),
                    multipart(fields));
            assertEquals(404, response.statusCode(), response.body());
            assertTrue(response.body().contains("could not match"), response.body());
        }
        assertEquals(0, count("SELECT count(*) FROM claim"));
    }

    @Test
    void legacyNoCoversPathPersistsNothingAndLeavesTotalNull() throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(),
                multipart(baseFields("POL-10001", "Ada Lovelace",
                        "ada.lovelace@example.test")));

        assertEquals(201, response.statusCode(), response.body());
        assertFalse(response.body().contains("\"covers\""), response.body());
        assertFalse(response.body().contains("\"claimedTotal\""), response.body());
        Claim claim = findClaim(numberOf(response));
        assertNull(claim.getClaimedTotal());
        assertEquals(0,
                count("SELECT count(*) FROM claim_cover WHERE claim_id = ?",
                        claim.getId()));
    }

    @Test
    void filingCoversListsOptedCoversAnd404sOnMismatch() throws Exception {
        HttpResponse<String> ok = getResponse(
                "/api/claims/filing-covers?policyNumber=POL-10001&holderName=Ada%20Lovelace"
                        + "&holderEmail=ada.lovelace%40example.test",
                claimantBearer());
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"coverCode\":\"HOSPITALIZATION\""), ok.body());
        assertTrue(ok.body().contains("\"subLimit\""), ok.body());
        assertFalse(ok.body().contains("reserveAmount"), ok.body());

        HttpResponse<String> mismatch = getResponse(
                "/api/claims/filing-covers?policyNumber=POL-10001&holderName=Someone%20Else"
                        + "&holderEmail=ada.lovelace%40example.test",
                claimantBearer());
        assertEquals(404, mismatch.statusCode(), mismatch.body());

        HttpResponse<String> retired = getResponse(
                "/api/claims/filing-covers?policyNumber=POL-30007&holderName=Retired%20Holder"
                        + "&holderEmail=retired.holder%40example.test",
                claimantBearer());
        assertEquals(404, retired.statusCode(), retired.body());
    }

    @Test
    void trackerShowsFiledCoversWithNoInternalFields() throws Exception {
        Map<String, String> fields = baseFields("POL-10001", "Ada Lovelace",
                "ada.lovelace@example.test");
        fields.put("covers",
                "[{\"coverCode\":\"HOSPITALIZATION\",\"claimedAmount\":200000}]");
        HttpResponse<String> filed = post("/api/claims", claimantBearer(),
                multipart(fields));
        assertEquals(201, filed.statusCode(), filed.body());
        String claimNumber = numberOf(filed);

        HttpResponse<String> status = getResponse("/api/claims/" + claimNumber,
                claimantBearer());
        assertEquals(200, status.statusCode(), status.body());
        assertTrue(status.body().contains("\"coverCode\":\"HOSPITALIZATION\""),
                status.body());
        assertTrue(status.body().contains("\"claimedTotal\":200000"), status.body());
        for (String internal : new String[] {"assessedAmount", "approvedAmount",
                "netPayable", "reserveAmount", "assignedTo", "policyNumber"}) {
            assertFalse(status.body().contains("\"" + internal + "\""),
                    "tracker must not contain " + internal);
        }
    }

    // --- helpers ---------------------------------------------------------------

    private Map<String, String> baseFields(String policyNumber, String holderName,
            String holderEmail) {
        Map<String, String> fields = new HashMap<>();
        fields.put("policyNumber", policyNumber);
        fields.put("holderName", holderName);
        fields.put("holderEmail", holderEmail);
        fields.put("lossDate", "2026-09-01");
        fields.put("lossLocation", "London");
        fields.put("lossDescription", "Hospital stay plus follow-up visits.");
        return fields;
    }

    private String numberOf(HttpResponse<String> response) {
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    private Claim findClaim(String claimNumber) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM claim WHERE claim_number = ?", Long.class, claimNumber);
        return claims.findById(id).orElseThrow();
    }

    private String claimantBearer() {
        return JwtTestConfig.tokenFor("sub-claimant-1", "claimant");
    }

    private HttpResponse<String> post(String path, String bearer, byte[] body)
            throws Exception {
        int port = Integer.parseInt(environment.getProperty("local.server.port"));
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getResponse(String path, String bearer) throws Exception {
        int port = Integer.parseInt(environment.getProperty("local.server.port"));
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path)).GET();
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

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
