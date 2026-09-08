package com.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * Policy + health integration tests: real HTTP through the real Spring context against a
 * real PostgreSQL (Testcontainers when Docker is available, dedicated claims_test
 * database otherwise — see TestcontainersConfiguration). Schema comes from Flyway, which
 * also applies the seed rows.
 */
@Import({TestcontainersConfiguration.class, com.claims.support.JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PolicyApiIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    @Test
    void legacyBookListIsSupervisorOnly() throws Exception {
        int port = Integer.parseInt(environment.getProperty("local.server.port"));
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/policies"))
                        .header("Authorization", "Bearer "
                                + com.claims.support.JwtTestConfig.tokenFor("sub-policy-1", "supervisor"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("POL-10001"), "response should contain the seeded policy number: " + body);
        assertTrue(body.contains("Ada Lovelace"), "response should contain the seeded holder name: " + body);
        assertFalse(body.contains("ada.lovelace@example.test"),
                "holder email must never reach the legacy policy view: " + body);
        assertFalse(body.contains("sum_insured"), "coverage must never reach the legacy policy view: " + body);
    }

    @Test
    void legacyBookListRejectsClaimantsAdjustersAndAnonymous() throws Exception {
        int port = Integer.parseInt(environment.getProperty("local.server.port"));
        HttpResponse<Void> anonymous = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/policies")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, anonymous.statusCode(), "anonymous callers must not list policyholder names");

        for (String role : new String[] {"claimant", "adjuster_l1", "adjuster_l2", "adjuster_l3"}) {
            HttpResponse<Void> denied = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/policies"))
                            .header("Authorization", "Bearer "
                                    + com.claims.support.JwtTestConfig.tokenFor("sub-" + role, role))
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(403, denied.statusCode(), "role " + role + " must not list other customers' policies");
        }
    }

    @Test
    void healthEndpointIsPublicAndReportsUp() throws Exception {
        int port = Integer.parseInt(environment.getProperty("local.server.port"));
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // Public liveness probe: no auth required, reports UP.
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("UP"), "health response should report UP: " + response.body());
    }

    @Test
    void readinessEndpointReportsDatabaseReachability() throws Exception {
        int port = Integer.parseInt(environment.getProperty("local.server.port"));
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/ready")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("READY"), "ready response should report READY: " + response.body());
    }
}
