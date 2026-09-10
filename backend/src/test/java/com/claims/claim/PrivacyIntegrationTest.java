/**
 * V24 (V3 S9: GDPR + retention story) over real HTTP against real Postgres.
 * Export holds own data and only own data (two claimants, absence asserted);
 * anonymize redacts exactly the listed columns (holder name/email, claimant
 * sub/remarks, subject-authored note bodies, uploader links) while audit rows,
 * payments, decisions and descriptions stay byte-identical; a second run is a
 * stable 200; shared policies are a 400 with an explanation; the auth matrix
 * pins supervisor-only admin vs claimant-own export.
 */
package com.claims.claim;

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
import java.security.MessageDigest;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import com.claims.TestcontainersConfiguration;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PrivacyIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----PrivacyBoundary24";

    private static final String SUB_SUPERVISOR = "10000000-0000-0000-0000-000000000004";
    private static final String CLAIMANT_A = "sub-privacy-alice";
    private static final String CLAIMANT_B = "sub-privacy-bob";
    private static final String SHARED_SUB_ONE = "sub-privacy-shared-1";
    private static final String SHARED_SUB_TWO = "sub-privacy-shared-2";
    private static final String MATRIX_SUB = "sub-privacy-matrix";
    private static final String REPORT_SUB_A = "sub-privacy-report-a";
    private static final String REPORT_SUB_B = "sub-privacy-report-b";
    // Dedicated single-use policies per test (holder PII redaction is permanent
    // within the shared DB: reusing one policy across tests means an earlier
    // anonymize breaks later filings with a 404 holder mismatch).
    private static final String POL_A = "POL-30001"; // Ravi Menon, HLTH-BASIC
    private static final String POL_B = "POL-30010"; // Vikram Rao, HLTH-BASIC
    private static final String POL_SHARED = "POL-30005"; // Arjun Nair, HLTH-PLUS
    private static final String POL_MATRIX = "POL-30006"; // Kavya Reddy, HLTH-BASIC
    private static final String POL_REPORT_CLOSED = "POL-30002"; // Fatima Khan, HLTH-CRIT
    private static final String POL_REPORT_OPEN = "POL-30004"; // Lakshmi Iyer, PROP-HOME
    private static final String COVERS_HOME_JSON =
            "[{\"coverCode\":\"STRUCTURE\",\"claimedAmount\":500000},"
                    + "{\"coverCode\":\"CONTENTS\",\"claimedAmount\":100000}]";
    private static final String HOLDER_MATRIX = "Kavya Reddy";
    private static final String EMAIL_MATRIX = "kavya.reddy@example.test";
    private static final String HOLDER_REPORT_CLOSED = "Fatima Khan";
    private static final String EMAIL_REPORT_CLOSED = "fatima.khan@example.test";
    private static final String HOLDER_REPORT_OPEN = "Lakshmi Iyer";
    private static final String EMAIL_REPORT_OPEN = "lakshmi.iyer@example.test";
    // Per-policy cover sets for the close path (sub-limit-safe figures).
    private static final String COVERS_BASIC_JSON =
            "[{\"coverCode\":\"HOSPITALIZATION\",\"claimedAmount\":200000},"
                    + "{\"coverCode\":\"OPD\",\"claimedAmount\":10000}]";
    private static final String COVERS_CRIT_JSON =
            "[{\"coverCode\":\"CRITICAL_ILLNESS\",\"claimedAmount\":500000},"
                    + "{\"coverCode\":\"HOSPITALIZATION\",\"claimedAmount\":200000}]";

    private static final String RATIONALE =
            "Subject erasure request received and verified; handling now.";

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    // --- export: own + only own -------------------------------------------------

    @Test
    @org.junit.jupiter.api.Order(1)
    void exportA_containsOwnAndOnlyOwn() throws Exception {
        String own = fileFnol(CLAIMANT_A, POL_A, "Ravi Menon",
                "ravi.menon@example.test", "Clinic visit.");
        String other = fileFnol(CLAIMANT_B, POL_B, "Vikram Rao",
                "vikram.rao@example.test", "Daycare procedure.");

        HttpResponse<String> response = get("/api/privacy/me/export",
                JwtTestConfig.tokenFor(CLAIMANT_A, "claimant"));
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(dispositionOf(response).contains("attachment"), dispositionOf(response));
        assertTrue(contentTypeOf(response).contains("application/json"),
                contentTypeOf(response));

        String body = response.body();
        assertTrue(body.contains(own), body);
        assertTrue(body.contains("Clinic visit."), body);
        assertTrue(body.contains(POL_A), body);
        assertTrue(body.contains("\"subject\":\"" + CLAIMANT_A + "\""), body);
        // The wall: the other claimant's number, description, holder and policy
        // appear nowhere in this payload.
        assertFalse(body.contains(other), body);
        assertFalse(body.contains("Daycare procedure."), body);
        assertFalse(body.contains("Vikram Rao"), body);
        assertFalse(body.contains(POL_B), body);
        assertFalse(body.contains(CLAIMANT_B), body);
        // No export trail: EXPORT writes no privacy_request row (slice-literal).
        assertEquals(0, count("SELECT count(*) FROM privacy_request"),
                "export must not write privacy_request rows");
    }

    // --- anonymize: exactly the listed columns ----------------------------------

    @Test
    @org.junit.jupiter.api.Order(2)
    void anonymizeB_redactsListedColumnsKeepsHistoryAndIsIdempotent() throws Exception {
        String claimNumber = fileFnolWithPhoto(CLAIMANT_A, POL_A, "Ravi Menon",
                "ravi.menon@example.test", "Clinic visit with remarks trail.");
        Long claimId = idOf(claimNumber);

        // Subject-authored internal note (adjuster posts it, stamped with the
        // claimant sub by direct insert — the claimant never writes notes).
        // Plus one staff note that must survive erasure untouched.
        String staffSub = holderSubOf(claimNumber);
        jdbcTemplate.update(
                "INSERT INTO internal_note (claim_id, author_id, author_sub, body, created_at) "
                        + "VALUES (?, NULL, ?, 'Claimant-called note body.', now())",
                claimId, CLAIMANT_A);
        postJson("/api/claims/" + claimNumber + "/notes", holderBearer(claimNumber),
                "{\"body\":\"Staff assessment note stays.\"}");
        jdbcTemplate.update("UPDATE claim SET claimant_remarks = 'Please hurry.' "
                + "WHERE id = ?", claimId);
        // The FNOL photo has a NULL uploader (ClaimService stamps none); set it to
        // the subject so the uploader-nulling has something to clear.
        jdbcTemplate.update("UPDATE attachment SET uploaded_by_sub = ? WHERE claim_id = ?",
                CLAIMANT_A, claimId);
        // Close the claim so payments/decisions/descriptions exist to protect.
        closeValid(claimNumber);

        long auditBefore = count("SELECT count(*) FROM audit_log WHERE entity_type = 'CLAIM' "
                + "AND entity_id = ?", claimId);
        String paymentBefore = jdbcTemplate.queryForObject(
                "SELECT amount::text FROM payment WHERE claim_id = ? ORDER BY seq LIMIT 1",
                String.class, claimId);
        assertNotNull(paymentBefore);
        String decisionBefore = jdbcTemplate.queryForObject(
                "SELECT decision FROM claim WHERE id = ?", String.class, claimId);
        String decisionRemarksBefore = jdbcTemplate.queryForObject(
                "SELECT decision_remarks FROM claim WHERE id = ?", String.class, claimId);
        String descriptionBefore = jdbcTemplate.queryForObject(
                "SELECT loss_description FROM claim WHERE id = ?", String.class, claimId);

        HttpResponse<String> first = postJson("/api/admin/privacy/anonymize",
                supervisorBearer(),
                "{\"claimantSub\":\"" + CLAIMANT_A + "\",\"rationale\":\"" + RATIONALE + "\"}");
        assertEquals(200, first.statusCode(), first.body());
        String sha8 = sha8Hex(CLAIMANT_A);
        assertTrue(first.body().contains("\"anonymizedSub\":\"ANON:" + sha8 + "\""),
                first.body());

        // Exactly the listed columns.
        assertEquals("REDACTED", jdbcTemplate.queryForObject(
                "SELECT holder_name FROM policy WHERE policy_number = '" + POL_A + "'",
                String.class));
        assertEquals("redacted+" + sha8 + "@example.invalid",
                jdbcTemplate.queryForObject(
                        "SELECT holder_email FROM policy WHERE policy_number = '" + POL_A
                                + "'",
                        String.class));
        assertEquals("ANON:" + sha8, jdbcTemplate.queryForObject(
                "SELECT claimant_sub FROM claim WHERE id = ?", String.class, claimId));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT claimant_remarks FROM claim WHERE id = ?", String.class, claimId));
        assertEquals("[redacted]", jdbcTemplate.queryForObject(
                "SELECT body FROM internal_note WHERE claim_id = ? AND author_sub = ?",
                String.class, claimId, CLAIMANT_A));
        assertEquals("Staff assessment note stays.", jdbcTemplate.queryForObject(
                "SELECT body FROM internal_note WHERE claim_id = ? AND author_sub = ?",
                String.class, claimId, staffSub));
        assertEquals(0, count("SELECT count(*) FROM attachment WHERE claim_id = ? "
                + "AND uploaded_by_sub IS NOT NULL", claimId));

        // History intact: the CLAIM rows are unchanged in count (the
        // PRIVACY_ERASURE row is entity_type PRIVACY, not CLAIM), and exactly one
        // PRIVACY_ERASURE row was appended.
        assertEquals(auditBefore,
                count("SELECT count(*) FROM audit_log WHERE entity_type = 'CLAIM' "
                        + "AND entity_id = ?", claimId),
                "anonymize must not UPDATE audit rows");
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'PRIVACY_ERASURE'"),
                "exactly one PRIVACY_ERASURE audit row");
        assertEquals(paymentBefore, jdbcTemplate.queryForObject(
                "SELECT amount::text FROM payment WHERE claim_id = ? ORDER BY seq LIMIT 1",
                String.class, claimId));
        assertEquals(decisionBefore, jdbcTemplate.queryForObject(
                "SELECT decision FROM claim WHERE id = ?", String.class, claimId));
        assertEquals(decisionRemarksBefore, jdbcTemplate.queryForObject(
                "SELECT decision_remarks FROM claim WHERE id = ?", String.class, claimId));
        assertEquals(descriptionBefore, jdbcTemplate.queryForObject(
                "SELECT loss_description FROM claim WHERE id = ?", String.class, claimId));
        assertEquals(1, count("SELECT count(*) FROM privacy_request WHERE claimant_sub = ? "
                + "AND kind = 'ERASURE' AND status = 'COMPLETED' AND handled_by = ?",
                CLAIMANT_A, SUB_SUPERVISOR));

        // Timeline renders the anonymized actor as "Redacted".
        HttpResponse<String> timeline = get("/api/claims/" + claimNumber + "/timeline",
                supervisorBearer());
        assertEquals(200, timeline.statusCode(), timeline.body());
        assertFalse(timeline.body().contains("ANON:" + sha8), timeline.body());
        assertTrue(timeline.body().contains("Redacted"), timeline.body());

        // Second run: stable 200, values unchanged, a second ERASURE row + audit.
        HttpResponse<String> second = postJson("/api/admin/privacy/anonymize",
                supervisorBearer(),
                "{\"claimantSub\":\"" + CLAIMANT_A + "\",\"rationale\":\""
                        + RATIONALE + "\"}");
        assertEquals(200, second.statusCode(), second.body());
        assertEquals("ANON:" + sha8, jdbcTemplate.queryForObject(
                "SELECT claimant_sub FROM claim WHERE id = ?", String.class, claimId));
        assertEquals("REDACTED", jdbcTemplate.queryForObject(
                "SELECT holder_name FROM policy WHERE policy_number = '" + POL_A + "'",
                String.class));
        assertEquals(2, count("SELECT count(*) FROM privacy_request WHERE claimant_sub = ? "
                + "AND kind = 'ERASURE'", CLAIMANT_A));
        assertEquals(2, count("SELECT count(*) FROM audit_log WHERE action = 'PRIVACY_ERASURE'"));
    }

    // --- shared policy: 400 with an explanation ----------------------------------

    @Test
    @org.junit.jupiter.api.Order(3)
    void sharedC_policyAnonymizeIs400AndRedactsNothing() throws Exception {
        String first = fileSharedFnol(SHARED_SUB_ONE, "Family claim one.");
        String second = fileSharedFnol(SHARED_SUB_TWO, "Family claim two.");
        assertNotNull(first);
        assertNotNull(second);

        HttpResponse<String> response = postJson("/api/admin/privacy/anonymize",
                supervisorBearer(),
                "{\"claimantSub\":\"" + SHARED_SUB_ONE + "\",\"rationale\":\""
                        + RATIONALE + "\"}");
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains(POL_SHARED), response.body());
        assertTrue(response.body().contains("shared"), response.body().toLowerCase());

        // Nothing redacted, no trail written.
        assertEquals(SHARED_SUB_ONE, jdbcTemplate.queryForObject(
                "SELECT claimant_sub FROM claim WHERE claim_number = ?", String.class,
                first));
        assertEquals("Arjun Nair", jdbcTemplate.queryForObject(
                "SELECT holder_name FROM policy WHERE policy_number = '" + POL_SHARED + "'",
                String.class));
        assertEquals(0, count("SELECT count(*) FROM privacy_request"));
        assertEquals(0, count("SELECT count(*) FROM audit_log WHERE action = 'PRIVACY_ERASURE'"));
    }

    // --- retention report --------------------------------------------------------

    @Test
    @org.junit.jupiter.api.Order(4)
    void retentionD_reportCountsClosedClaimsInFixedBuckets() throws Exception {
        String closed = fileFnol(REPORT_SUB_A, POL_REPORT_CLOSED, HOLDER_REPORT_CLOSED,
                EMAIL_REPORT_CLOSED, "Old closed claim.", COVERS_CRIT_JSON);
        String open = fileFnol(REPORT_SUB_B, POL_REPORT_OPEN, HOLDER_REPORT_OPEN,
                EMAIL_REPORT_OPEN, "Still open claim.", COVERS_HOME_JSON);
        closeValidCrit(closed);
        // Backdate the closure: 8 years ago lands in the 6y + 7y buckets, not 10y.
        jdbcTemplate.update("UPDATE claim SET closed_at = now() - INTERVAL '8 years' "
                + "WHERE claim_number = ?", closed);

        HttpResponse<String> response = get("/api/admin/privacy/retention-report",
                supervisorBearer());
        assertEquals(200, response.statusCode(), response.body());
        String body = response.body();
        assertTrue(body.contains("\"policyClosedYears\":7"), body);
        assertTrue(body.contains("\"olderThan6y\":1"), body);
        assertTrue(body.contains("\"olderThan7y\":1"), body);
        assertTrue(body.contains("\"olderThan10y\":0"), body);
        assertNotNull(open);
    }

    // --- auth matrix --------------------------------------------------------------

    @Test
    @org.junit.jupiter.api.Order(5)
    void privacyE_surfaceAuthMatrix() throws Exception {
        String claimNumber = fileFnol(MATRIX_SUB, POL_MATRIX, HOLDER_MATRIX,
                EMAIL_MATRIX, "Auth matrix claim.");
        assertNotNull(claimNumber);
        String anonymize = "{\"claimantSub\":\"" + MATRIX_SUB + "\",\"rationale\":\""
                + RATIONALE + "\"}";

        // Export: anon 401, adjuster 403, supervisor 403 (claimants only),
        // claimant 200 on own (MATRIX_SUB's own filing above).
        assertEquals(401, get("/api/privacy/me/export", null).statusCode());
        assertEquals(403, get("/api/privacy/me/export", adjusterBearer()).statusCode());
        assertEquals(403, get("/api/privacy/me/export", supervisorBearer()).statusCode());
        assertEquals(200, get("/api/privacy/me/export",
                JwtTestConfig.tokenFor(MATRIX_SUB, "claimant")).statusCode());

        // Admin: anon 401, adjuster 403, claimant 403, supervisor 200.
        assertEquals(401, postJson("/api/admin/privacy/anonymize", null, anonymize)
                .statusCode());
        assertEquals(403, postJson("/api/admin/privacy/anonymize", adjusterBearer(),
                anonymize).statusCode());
        assertEquals(403, postJson("/api/admin/privacy/anonymize",
                JwtTestConfig.tokenFor(MATRIX_SUB, "claimant"), anonymize).statusCode());
        assertEquals(200, postJson("/api/admin/privacy/anonymize", supervisorBearer(),
                anonymize).statusCode());

        assertEquals(401, get("/api/admin/privacy/retention-report", null).statusCode());
        assertEquals(403, get("/api/admin/privacy/retention-report", adjusterBearer())
                .statusCode());
        assertEquals(403, get("/api/admin/privacy/retention-report",
                JwtTestConfig.tokenFor(MATRIX_SUB, "claimant")).statusCode());
        assertEquals(200, get("/api/admin/privacy/retention-report", supervisorBearer())
                .statusCode());

        // Missing rationale is a 400, not a silent accept.
        assertEquals(400, postJson("/api/admin/privacy/anonymize", supervisorBearer(),
                "{\"claimantSub\":\"" + MATRIX_SUB + "\"}").statusCode());
    }

    // --- drivers -------------------------------------------------------------------

    private static final java.util.concurrent.atomic.AtomicInteger LOSS_DAY =
            new java.util.concurrent.atomic.AtomicInteger(3);

    /**
     * Distinct loss dates per filing: the FNOL duplicate guard (same policy +
     * loss date + cover set within 24h) would otherwise collapse same-policy
     * filings in one run into one claim. Dates stay within the fileable window
     * (past 10 years, never future): they march backward from today so every
     * filing in the suite is unique.
     */
    private static String nextLossDate() {
        synchronized (PrivacyIntegrationTest.class) {
            int day = LOSS_DAY.getAndIncrement();
            return java.time.LocalDate.now().minusDays(30 + day).toString();
        }
    }

    private String fileFnol(String claimantSub, String policyNumber, String holderName,
            String holderEmail, String description) throws Exception {
        return fileFnol(claimantSub, policyNumber, holderName, holderEmail, description,
                COVERS_BASIC_JSON);
    }

    /** HLTH-PLUS shared-policy filings (POL-30005 carries the 5-cover set). */
    private String fileSharedFnol(String claimantSub, String description) throws Exception {
        return fileFnol(claimantSub, POL_SHARED, "Arjun Nair",
                "arjun.nair@example.test", description,
                "[{\"coverCode\":\"HOSPITALIZATION\",\"claimedAmount\":200000},"
                        + "{\"coverCode\":\"OPD\",\"claimedAmount\":10000},"
                        + "{\"coverCode\":\"MATERNITY\",\"claimedAmount\":20000}]");
    }

    private String fileFnol(String claimantSub, String policyNumber, String holderName,
            String holderEmail, String description, String coversJson) throws Exception {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("policyNumber", policyNumber);
        fields.put("holderName", holderName);
        fields.put("holderEmail", holderEmail);
        fields.put("lossDate", nextLossDate());
        fields.put("lossLocation", "London");
        fields.put("lossDescription", description);
        fields.put("remarks", "Filing remarks for " + claimantSub + ".");
        fields.put("covers", coversJson);
        HttpResponse<String> response = post("/api/claims",
                JwtTestConfig.tokenFor(claimantSub, "claimant"), multipart(fields, null));
        assertEquals(201, response.statusCode(),
                "FNOL " + policyNumber + " / " + holderEmail + " -> " + response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    private String fileFnolWithPhoto(String claimantSub, String policyNumber,
            String holderName, String holderEmail, String description) throws Exception {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("policyNumber", policyNumber);
        fields.put("holderName", holderName);
        fields.put("holderEmail", holderEmail);
        fields.put("lossDate", nextLossDate());
        fields.put("lossLocation", "London");
        fields.put("lossDescription", description);
        fields.put("covers", COVERS_BASIC_JSON);
        HttpResponse<String> response = post("/api/claims",
                JwtTestConfig.tokenFor(claimantSub, "claimant"),
                multipart(fields, new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4}));
        assertEquals(201, response.statusCode(),
                "FNOL " + policyNumber + " / " + holderEmail + " -> " + response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    /** Drives a BASIC-family claim to a valid closure (mirrors the S8 close path). */
    private void closeValid(String claimNumber) throws Exception {
        closeWith(claimNumber, "HOSPITALIZATION", 180000, 20000, "OPD", 9000, 8000);
    }

    /** Drives the HLTH-CRIT retention claim to a valid closure. */
    private void closeValidCrit(String claimNumber) throws Exception {
        closeWith(claimNumber, "CRITICAL_ILLNESS", 500000, 100000, "HOSPITALIZATION",
                180000, 80000);
    }

    private void closeWith(String claimNumber, String coverOne, long assessedOne,
            long approvedOne, String coverTwo, long assessedTwo, long approvedTwo)
            throws Exception {
        String bearer = holderBearer(claimNumber);
        assertEquals(200, postJson("/api/claims/" + claimNumber + "/review", bearer,
                "{\"action\":\"ADVANCE\",\"rationale\":\"Verifying the claim.\"}")
                .statusCode());
        HttpResponse<String> created = postJson(
                "/api/claims/" + claimNumber + "/verifications", bearer,
                "{\"type\":\"DIGITAL\",\"notes\":\"Checking records.\"}");
        assertEquals(200, created.statusCode(), created.body());
        String staged = get("/api/claims/" + claimNumber + "/staged", bearer).body();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\"id\":(\\d+),\"type\":([^,]+),\"status\":\"([A-Z_]+)\"").matcher(staged);
        while (matcher.find()) {
            if (!"COMPLETE".equals(matcher.group(3))) {
                HttpResponse<String> completed = putJson("/api/claims/" + claimNumber
                        + "/verifications/" + matcher.group(1), bearer,
                        "{\"status\":\"COMPLETE\",\"outcome\":\"PASSED\","
                                + "\"notes\":\"Checked and verified.\"}");
                assertEquals(200, completed.statusCode(), completed.body());
            }
        }
        HttpResponse<String> assessed = putJson(
                "/api/claims/" + claimNumber + "/assessment", bearer,
                "{\"rationale\":\"Bills verified.\",\"covers\":["
                        + "{\"coverCode\":\"" + coverOne + "\","
                        + "\"assessedAmount\":" + assessedOne + "},"
                        + "{\"coverCode\":\"" + coverTwo + "\",\"assessedAmount\":"
                        + assessedTwo + "}],"
                        + "\"expectedVersion\":" + versionOf(claimNumber) + "}");
        assertEquals(200, assessed.statusCode(), assessed.body());
        HttpResponse<String> decided = postJson(
                "/api/claims/" + claimNumber + "/cover-decision", bearer,
                "{\"rationale\":\"Bills verified against the submitted documents; "
                        + "closing now.\",\"expectedVersion\":" + versionOf(claimNumber)
                        + ",\"covers\":["
                        + "{\"coverCode\":\"" + coverOne + "\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":" + approvedOne
                        + ",\"remarks\":\"Bills verified.\"},"
                        + "{\"coverCode\":\"" + coverTwo + "\",\"decision\":\"APPROVED\","
                        + "\"approvedAmount\":" + approvedTwo
                        + ",\"remarks\":\"Bills verified.\"}]}");
        assertEquals(200, decided.statusCode(), decided.body());
        assertEquals("CLOSED", statusOf(claimNumber));
    }

    private static String sha8Hex(String sub) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(sub.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 8);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Long idOf(String claimNumber) {
        return jdbcTemplate.queryForObject("SELECT id FROM claim WHERE claim_number = ?",
                Long.class, claimNumber);
    }

    private Long versionOf(String claimNumber) {
        return jdbcTemplate.queryForObject("SELECT version FROM claim WHERE claim_number = ?",
                Long.class, claimNumber);
    }

    private String statusOf(String claimNumber) {
        return jdbcTemplate.queryForObject("SELECT status FROM claim WHERE claim_number = ?",
                String.class, claimNumber);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String holderSubOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT a.keycloak_sub FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
    }

    private String holderBearer(String claimNumber) {
        String level = jdbcTemplate.queryForObject(
                "SELECT a.level FROM claim c JOIN app_user a "
                        + "ON a.id = c.assigned_adjuster_id WHERE c.claim_number = ?",
                String.class, claimNumber);
        return JwtTestConfig.tokenFor(holderSubOf(claimNumber),
                "adjuster_" + level.toLowerCase());
    }

    private String adjusterBearer() {
        return JwtTestConfig.tokenFor("10000000-0000-0000-0000-000000000001",
                "adjuster_l1");
    }

    private String supervisorBearer() {
        return JwtTestConfig.tokenFor(SUB_SUPERVISOR, "supervisor");
    }

    private String contentTypeOf(HttpResponse<String> response) {
        return response.headers().firstValue("content-type").orElse("");
    }

    private String dispositionOf(HttpResponse<String> response) {
        return response.headers().firstValue("content-disposition").orElse("");
    }

    private static byte[] multipart(Map<String, String> fields, byte[] photo) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Map.Entry<String, String> field : fields.entrySet()) {
                out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + field.getKey()
                        + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write((field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            if (photo != null) {
                out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"photos\"; filename=\""
                        + "photo.png\"\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Type: image/png\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(photo);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
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

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }
}
