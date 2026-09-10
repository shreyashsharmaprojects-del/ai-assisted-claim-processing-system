package com.claims.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import com.claims.TestcontainersConfiguration;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

/**
 * V3 S7: the supervisor's staff surface over real HTTP against real Postgres.
 * List carries per-adjuster open-claim loads; deactivation drains the adjuster's
 * open queue through ClaimAssigner (same-level least-loaded, skill-aware) with a
 * CLAIM_REASSIGNED audit row per move; claims with no eligible active target
 * park UNASSIGNED (the queue ?status=UNASSIGNED attention list); reactivation
 * flips without moving. Auth matrix: adjuster/claimant 403, anon 401, unknown
 * staff id 404.
 *
 * <p>Determinism: claim tables reset per test, so sequential HOME FNOLs land on
 * Priya, then Marcus (least-loaded L1, lowest id on ties). app_user is a seed
 * table the base class never truncates, so every test starts by restoring
 * active=TRUE for all rows (a previous test's deactivation must not leak).
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaffAdminIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----StaffAdminBoundary7";

    private static final String SUB_L1_ONE = "10000000-0000-0000-0000-000000000001";
    private static final String SUB_L1_TWO = "10000000-0000-0000-0000-000000000002";
    private static final String SUB_SUPERVISOR = "10000000-0000-0000-0000-000000000004";

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reactivateAllStaff() {
        // app_user survives the shared truncation (seed table) — restore the seed
        // state so deactivations never leak between tests.
        jdbcTemplate.update("UPDATE app_user SET active = TRUE");
    }

    // --- list ------------------------------------------------------------------

    @Test
    void supervisorListsStaffWithOpenClaimLoads() throws Exception {
        String first = fileHomeFnol("sub-staff-list-1");
        String second = fileHomeFnol("sub-staff-list-2");
        assertEquals("Priya Sharma", assigneeOf(first));
        assertEquals("Marcus Webb", assigneeOf(second));

        HttpResponse<String> response = get("/api/staff", supervisorBearer());
        assertEquals(200, response.statusCode(), response.body());

        // Six seeded adjusters, each row carrying identity + load + the sub to copy.
        for (String name : new String[]{"Priya Sharma", "Marcus Webb", "Ines Kowalski",
                "Aisha Verma", "Rahul Singh", "Meera Nair"}) {
            assertTrue(response.body().contains("\"displayName\":\"" + name + "\""),
                    "staff list holds " + name + ": " + response.body());
        }
        assertTrue(rowOf(response.body(), "Priya Sharma").contains("\"openClaims\":1"),
                response.body());
        assertTrue(rowOf(response.body(), "Marcus Webb").contains("\"openClaims\":1"),
                response.body());
        assertTrue(rowOf(response.body(), "Aisha Verma").contains("\"openClaims\":0"),
                response.body());
        assertTrue(rowOf(response.body(), "Priya Sharma").contains("\"active\":true"),
                response.body());
        assertTrue(rowOf(response.body(), "Priya Sharma")
                .contains("\"keycloakSub\":\"" + SUB_L1_ONE + "\""), response.body());
        assertTrue(rowOf(response.body(), "Priya Sharma").contains("\"level\":\"L1\""),
                response.body());
        assertTrue(rowOf(response.body(), "Priya Sharma")
                .contains("\"email\":\"priya.sharma@claims.test\""), response.body());
    }

    // --- deactivate: move + audit + park ----------------------------------------

    @Test
    void deactivateMovesOpenClaimsWritesAuditsAndParksTheUnroutable() throws Exception {
        String first = fileHomeFnol("sub-staff-move-1");
        String second = fileHomeFnol("sub-staff-move-2");
        long outboxBefore = count("SELECT count(*) FROM email_outbox");

        // Deactivate Marcus: his claim moves to the least-loaded active L1 (Aisha
        // holds 0, Priya holds 1) with a CLAIM_REASSIGNED audit row.
        HttpResponse<String> off = putJson("/api/staff/" + staffId(SUB_L1_TWO)
                + "/active", supervisorBearer(), "{\"active\":false}");
        assertEquals(200, off.statusCode(), off.body());
        assertTrue(off.body().contains("\"active\":false"), off.body());
        assertEquals("Aisha Verma", assigneeOf(second));
        assertEquals("UNDER_REVIEW", statusOf(second));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_REASSIGNED' "
                + "AND entity_id = ?", idOf(second)));
        assertTrue(jdbcTemplate.queryForObject("SELECT after::text FROM audit_log "
                + "WHERE action = 'CLAIM_REASSIGNED' AND entity_id = ?", String.class,
                idOf(second)).contains("Aisha Verma"));
        assertEquals(SUB_SUPERVISOR, jdbcTemplate.queryForObject("SELECT actor_sub FROM audit_log "
                + "WHERE action = 'CLAIM_REASSIGNED' AND entity_id = ?", String.class,
                idOf(second)));

        // Deactivate Priya: her claim moves to Aisha too (the only other active L1).
        putJson("/api/staff/" + staffId(SUB_L1_ONE) + "/active",
                supervisorBearer(), "{\"active\":false}");
        assertEquals("Aisha Verma", assigneeOf(first));

        // Deactivate Aisha: no active L1 remains — both claims park UNASSIGNED with
        // no assignee, each with its own CLAIM_REASSIGNED audit row.
        putJson("/api/staff/" + staffIdForDisplayName("Aisha Verma") + "/active",
                supervisorBearer(), "{\"active\":false}");
        for (String claimNumber : new String[]{first, second}) {
            assertEquals("UNASSIGNED", statusOf(claimNumber));
            assertNull(jdbcTemplate.queryForObject(
                    "SELECT assigned_adjuster_id FROM claim WHERE claim_number = ?",
                    Long.class, claimNumber));
            assertEquals(2, count("SELECT count(*) FROM audit_log "
                    + "WHERE action = 'CLAIM_REASSIGNED' AND entity_id = ?",
                    idOf(claimNumber)),
                    "one audit row per move for " + claimNumber);
        }
        assertEquals(Boolean.FALSE, jdbcTemplate.queryForObject(
                "SELECT active FROM app_user WHERE display_name = 'Aisha Verma'",
                Boolean.class));

        // The parked claims are the supervisor attention list via ?status=UNASSIGNED.
        HttpResponse<String> queue = get("/api/queue?status=UNASSIGNED", supervisorBearer());
        assertEquals(200, queue.statusCode(), queue.body());
        assertTrue(queue.body().contains(first) && queue.body().contains(second),
                queue.body());
        assertTrue(queue.body().contains("\"totalElements\":2"), queue.body());

        // No outbox write for staff flips — the slice sends no mail here.
        assertEquals(outboxBefore, count("SELECT count(*) FROM email_outbox"),
                "deactivation must not enqueue mail");
    }

    // --- reactivate: flip without moving -----------------------------------------

    @Test
    void reactivateFlipsWithoutMoving() throws Exception {
        String claimNumber = fileHomeFnol("sub-staff-react-1");
        Long marcusId = staffId(SUB_L1_TWO);

        putJson("/api/staff/" + staffId(SUB_L1_ONE) + "/active",
                supervisorBearer(), "{\"active\":false}");
        assertEquals("Marcus Webb", assigneeOf(claimNumber));
        long reassigns = count("SELECT count(*) FROM audit_log WHERE action = 'CLAIM_REASSIGNED' "
                + "AND entity_id = ?", idOf(claimNumber));

        HttpResponse<String> on = putJson("/api/staff/" + staffId(SUB_L1_ONE)
                + "/active", supervisorBearer(), "{\"active\":true}");
        assertEquals(200, on.statusCode(), on.body());
        assertTrue(on.body().contains("\"active\":true"), on.body());
        assertTrue(on.body().contains("\"openClaims\":0"), on.body());
        assertEquals("Marcus Webb", assigneeOf(claimNumber),
                "reactivation must not pull the claim back");
        assertEquals("UNDER_REVIEW", statusOf(claimNumber));
        assertEquals(reassigns, count("SELECT count(*) FROM audit_log "
                + "WHERE action = 'CLAIM_REASSIGNED' AND entity_id = ?",
                idOf(claimNumber)),
                "reactivation writes no CLAIM_REASSIGNED rows");
        assertEquals(Long.valueOf(0), jdbcTemplate.queryForObject(
                "SELECT count(*) FROM claim WHERE assigned_adjuster_id = ? "
                        + "AND status <> 'CLOSED'",
                Long.class, staffId(SUB_L1_ONE)));
        assertEquals(1, count("SELECT count(*) FROM claim WHERE assigned_adjuster_id = ? "
                + "AND status <> 'CLOSED'", marcusId));
    }

    // --- auth matrix + 404 ---------------------------------------------------------

    @Test
    void staffSurfaceIsSupervisorOnlyAndUnknownIdsAre404() throws Exception {
        Long priyaId = staffId(SUB_L1_ONE);

        assertEquals(401, get("/api/staff", null).statusCode());
        assertEquals(403, get("/api/staff", adjusterBearer()).statusCode());
        assertEquals(403, get("/api/staff", claimantBearer()).statusCode());
        assertEquals(200, get("/api/staff", supervisorBearer()).statusCode());

        String body = "{\"active\":false}";
        assertEquals(401, putJson("/api/staff/" + priyaId + "/active", null, body)
                .statusCode());
        assertEquals(403, putJson("/api/staff/" + priyaId + "/active", adjusterBearer(),
                body).statusCode());
        assertEquals(403, putJson("/api/staff/" + priyaId + "/active", claimantBearer(),
                body).statusCode());

        assertEquals(404, putJson("/api/staff/999999/active", supervisorBearer(), body)
                .statusCode());
        HttpResponse<String> empty = putJson("/api/staff/" + priyaId + "/active",
                supervisorBearer(), "{}");
        assertEquals(400, empty.statusCode(), empty.body());
        assertEquals(Boolean.TRUE, jdbcTemplate.queryForObject(
                "SELECT active FROM app_user WHERE id = ?", Boolean.class, priyaId),
                "a rejected flip must not touch the flag");
    }

    // --- helpers -------------------------------------------------------------------

    private String fileHomeFnol(String claimantSub) throws Exception {
        HttpResponse<String> response = post("/api/claims",
                JwtTestConfig.tokenFor(claimantSub, "claimant"),
                multipart(java.util.Map.of(
                        "policyNumber", "POL-10001",
                        "holderName", "Ada Lovelace",
                        "holderEmail", "ada.lovelace@example.test",
                        "lossDate", "2026-09-01",
                        "lossLocation", "London",
                        "lossDescription", "Kitchen flooded after a pipe burst.")));
        assertEquals(201, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    private Long staffId(String keycloakSub) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE keycloak_sub = ?", Long.class, keycloakSub);
    }

    private Long staffIdForDisplayName(String displayName) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE display_name = ?", Long.class, displayName);
    }

    private String assigneeOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT a.display_name FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
    }

    private String statusOf(String claimNumber) {
        return jdbcTemplate.queryForObject("SELECT status FROM claim WHERE claim_number = ?",
                String.class, claimNumber);
    }

    private Long idOf(String claimNumber) {
        return jdbcTemplate.queryForObject("SELECT id FROM claim WHERE claim_number = ?",
                Long.class, claimNumber);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static String rowOf(String body, String displayName) {
        Matcher matcher = Pattern.compile("\\{[^}]*\"displayName\":\""
                + Pattern.quote(displayName) + "\"[^}]*\\}").matcher(body);
        assertTrue(matcher.find(), "no staff row for " + displayName + ": " + body);
        return matcher.group();
    }

    private String supervisorBearer() {
        return JwtTestConfig.tokenFor(SUB_SUPERVISOR, "supervisor");
    }

    private String adjusterBearer() {
        return JwtTestConfig.tokenFor(SUB_L1_ONE, "adjuster_l1");
    }

    private String claimantBearer() {
        return JwtTestConfig.tokenFor("sub-staff-claimant", "claimant");
    }

    private HttpResponse<String> putJson(String path, String bearer, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
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

    private static byte[] multipart(java.util.Map<String, String> fields) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (java.util.Map.Entry<String, String> field : fields.entrySet()) {
                out.write(("--" + BOUNDARY + "\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + field.getKey()
                        + "\"\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.write((field.getValue() + "\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            out.write(("--" + BOUNDARY + "--\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }
}
