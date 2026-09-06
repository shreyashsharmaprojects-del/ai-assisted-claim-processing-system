package com.claims.assignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.claims.TestcontainersConfiguration;
import com.claims.claim.ClaimService;
import com.claims.claim.FnolInput;
import com.claims.claim.FnolResult;
import com.claims.staff.AppUser;
import com.claims.staff.AppUserRepository;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Slice-2 acceptance, run against a database this class alone controls (see
 * {@link ClaimTableResettingTest}: claim tables are truncated between tests, so assignment
 * outcomes are deterministic from the V4 seeds — two L1 adjusters and one L2 adjuster, all
 * with zero open claims). Real HTTP, real Postgres, real SMTP capture.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssignmentQueueIntegrationTest extends ClaimTableResettingTest {

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
    private AppUserRepository appUsers;
    @Autowired
    private ClaimService claimService;
    @Autowired
    private DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();

    private Long l1One;
    private Long l1Two;
    private Long l2;

    @BeforeEach
    void resetMailboxAndLoadAdjusters() throws Exception {
        // Claim tables were already truncated by the shared base's @BeforeEach.
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
    void assignmentWaitsForAnotherTransactionHoldingTheLevelLock() throws Exception {
        // A deterministic race test: hold the L1 candidates' rows locked on a raw
        // connection, then file an L1 FNOL on another thread. The claim's assignment must
        // block behind that lock — if ClaimAssigner's SELECT ... FOR UPDATE were removed,
        // the FNOL would complete while the lock is still held and this test fails. (An
        // HTTP-level two-thread test cannot force the two transactions to overlap, so it
        // would pass even with the lock gone.)
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("SELECT id FROM app_user WHERE level = 'L1' ORDER BY id FOR UPDATE");
            }

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<FnolResult> pending = pool.submit(
                        () -> claimService.fileFnol(fnolInput("sub-claimant-lock")));

                // The lock is still held, so the assignment step cannot have finished.
                Thread.sleep(500);
                assertFalse(pending.isDone(),
                        "assignment must block while another transaction holds the level lock");

                holder.commit(); // release the lock; the FNOL's assignment proceeds
                FnolResult result = pending.get(10, TimeUnit.SECONDS);
                assertEquals("UNDER_REVIEW", result.view().status());
                assertNotNull(assigneeOf(result.view().claimNumber()));
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void claimForALevelWithNoProvisionedAdjusterStaysUnassignedWithoutEmailOrAssignmentAudit()
            throws Exception {
        // Simulate a provisioning gap: no L2 adjuster exists, so an L2 claim cannot be
        // assigned. The claim must still be created (FNOL number returned) and stay
        // UNASSIGNED — with no CLAIM_ASSIGNED row and no assignment email.
        AppUser l2Adjuster = appUsers.findByKeycloakSub(SUB_L2).orElseThrow();
        appUsers.delete(l2Adjuster);
        try {
            HttpResponse<String> response = post("/api/claims", claimantBearer("sub-claimant-gap"),
                    fnolBody("POL-20002", "Grace Hopper", "grace.hopper@example.test"));
            assertEquals(201, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"status\":\"UNASSIGNED\""), response.body());

            String claimNumber = extractClaimNumber(response);
            Long claimId = idOf(claimNumber);
            assertEquals("UNASSIGNED", statusOf(claimNumber));
            assertNull(assigneeOf(claimNumber));
            assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_CREATED' "
                    + "AND entity_id = ?", claimId));
            assertEquals(0, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_ASSIGNED' "
                    + "AND entity_id = ?", claimId));

            String inbox = get(mailpitUrl() + "/api/v1/messages");
            assertTrue(inbox.contains(claimNumber), inbox);
            assertTrue(!inbox.contains("is now with an adjuster"),
                    "no assignment email when there is no assignee: " + inbox);
        } finally {
            // Restore the seeded L2 adjuster so later tests see the full staff (fresh id,
            // same identity — the V4 seed state this class's contract relies on).
            appUsers.save(new AppUser(SUB_L2, "Ines Kowalski", "ines.kowalski@claims.test", "L2"));
        }
    }

    @Test
    void seededAdjusterCacheMatchesTheProvisionedRealmStaff() throws Exception {
        // S2/S4 seam check: app_user.keycloak_sub/level/identity are hand-synced with
        // keycloak/realm-export.template.json (V4 seeds mirror the imported realm users). This
        // test pins that sync so a drift between the two files — which would silently
        // mis-route claims or empty a queue — fails here instead of in production.
        JsonNode realm = new ObjectMapper().readTree(realmExportFile().toFile());
        List<JsonNode> staff = new ArrayList<>();
        for (JsonNode user : realm.path("users")) {
            List<String> roles = new ArrayList<>();
            for (JsonNode role : user.path("realmRoles")) {
                roles.add(role.asText());
            }
            if (roles.contains("adjuster_l1") || roles.contains("adjuster_l2")) {
                staff.add(user);
            }
        }
        assertEquals(3, staff.size(), "the realm export must provision exactly the V4 staff");

        for (JsonNode user : staff) {
            AppUser cache = appUsers.findByKeycloakSub(user.get("id").asText()).orElseThrow(
                    () -> new AssertionError("no app_user row for realm user "
                            + user.get("username").asText()));
            boolean l1 = false;
            for (JsonNode role : user.path("realmRoles")) {
                l1 = l1 || "adjuster_l1".equals(role.asText());
            }
            assertEquals(l1 ? "L1" : "L2", cache.getLevel(),
                    "app_user.level must mirror the provisioned Keycloak role");
            assertEquals(user.get("email").asText(), cache.getEmail());
            assertEquals(user.get("firstName").asText() + " " + user.get("lastName").asText(),
                    cache.getDisplayName());
        }
        assertEquals(3, appUsers.count(),
                "app_user must hold exactly the provisioned staff, nothing more");
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

    /** Service-level FNOL for the lock test (no HTTP, no controller, so no emails). */
    private static FnolInput fnolInput(String claimantSub) {
        return new FnolInput("POL-10001", "Ada Lovelace", "ada.lovelace@example.test",
                "2026-09-01", "London", "Storm damage to the property.", null,
                claimantSub, List.<MultipartFile>of());
    }

    private static Path realmExportFile() {
        // The realm lives as a committed template (passwords are env-rendered into the
        // gitignored realm-export.json at dev/E2E boot); this test reads identity fields only.
        // Surefire runs with the module directory as cwd; also accept the repo root.
        Path fromModule = Path.of("../keycloak/realm-export.template.json");
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        Path fromRepo = Path.of("keycloak/realm-export.template.json");
        assertTrue(Files.exists(fromRepo), "keycloak/realm-export.template.json not found next to the backend module");
        return fromRepo;
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
