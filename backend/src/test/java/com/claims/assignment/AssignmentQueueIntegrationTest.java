package com.claims.assignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.claims.TestcontainersConfiguration;
import com.claims.staff.AppUserRepository;
import com.claims.support.JwtTestConfig;

/**
 * Slice-2 acceptance, run against a database this class alone controls: every test starts
 * by truncating the claim tables (and clearing the mailpit mailbox), so assignment
 * outcomes are deterministic from the V4 seeds — two L1 adjusters (ids 1,2) and one L2
 * adjuster (id 3), all with zero open claims. Real HTTP, real Postgres, real SMTP capture.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssignmentQueueIntegrationTest {

    private static final String BOUNDARY = "----AssignmentTestBoundary99";
    private static final Pattern CLAIM_NUMBER = Pattern.compile("\"claimNumber\":\"(CLM-\\d{6})\"");

    private static final String SUB_L1_ONE = "10000000-0000-0000-0000-000000000001"; // adjuster.one
    private static final String SUB_L1_TWO = "10000000-0000-0000-0000-000000000002"; // adjuster.two
    private static final String SUB_L2 = "10000000-0000-0000-0000-000000000003"; // adjuster.three

    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
            DockerImageName.parse("axllent/mailpit:v1.22"))
            .withExposedPorts(1025, 8025);

    private static final Path UPLOADS = createUploadsDir();

    static {
        MAILPIT.start();
    }

    private static Path createUploadsDir() {
        try {
            return Files.createTempDirectory("claims-assignment-test-uploads");
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
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AppUserRepository appUsers;

    private final HttpClient http = HttpClient.newHttpClient();

    private Long l1One;
    private Long l1Two;
    private Long l2;

    @BeforeEach
    void resetState() throws Exception {
        jdbcTemplate.execute("TRUNCATE claim, attachment, audit_log RESTART IDENTITY CASCADE");
        http.send(HttpRequest.newBuilder(URI.create(mailpitUrl() + "/api/v1/messages")).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        l1One = appUsers.findByKeycloakSub(SUB_L1_ONE).orElseThrow().getId();
        l1Two = appUsers.findByKeycloakSub(SUB_L1_TWO).orElseThrow().getId();
        l2 = appUsers.findByKeycloakSub(SUB_L2).orElseThrow().getId();
    }

    // --- assignment ------------------------------------------------------------

    @Test
    void l1FnosAreLoadBalancedWithLowestIdAsTieBreak() throws Exception {
        List<Long> assignees = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String claimNumber = fileHomeFnol("sub-claimant-" + i);
            assignees.add(assigneeOf(claimNumber));
        }
        // Equal loads -> lowest id; then the other is least loaded; then equal again.
        assertEquals(List.of(l1One, l1Two, l1One), assignees);
    }

    @Test
    void l2FnolIsAssignedToTheL2Adjuster() throws Exception {
        String claimNumber = fileFnol(Map.of(
                "policyNumber", "POL-20002",
                "holderName", "Grace Hopper",
                "holderEmail", "grace.hopper@example.test"), "sub-claimant-grace");

        assertEquals(l2, assigneeOf(claimNumber));
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
    }

    @Test
    void assignmentIsRecordedAndEmailedToTheClaimant() throws Exception {
        String claimNumber = fileHomeFnol("sub-claimant-ada");

        // Status flips to UNDER_REVIEW at FNOL; assignee + timestamp written.
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        assertNotNull(assignedAtOf(claimNumber));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_ASSIGNED' "
                + "AND entity_id = ?", idOf(claimNumber)));

        // Assignment email to the verified policyholder, naming the adjuster (who to contact).
        String inbox = get(mailpitUrl() + "/api/v1/messages");
        assertTrue(inbox.contains(claimNumber), inbox);
        assertTrue(inbox.contains("is now with an adjuster"), inbox);
        assertTrue(inbox.contains("Priya Sharma"), "assignment email should name the adjuster: " + inbox);
    }

    @Test
    void simultaneousFnosToTheSameLevelCannotDoubleAssign() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<HttpResponse<String>>> tasks = List.of(
                    () -> post("/api/claims", claimantBearer("sub-racer-a"), fnolBody("POL-10001",
                            "Ada Lovelace", "ada.lovelace@example.test")),
                    () -> post("/api/claims", claimantBearer("sub-racer-b"), fnolBody("POL-10001",
                            "Ada Lovelace", "ada.lovelace@example.test")));
            List<HttpResponse<String>> responses = pool.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    })
                    .toList();

            for (HttpResponse<String> response : responses) {
                assertEquals(201, response.statusCode(), response.body());
            }
            Long assigneeA = assigneeOf(extractClaimNumber(responses.get(0)));
            Long assigneeB = assigneeOf(extractClaimNumber(responses.get(1)));
            assertNotNull(assigneeA);
            assertNotNull(assigneeB);
            assertNotEquals(assigneeA, assigneeB,
                    "concurrent FNOLs must serialize on assignment and land on different adjusters");
        } finally {
            pool.shutdownNow();
        }
    }

    // --- queue -----------------------------------------------------------------

    @Test
    void adjusterSeesOnlyTheirOwnAssignedClaims() throws Exception {
        String claimNumber = fileHomeFnol("sub-claimant-q");

        String ownQueue = getQueue(JwtTestConfig.tokenFor(SUB_L1_ONE, "adjuster_l1"));
        assertTrue(ownQueue.contains(claimNumber), "the assignee's queue shows the claim: " + ownQueue);

        String otherQueue = getQueue(JwtTestConfig.tokenFor(SUB_L1_TWO, "adjuster_l1"));
        assertTrue(!otherQueue.contains(claimNumber),
                "a different L1 adjuster's queue must not show someone else's claim: " + otherQueue);

        String l2Queue = getQueue(JwtTestConfig.tokenFor(SUB_L2, "adjuster_l2"));
        assertTrue(!l2Queue.contains(claimNumber),
                "an L2 adjuster never sees an L1 claim: " + l2Queue);
    }

    @Test
    void supervisorSeesTheWholeTeamQueueWithAssigneeNames() throws Exception {
        String first = fileHomeFnol("sub-claimant-s1");
        String second = fileHomeFnol("sub-claimant-s2");

        String team = getQueue(JwtTestConfig.tokenFor("sub-supervisor-1", "supervisor"));
        assertTrue(team.contains(first) && team.contains(second), "supervisor sees all open claims: " + team);
        assertTrue(team.contains("Priya Sharma") && team.contains("Marcus Webb"),
                "team rows carry the assignee display name: " + team);

        // Oldest work first: the first-filed claim precedes the second in the queue.
        List<String> ordered = claimNumbersIn(team);
        assertTrue(ordered.indexOf(first) < ordered.indexOf(second),
                "queue is ordered oldest-first: " + ordered);
    }

    @Test
    void queueAuthRules() throws Exception {
        fileHomeFnol("sub-claimant-auth");

        // Anonymous: 401. Claimant: 403 (authenticated but not internal).
        assertEquals(401, getQueueStatus(null));
        assertEquals(403, getQueueStatus(JwtTestConfig.tokenFor("sub-claimant-1", "claimant")));
    }

    @Test
    void queueIsEmptyForAnAdjusterWithNoAssignments() throws Exception {
        // No claims filed; an adjuster of an unused level must get an empty queue, not an error.
        String body = getQueue(JwtTestConfig.tokenFor(SUB_L2, "adjuster_l2"));
        assertTrue(body.trim().equals("[]"), body);
    }

    // --- helpers ---------------------------------------------------------------

    private String fileHomeFnol(String claimantSub) throws Exception {
        return fileFnol(Map.of(
                "policyNumber", "POL-10001",
                "holderName", "Ada Lovelace",
                "holderEmail", "ada.lovelace@example.test"), claimantSub);
    }

    private String fileFnol(Map<String, String> fields, String claimantSub) throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(claimantSub),
                fnolBody(fields.get("policyNumber"), fields.get("holderName"), fields.get("holderEmail")));
        assertEquals(201, response.statusCode(), response.body());
        return extractClaimNumber(response);
    }

    private String extractClaimNumber(HttpResponse<String> response) {
        Matcher matcher = CLAIM_NUMBER.matcher(response.body());
        assertTrue(matcher.find(), "claim number missing from " + response.body());
        return matcher.group(1);
    }

    private String getQueue(String bearer) throws Exception {
        HttpResponse<String> response = get("/api/queue", bearer);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private int getQueueStatus(String bearer) throws Exception {
        return get("/api/queue", bearer).statusCode();
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

    private static byte[] fnolBody(String policyNumber, String holderName, String holderEmail) {
        return multipart(Map.of(
                "policyNumber", policyNumber,
                "holderName", holderName,
                "holderEmail", holderEmail,
                "lossDate", "2026-09-01",
                "lossLocation", "London",
                "lossDescription", "Storm damage to the property."));
    }

    private static byte[] multipart(Map<String, String> fields) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Map.Entry<String, String> field : fields.entrySet()) {
                write(out, "--" + BOUNDARY + "\r\n");
                write(out, "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n");
                write(out, field.getValue() + "\r\n");
            }
            write(out, "--" + BOUNDARY + "--\r\n");
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void write(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private Long assigneeOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT assigned_adjuster_id FROM claim WHERE claim_number = ?", Long.class, claimNumber);
    }

    private String statusOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM claim WHERE claim_number = ?", String.class, claimNumber);
    }

    private Object assignedAtOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT assigned_at FROM claim WHERE claim_number = ?", Object.class, claimNumber);
    }

    private Long idOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM claim WHERE claim_number = ?", Long.class, claimNumber);
    }

    private List<String> claimNumbersIn(String jsonBody) {
        List<String> numbers = new ArrayList<>();
        Matcher matcher = CLAIM_NUMBER.matcher(jsonBody);
        while (matcher.find()) {
            numbers.add(matcher.group(1));
        }
        return numbers;
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }

    private String mailpitUrl() {
        return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
    }

    private String get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private String claimantBearer(String sub) {
        return JwtTestConfig.tokenFor(sub, "claimant");
    }
}
