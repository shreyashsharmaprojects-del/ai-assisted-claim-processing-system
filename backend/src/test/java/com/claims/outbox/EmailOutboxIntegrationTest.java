package com.claims.outbox;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.claims.TestcontainersConfiguration;
import com.claims.support.ClaimTableResettingTest;
import com.claims.support.JwtTestConfig;

/**
 * R2 acceptance: the email outbox. Real HTTP through the real Spring context against a
 * real PostgreSQL (Flyway V10 lays down email_outbox) with a real SMTP capture (Mailpit).
 * The scheduler is off in tests (see ClaimTableResettingTest) — dispatch() is driven
 * directly, and controllers flush it post-commit so Mailpit assertions observe delivery.
 *
 * <p>SMTP-down is simulated by pointing a test-local dispatcher at an unroutable port;
 * poison delivery by a JavaMailSender stub that always throws. The shared dispatcher bean
 * is never reconfigured — production delivery stays intact.
 */
@Import({TestcontainersConfiguration.class, JwtTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmailOutboxIntegrationTest extends ClaimTableResettingTest {

    private static final String BOUNDARY = "----OutboxTestBoundary2";
    private static final String SUB_L1_ONE = "10000000-0000-0000-0000-000000000001";
    private static final String CLAIMANT = "sub-claimant-outbox";

    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
            DockerImageName.parse("axllent/mailpit:v1.22"))
            .withExposedPorts(1025, 8025);

    private static final Path UPLOADS = createUploadsDir();

    static {
        MAILPIT.start();
    }

    private static Path createUploadsDir() {
        try {
            return Files.createTempDirectory("claims-outbox-test-uploads");
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
    private EmailOutboxDispatcher dispatcher;
    @Autowired
    private EmailOutboxRepository outbox;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clearMailbox() throws Exception {
        http.send(HttpRequest.newBuilder(URI.create(mailpitUrl() + "/api/v1/messages"))
                .DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    // --- closure writes PENDING in-transaction; dispatcher delivers ----------------

    @Test
    void closureWritesOutboxPendingAndDispatcherDeliversToMailpit() throws Exception {
        String claimNumber = fileHomeFnol();
        decide(claimNumber, "{\"decision\":\"DENIED\",\"rationale\":\"Not covered by policy.\"}");

        // The controller flushes post-commit: rows are SENT and Mailpit holds the mails.
        assertEquals("CLOSED", statusOf(claimNumber));
        assertEquals(0, count("SELECT count(*) FROM email_outbox WHERE status = 'PENDING'"));
        long sent = count("SELECT count(*) FROM email_outbox WHERE status = 'SENT'");
        assertTrue(sent >= 3, "FNOL + assignment + decision rows SENT, got " + sent);
        String inbox = mailpitFetch();
        assertTrue(inbox.contains("Decision on claim " + claimNumber), inbox);
        assertTrue(inbox.contains("not been approved"), inbox);
        assertTrue(inbox.contains("Claim " + claimNumber + " received"), inbox);
        assertTrue(inbox.contains("is now with an adjuster"), inbox);

        // Outbox list (supervisor) shows the decision row SENT, newest first.
        HttpResponse<String> list = getResponse("/api/outbox?status=SENT", supervisorBearer());
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("DECISION"), list.body());
        assertTrue(list.body().contains("\"totalElements\""), list.body());
    }

    // --- SMTP down at closure: claim still closes, row stays PENDING ---------------

    @Test
    void smtpDownAtClosureStillClosesWithPendingRowThenDispatchDelivers() throws Exception {
        String claimNumber = fileHomeFnol();
        // Simulate SMTP down by breaking delivery on a test-local dispatcher pointed at an
        // unroutable port — the shared bean (and Mailpit) is untouched.
        EmailOutboxDispatcher downDispatcher = new EmailOutboxDispatcher(outbox,
                brokenMailSender(), "no-reply@claims.test", 8, 50);

        // Enqueue the decision row directly (same-transaction write the service performs),
        // then attempt delivery while SMTP is down: attempts rise, row stays PENDING.
        // (The FNOL/assignment rows were already SENT by the controller flush — assert on
        // the DECISION row specifically.)
        long claimId = idOf(claimNumber);
        outbox.enqueue(claimId, "DECISION", "ada.lovelace@example.test",
                "Decision on claim " + claimNumber, "body");
        downDispatcher.dispatch();
        assertEquals(1, count("SELECT count(*) FROM email_outbox WHERE status = 'PENDING' "
                + "AND kind = 'DECISION' AND attempts >= 1"));
        assertFalse(mailpitFetch().contains("Decision on claim " + claimNumber),
                "nothing delivered while SMTP is down");

        // SMTP restored: the real dispatcher sends, row SENT, Mailpit receives.
        // (Backoff from the failed attempt may briefly defer it — clear the gate.)
        jdbcTemplate.update(
                "UPDATE email_outbox SET next_attempt_at = now() "
                        + "WHERE kind = 'DECISION' AND claim_id = ?",
                claimId);
        dispatcher.dispatch();
        assertEquals("SENT", jdbcTemplate.queryForObject(
                "SELECT status FROM email_outbox WHERE kind = 'DECISION' AND claim_id = ?",
                String.class, claimId));
        assertTrue(mailpitFetch().contains("Decision on claim " + claimNumber));
    }

    // --- poison address: FAILED + last_error; retry re-queues -----------------------

    @Test
    void poisonRowParksFailedAndSupervisorRetryRequeues() throws Exception {
        String claimNumber = fileHomeFnol();
        long claimId = idOf(claimNumber);
        outbox.enqueue(claimId, "DECISION", "poison@example.test",
                "Decision on claim " + claimNumber, "body");
        long rowId = jdbcTemplate.queryForObject(
                "SELECT id FROM email_outbox WHERE to_address = 'poison@example.test'",
                Long.class);

        // Always-failing sender with max-attempts 1: one pass parks it FAILED with an error.
        EmailOutboxDispatcher poison = new EmailOutboxDispatcher(outbox, alwaysFailingSender(),
                "no-reply@claims.test", 1, 50);
        poison.dispatch();
        assertEquals("FAILED", statusOfRow(rowId));
        String lastError = jdbcTemplate.queryForObject(
                "SELECT last_error FROM email_outbox WHERE id = ?", String.class, rowId);
        assertTrue(lastError != null && !lastError.isBlank(), "last_error must be set");

        // Supervisor retry → PENDING again; retry of a SENT row → 400.
        HttpResponse<String> retry = post("/api/outbox/" + rowId + "/retry", supervisorBearer());
        assertEquals(200, retry.statusCode(), retry.body());
        assertTrue(retry.body().contains("\"status\":\"PENDING\""), retry.body());

        // A SENT row cannot be retried.
        long sentId = jdbcTemplate.queryForObject(
                "SELECT id FROM email_outbox WHERE claim_id = ? AND kind = 'FNOL'",
                Long.class, claimId);
        // FNOL rows are SENT by the controller flush; retry must be a 400.
        assertEquals("SENT", statusOfRow(sentId));
        assertEquals(400, post("/api/outbox/" + sentId + "/retry", supervisorBearer())
                .statusCode());
    }

    // --- backoff shape ---------------------------------------------------------------

    @Test
    void backoffIsExponentialOneMinuteToFourHours() {
        assertEquals(java.time.Duration.ofMinutes(1),
                EmailOutboxDispatcher.backoffForAttempt(1));
        assertEquals(java.time.Duration.ofMinutes(2),
                EmailOutboxDispatcher.backoffForAttempt(2));
        assertEquals(java.time.Duration.ofMinutes(4),
                EmailOutboxDispatcher.backoffForAttempt(3));
        assertEquals(java.time.Duration.ofHours(4),
                EmailOutboxDispatcher.backoffForAttempt(10));
        assertEquals(java.time.Duration.ofHours(4),
                EmailOutboxDispatcher.backoffForAttempt(99));
    }

    // --- auth matrix -------------------------------------------------------------------

    @Test
    void outboxEndpointsAreSupervisorOnly() throws Exception {
        assertEquals(401, getStatus("/api/outbox", null));
        assertEquals(401, postStatus("/api/outbox/1/retry", null));
        for (String bearer : new String[] {
                JwtTestConfig.tokenFor("sub-adj-ob", "adjuster_l1"),
                JwtTestConfig.tokenFor("sub-clm-ob", "claimant") }) {
            assertEquals(403, getStatus("/api/outbox", bearer));
            assertEquals(403, postStatus("/api/outbox/1/retry", bearer));
        }
        assertEquals(200, getStatus("/api/outbox", supervisorBearer()));
    }

    @Test
    void retryOfUnknownRowIs400() throws Exception {
        assertEquals(400, post("/api/outbox/999999/retry", supervisorBearer()).statusCode());
    }

    // --- helpers -------------------------------------------------------------------

    private String supervisorBearer() {
        return JwtTestConfig.tokenFor("sub-supervisor-outbox", "supervisor");
    }

    private String fileHomeFnol() throws Exception {
        HttpResponse<String> response = post("/api/claims", claimantBearer(), multipart(Map.of(
                "policyNumber", "POL-10001",
                "holderName", "Ada Lovelace",
                "holderEmail", "ada.lovelace@example.test",
                "lossDate", "2026-09-01",
                "lossLocation", "London",
                "lossDescription", "Kitchen flooded after a pipe burst.")));
        assertEquals(201, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"claimNumber\":\"([^\"]+)\".*", "$1");
    }

    private void decide(String claimNumber, String json) throws Exception {
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM claim WHERE claim_number = ?", Long.class, claimNumber);
        String body = withExpectedVersion(json, version);
        HttpResponse<String> response = postJson("/api/claims/" + claimNumber + "/decision",
                JwtTestConfig.tokenFor(SUB_L1_ONE, "adjuster_l1"), body);
        assertEquals(200, response.statusCode(), response.body());
    }

    /** V21 (V3 S5): splices expectedVersion into a decision JSON body under test. */
    private static String withExpectedVersion(String json, Long version) {
        return json.endsWith("}") ? json.substring(0, json.length() - 1)
                + ",\"expectedVersion\":" + version + "}" : json;
    }

    /** A sender pointed at an unroutable port: every send fails fast (SMTP-down). */
    private JavaMailSender brokenMailSender() {
        org.springframework.mail.javamail.JavaMailSenderImpl sender =
                new org.springframework.mail.javamail.JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(1);
        sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "1000");
        sender.getJavaMailProperties().put("mail.smtp.timeout", "1000");
        return sender;
    }

    /** A sender that always throws: poison-address delivery without network waits. */
    private JavaMailSender alwaysFailingSender() {
        return new JavaMailSender() {
            @Override
            public void send(SimpleMailMessage message) {
                throw new org.springframework.mail.MailSendException("poison address");
            }

            @Override
            public void send(SimpleMailMessage... messages) {
                throw new org.springframework.mail.MailSendException("poison address");
            }

            @Override
            public void send(org.springframework.mail.javamail.MimeMessagePreparator p) {
                throw new org.springframework.mail.MailSendException("poison address");
            }

            @Override
            public void send(org.springframework.mail.javamail.MimeMessagePreparator... ps) {
                throw new org.springframework.mail.MailSendException("poison address");
            }

            @Override
            public jakarta.mail.internet.MimeMessage createMimeMessage() {
                throw new UnsupportedOperationException();
            }

            @Override
            public jakarta.mail.internet.MimeMessage createMimeMessage(
                    java.io.InputStream contentStream) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void send(jakarta.mail.internet.MimeMessage message) {
                throw new org.springframework.mail.MailSendException("poison address");
            }

            @Override
            public void send(jakarta.mail.internet.MimeMessage... messages) {
                throw new org.springframework.mail.MailSendException("poison address");
            }
        };
    }

    private String statusOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM claim WHERE claim_number = ?", String.class, claimNumber);
    }

    private String statusOfRow(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM email_outbox WHERE id = ?", String.class, id);
    }

    private Long idOf(String claimNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM claim WHERE claim_number = ?", Long.class, claimNumber);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String mailpitUrl() {
        return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
    }

    private String mailpitFetch() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(mailpitUrl() + "/api/v1/messages"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private String claimantBearer() {
        return JwtTestConfig.tokenFor(CLAIMANT, "claimant");
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

    private HttpResponse<String> getResponse(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port() + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int getStatus(String path, String bearer) throws Exception {
        return getResponse(path, bearer).statusCode();
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

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }
}
