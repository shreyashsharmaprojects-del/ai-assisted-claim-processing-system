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
 * One integration test for the walking skeleton: a real HTTP request through the real
 * Spring context against a real PostgreSQL (Testcontainers when Docker is available,
 * dedicated claims_test database otherwise — see TestcontainersConfiguration).
 * Schema comes from Flyway, which also applies the V2 seed row.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PolicyApiIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    @Test
    void seededPolicyIsServedThroughTheRealStack() throws Exception {
        int port = Integer.parseInt(environment.getProperty("local.server.port"));
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/policies")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("POL-10001"), "response should contain the seeded policy number: " + body);
        assertTrue(body.contains("Ada Lovelace"), "response should contain the seeded holder name: " + body);
        assertFalse(body.contains("ada.lovelace@example.test"),
                "holder email must never reach the public policy view: " + body);
        assertFalse(body.contains("sum_insured"), "coverage must never reach the public policy view: " + body);
    }
}
