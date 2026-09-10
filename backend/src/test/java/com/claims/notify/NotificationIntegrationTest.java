/**
 * V25 (V3 S10: notifications beyond email) over real HTTP against real
 * Postgres. Every claimant event writes an outbox row (the three legacy kinds
 * plus the new NEED_INFO/REFERRAL/REOPEN claimant mails) and an INAPP row in
 * the same transaction; the in-app center lists own rows newest-first with the
 * unread bell count; read/others'-read is own-ok/others'-404; prefs round-trip
 * and inapp opt-out suppresses INAPP rows (outbox mail still flows).
 */
package com.claims.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import com.claims.TestcontainersConfiguration;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----NotificationBoundary25";

    private static final String SUB_L1_ONE = "10000000-0000-0000-0000-000000000001";
    private static final String SUB_SUPERVISOR = "10000000-0000-0000-0000-000000000004";
    private static final String CLAIMANT = "sub-claimant-notify";
    private static final String OTHER = "sub-claimant-notify-other";
    private static final String PREFS_SUB = "sub-claimant-notify-prefs";
    private static final String OPT_OUT_SUB = "sub-claimant-notify-optout";

    private static final String COVERS_JSON =
            "[{\"coverCode\":\"HOSPITALIZATION\",\"claimedAmount\":200000},"
                    + "{\"coverCode\":\"OPD\",\"claimedAmount\":40000}]";

    private static final AtomicInteger LOSS_DAY = new AtomicInteger(1);

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    // --- the full event journey: every event pings outbox + in-app ----------------

    @Test
    void fullJourneyWritesOutboxAndInappForEveryEvent() throws Exception {
        String claimNumber = fileCoverFnol(CLAIMANT, "Ada Lovelace",
                "ada.lovelace@example.test");
        Long claimId = idOf(claimNumber);
        String bearer = holderBearer(claimNumber);

        // FNOL + its FNOL assignment: legacy kinds + INAPP mirrors.
        assertEquals(1, outboxKinds(claimId, "FNOL"));
        assertEquals(1, outboxKinds(claimId, "ASSIGNMENT"));
        assertEquals(1, inappEvents(claimId, "FNOL_RECEIVED"));
        assertEquals(1, inappEvents(claimId, "ASSIGNED"));

        // NEED_INFO send: the new NEED_INFO mail + its mirror.
        HttpResponse<String> parked = postJson("/api/claims/" + claimNumber + "/review",
                bearer,
                "{\"action\":\"NEED_INFO\",\"requestedItems\":\"Upload the bill.\"}");
        assertEquals(200, parked.statusCode(), parked.body());
        assertEquals(1, outboxKinds(claimId, "NEED_INFO"));
        assertEquals(1, inappEvents(claimId, "NEED_INFO_SENT"));
        String needInfoMail = jdbc.queryForObject(
                "SELECT body FROM email_outbox WHERE claim_id = ? AND kind = 'NEED_INFO'",
                String.class, claimId);
        assertTrue(needInfoMail.contains("Upload the bill."), needInfoMail);

        // NEED_INFO response: INAPP-only (no mail today) + the reassign ASSIGNED.
        long newKindsBefore = outboxTotal(claimId, "NEED_INFO", "REFERRAL", "REOPEN");
        HttpResponse<String> response = postJson(
                "/api/claims/" + claimNumber + "/need-info-response",
                JwtTestConfig.tokenFor(CLAIMANT, "claimant"),
                "{\"message\":\"Uploaded the bill.\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(newKindsBefore, outboxTotal(claimId, "NEED_INFO", "REFERRAL", "REOPEN"),
                "the response adds no outbox mail");
        assertEquals(1, inappEvents(claimId, "NEED_INFO_RESPONSE"));
        assertEquals(2, inappEvents(claimId, "ASSIGNED"),
                "the response reassign gives a second ASSIGNED ping");
        bearer = holderBearer(claimNumber);

        // Refer: the new REFERRAL mail + its mirror (pre-decision, auto → L2).
        HttpResponse<String> referred = postJson("/api/claims/" + claimNumber + "/refer",
                bearer, "{\"auto\":true,\"reason\":\"Needs a senior eye.\"}");
        assertEquals(200, referred.statusCode(), referred.body());
        assertEquals(1, outboxKinds(claimId, "REFERRAL"));
        assertEquals(1, inappEvents(claimId, "REFERRED"));
        String referralMail = jdbc.queryForObject(
                "SELECT body FROM email_outbox WHERE claim_id = ? AND kind = 'REFERRAL'",
                String.class, claimId);
        assertTrue(referralMail.contains("Needs a senior eye."), referralMail);
        bearer = holderBearer(claimNumber);

        // Decide within authority (L2 400000; approved 80000): DECIDED mirror.
        driveClaimToDecision(claimNumber, bearer);
        HttpResponse<String> decided = postJson(
                "/api/claims/" + claimNumber + "/cover-decision", bearer,
                "{\"rationale\":\"Within authority to close now.\",\"expectedVersion\":"
                        + versionOf(claimNumber) + ",\"covers\":["
                        + "{\"coverCode\":\"HOSPITALIZATION\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":80000,\"remarks\":\"Bills verified.\"},"
                        + "{\"coverCode\":\"OPD\",\"decision\":\"REJECTED\","
                        + "\"remarks\":\"Pre-dates the waiting period.\","
                        + "\"denialReason\":\"EXCLUDED_PER_CLAUSE\"}]}");
        assertEquals(200, decided.statusCode(), decided.body());
        assertEquals("CLOSED", statusOf(claimNumber));
        assertEquals(1, outboxKinds(claimId, "DECISION"));
        assertEquals(1, inappEvents(claimId, "DECIDED"));

        // Reopen: the REOPEN claimant mail (own kind, S6 subject kept) + mirror.
        HttpResponse<String> reopened = postJson(
                "/api/claims/" + claimNumber + "/reopen", supervisorBearer(),
                "{\"rationale\":\"New hospital evidence received after closure; "
                        + "re-examining.\",\"expectedVersion\":"
                        + versionOf(claimNumber) + "}");
        assertEquals(200, reopened.statusCode(), reopened.body());
        assertEquals(1, outboxKinds(claimId, "REOPEN"));
        String reopenSubject = jdbc.queryForObject(
                "SELECT subject FROM email_outbox WHERE claim_id = ? AND kind = 'REOPEN'",
                String.class, claimId);
        assertTrue(reopenSubject.contains("reopened"), reopenSubject);
        assertEquals(1, inappEvents(claimId, "REOPENED"));
        // The reopen reassign lands a third ASSIGNED ping.
        assertEquals(3, inappEvents(claimId, "ASSIGNED"));

        // The bell: mine lists own rows newest-first with the unread count.
        HttpResponse<String> mine = get("/api/notifications/mine",
                JwtTestConfig.tokenFor(CLAIMANT, "claimant"));
        assertEquals(200, mine.statusCode(), mine.body());
        assertTrue(mine.body().contains("\"unread\":9"), mine.body());
        assertTrue(mine.body().contains("\"event\":\"REOPENED\""), mine.body());
        assertTrue(mine.body().contains("\"totalElements\":9"), mine.body());
        int reopenedAt = mine.body().indexOf("\"event\":\"REOPENED\"");
        int fnolAt = mine.body().indexOf("\"event\":\"FNOL_RECEIVED\"");
        assertTrue(reopenedAt >= 0 && fnolAt > reopenedAt,
                "newest first: " + mine.body());
    }

    // --- read matrix: own read ok (idempotent), another's id 404 ------------------

    @Test
    void readMatrixOwnOkOthers404() throws Exception {
        String mine = fileCoverFnol(CLAIMANT, "Ada Lovelace",
                "ada.lovelace@example.test");
        String theirs = fileCoverFnol(OTHER, "Vikram Rao",
                "vikram.rao@example.test");
        long myId = firstNotificationId(mine);
        long theirId = firstNotificationId(theirs);

        // Own read: ok, marks read, idempotent.
        HttpResponse<String> read = post(
                "/api/notifications/" + myId + "/read",
                JwtTestConfig.tokenFor(CLAIMANT, "claimant"));
        assertEquals(200, read.statusCode(), read.body());
        assertTrue(read.body().contains("\"read\":true"), read.body());
        HttpResponse<String> reread = post(
                "/api/notifications/" + myId + "/read",
                JwtTestConfig.tokenFor(CLAIMANT, "claimant"));
        assertEquals(200, reread.statusCode(), reread.body());

        // The bell drops by one.
        HttpResponse<String> after = get("/api/notifications/mine",
                JwtTestConfig.tokenFor(CLAIMANT, "claimant"));
        assertEquals(200, after.statusCode(), after.body());
        assertTrue(after.body().contains("\"unread\":1"), after.body());

        // Another claimant's row id is a 404, never a 403 — and stays unread.
        HttpResponse<String> cross = post(
                "/api/notifications/" + theirId + "/read",
                JwtTestConfig.tokenFor(CLAIMANT, "claimant"));
        assertEquals(404, cross.statusCode(), cross.body());
        HttpResponse<String> theirMine = get("/api/notifications/mine",
                JwtTestConfig.tokenFor(OTHER, "claimant"));
        assertTrue(theirMine.body().contains("\"unread\":2"), theirMine.body());
        assertFalse(theirMine.body().contains(mine),
                "no cross-claimant leakage: " + theirMine.body());

        // Unknown ids are a 404 too.
        assertEquals(404, post("/api/notifications/999999/read",
                JwtTestConfig.tokenFor(CLAIMANT, "claimant")).statusCode());
    }

    // --- prefs round-trip + opt-out honored ----------------------------------------

    @Test
    void prefsRoundTripAndOptOutSuppressesInappOnly() throws Exception {
        String bearer = JwtTestConfig.tokenFor(PREFS_SUB, "claimant");

        // Defaults before any save.
        HttpResponse<String> defaults = get("/api/notifications/preferences", bearer);
        assertEquals(200, defaults.statusCode(), defaults.body());
        assertTrue(defaults.body().contains("\"emailEvents\":true"), defaults.body());
        assertTrue(defaults.body().contains("\"inappEvents\":true"), defaults.body());
        assertTrue(defaults.body().contains("\"smsEvents\":false"), defaults.body());

        // Round-trip a full save (incl. phone).
        HttpResponse<String> saved = putJson("/api/notifications/preferences", bearer,
                "{\"emailEvents\":false,\"inappEvents\":true,\"smsEvents\":true,"
                        + "\"phone\":\"+44 7700 900123\"}");
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(saved.body().contains("\"emailEvents\":false"), saved.body());
        assertTrue(saved.body().contains("\"smsEvents\":true"), saved.body());
        assertTrue(saved.body().contains("+44 7700 900123"), saved.body());
        HttpResponse<String> reread = get("/api/notifications/preferences", bearer);
        assertEquals(200, reread.statusCode(), reread.body());
        assertTrue(reread.body().contains("\"emailEvents\":false"), reread.body());

        // An over-long phone is a 400.
        assertEquals(400, putJson("/api/notifications/preferences", bearer,
                "{\"phone\":\"+44 7700 90012345678901234\"}").statusCode());

        // Opt-out: INAPP rows stop, outbox mail still flows.
        String optOutBearer = JwtTestConfig.tokenFor(OPT_OUT_SUB, "claimant");
        HttpResponse<String> opted = putJson("/api/notifications/preferences",
                optOutBearer, "{\"inappEvents\":false}");
        assertEquals(200, opted.statusCode(), opted.body());
        String claimNumber = fileFnolForPolicy(OPT_OUT_SUB, "POL-30006",
                "Kavya Reddy", "kavya.reddy@example.test");
        Long claimId = idOf(claimNumber);
        assertEquals(1, outboxKinds(claimId, "FNOL"),
                "opt-out never blocks mail");
        assertEquals(0, count("SELECT count(*) FROM notification WHERE claim_id = ?",
                claimId), "opt-out suppresses every INAPP row");
        HttpResponse<String> mine = get("/api/notifications/mine", optOutBearer);
        assertEquals(200, mine.statusCode(), mine.body());
        assertTrue(mine.body().contains("\"unread\":0"), mine.body());
        assertTrue(mine.body().contains("\"totalElements\":0"), mine.body());
    }

    // --- auth matrix: staff + anon off the claimant center -------------------------

    @Test
    void notificationSurfaceIsClaimantOnly() throws Exception {
        assertEquals(401, getStatus("/api/notifications/mine", null));
        assertEquals(401, postStatus("/api/notifications/1/read", null));
        assertEquals(401, getStatus("/api/notifications/preferences", null));
        for (String bearer : new String[] {
                JwtTestConfig.tokenFor(SUB_L1_ONE, "adjuster_l1"),
                JwtTestConfig.tokenFor(SUB_SUPERVISOR, "supervisor") }) {
            assertEquals(403, getStatus("/api/notifications/mine", bearer));
            assertEquals(403, postStatus("/api/notifications/1/read", bearer));
            assertEquals(403, getStatus("/api/notifications/preferences", bearer));
        }
        assertEquals(200, getStatus("/api/notifications/mine",
                JwtTestConfig.tokenFor(CLAIMANT, "claimant")));
    }

    // --- helpers -------------------------------------------------------------------

    private String fileCoverFnol(String claimantSub, String holderName,
            String holderEmail) throws Exception {
        if (OTHER.equals(claimantSub)) {
            return fileCoverFnolOnPolicy("POL-30010", claimantSub, holderName,
                    holderEmail, "[{\"coverCode\":\"OPD\",\"claimedAmount\":5000}]");
        }
        return fileCoverFnolOnPolicy("POL-10001", claimantSub, holderName, holderEmail,
                COVERS_JSON);
    }

    private String fileCoverFnolFor(String policyNumber, String claimantSub,
            String holderName, String holderEmail) throws Exception {
        return fileCoverFnolOnPolicy(policyNumber, claimantSub, holderName, holderEmail,
                COVERS_JSON);
    }

    private String fileCoverFnolOnPolicy(String policyNumber, String claimantSub,
            String holderName, String holderEmail, String coversJson) throws Exception {
        String lossDate = "2026-08-" + String.format("%02d",
                LOSS_DAY.getAndIncrement() % 27 + 1);
        Map<String, String> fields = new HashMap<>();
        fields.put("policyNumber", policyNumber);
        fields.put("holderName", holderName);
        fields.put("holderEmail", holderEmail);
        fields.put("lossDate", lossDate);
        fields.put("lossLocation", "London");
        fields.put("lossDescription", "Hospital stay plus follow-up visits.");
        fields.put("covers", coversJson);
        HttpResponse<String> response = post("/api/claims",
                JwtTestConfig.tokenWithClaims(claimantSub, holderEmail, "claimant"),
                multipart(fields));
        assertEquals(201, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    /** No-cover filing for the opt-out test (keeps the policy choice free). */
    private String fileFnolForPolicy(String claimantSub, String policyNumber,
            String holderName, String holderEmail) throws Exception {
        String lossDate = "2026-07-" + String.format("%02d",
                LOSS_DAY.getAndIncrement() % 27 + 1);
        Map<String, String> fields = new HashMap<>();
        fields.put("policyNumber", policyNumber);
        fields.put("holderName", holderName);
        fields.put("holderEmail", holderEmail);
        fields.put("lossDate", lossDate);
        fields.put("lossLocation", "Leeds");
        fields.put("lossDescription", "Clinic visit for tests.");
        HttpResponse<String> response = post("/api/claims",
                JwtTestConfig.tokenWithClaims(claimantSub, holderEmail, "claimant"),
                multipart(fields));
        assertEquals(201, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    private void driveClaimToDecision(String claimNumber, String bearer)
            throws Exception {
        assertEquals(200, postJson("/api/claims/" + claimNumber + "/review", bearer,
                "{\"action\":\"ADVANCE\",\"rationale\":\"Verifying.\"}").statusCode());
        HttpResponse<String> created = postJson(
                "/api/claims/" + claimNumber + "/verifications", bearer,
                "{\"type\":\"DIGITAL\",\"notes\":\"Checking records.\"}");
        assertEquals(200, created.statusCode(), created.body());
        long verificationId = Long.parseLong(
                created.body().replaceAll(".*\"id\":(\\d+).*", "$1"));
        HttpResponse<String> completed = putJson(
                "/api/claims/" + claimNumber + "/verifications/" + verificationId,
                bearer,
                "{\"status\":\"COMPLETE\",\"outcome\":\"PASSED\",\"notes\":\"Match.\"}");
        assertEquals(200, completed.statusCode(), completed.body());
        completeAllVerifications(claimNumber, bearer);
        String body = "{\"rationale\":\"Bills verified.\",\"covers\":["
                + "{\"coverCode\":\"HOSPITALIZATION\",\"assessedAmount\":180000},"
                + "{\"coverCode\":\"OPD\",\"assessedAmount\":25000}],"
                + "\"expectedVersion\":" + versionOf(claimNumber) + "}";
        HttpResponse<String> assessed = putJson(
                "/api/claims/" + claimNumber + "/assessment", bearer, body);
        assertEquals(200, assessed.statusCode(), assessed.body());
    }

    private void completeAllVerifications(String claimNumber, String bearer)
            throws Exception {
        String staged = get("/api/claims/" + claimNumber + "/staged", bearer).body();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\"id\":(\\d+),\"type\":([^,]+),\"status\":\"([A-Z_]+)\"")
                .matcher(staged);
        while (matcher.find()) {
            if (!"COMPLETE".equals(matcher.group(3))) {
                HttpResponse<String> completed = putJson("/api/claims/" + claimNumber
                        + "/verifications/" + matcher.group(1), bearer,
                        "{\"status\":\"COMPLETE\",\"outcome\":\"PASSED\","
                                + "\"notes\":\"Checked.\"}");
                assertEquals(200, completed.statusCode(), completed.body());
            }
        }
    }

    private String holderBearer(String claimNumber) {
        String sub = jdbc.queryForObject(
                "SELECT a.keycloak_sub FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        String level = jdbc.queryForObject(
                "SELECT a.level FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        return JwtTestConfig.tokenFor(sub, "adjuster_" + level.toLowerCase());
    }

    private String supervisorBearer() {
        return JwtTestConfig.tokenFor(SUB_SUPERVISOR, "supervisor");
    }

    private Long idOf(String claimNumber) {
        return jdbc.queryForObject("SELECT id FROM claim WHERE claim_number = ?",
                Long.class, claimNumber);
    }

    private Long versionOf(String claimNumber) {
        return jdbc.queryForObject("SELECT version FROM claim WHERE claim_number = ?",
                Long.class, claimNumber);
    }

    private String statusOf(String claimNumber) {
        return jdbc.queryForObject("SELECT status FROM claim WHERE claim_number = ?",
                String.class, claimNumber);
    }

    private long firstNotificationId(String claimNumber) {
        Long id = jdbc.queryForObject(
                "SELECT n.id FROM notification n JOIN claim c ON c.id = n.claim_id "
                        + "WHERE c.claim_number = ? ORDER BY n.id LIMIT 1",
                Long.class, claimNumber);
        assertTrue(id != null, "expected INAPP rows for " + claimNumber);
        return id;
    }

    private long outboxKinds(long claimId, String kind) {
        return count("SELECT count(*) FROM email_outbox WHERE claim_id = ? AND kind = ?",
                claimId, kind);
    }

    private long outboxTotal(long claimId, String... kinds) {
        StringBuilder placeholders = new StringBuilder();
        for (String kind : kinds) {
            if (placeholders.length() > 0) {
                placeholders.append(",");
            }
            placeholders.append("?");
        }
        return count("SELECT count(*) FROM email_outbox WHERE claim_id = ? AND kind IN ("
                + placeholders + ")", prepend(claimId, kinds));
    }

    private Object[] prepend(long claimId, String... kinds) {
        Object[] args = new Object[kinds.length + 1];
        args[0] = claimId;
        System.arraycopy(kinds, 0, args, 1, kinds.length);
        return args;
    }

    private long inappEvents(long claimId, String event) {
        return count("SELECT count(*) FROM notification WHERE claim_id = ? AND event = ?",
                claimId, event);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static byte[] multipart(Map<String, String> fields) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (Map.Entry<String, String> field : fields.entrySet()) {
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

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int getStatus(String path, String bearer) throws Exception {
        return get(path, bearer).statusCode();
    }

    private HttpResponse<String> post(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int postStatus(String path, String bearer) throws Exception {
        return post(path, bearer).statusCode();
    }

    private HttpResponse<String> post(String path, String bearer, byte[] body)
            throws Exception {
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
}
