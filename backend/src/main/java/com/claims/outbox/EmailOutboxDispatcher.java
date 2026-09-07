package com.claims.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * R2 outbox dispatcher: delivers due PENDING rows through the configured SMTP sender,
 * recording SENT/FAILED + attempts + last_error. Backoff is exponential from 1 minute,
 * capped at 4 hours; after {@code max-attempts} (default 8) the row parks FAILED and an
 * error log carries the claim reference for the runbook triage query (see
 * {@code docs/operations.md}).
 *
 * <p>This bean is always in context (controllers flush it after commit); only the
 * scheduled trigger lives behind {@code claims.outbox.enabled} (see
 * {@link EmailOutboxScheduler}). Tests disable the scheduler via
 * {@code claims.outbox.enabled=false} and drive {@link #dispatch()} directly.
 *
 * <p>Delivery itself uses raw {@link JavaMailSender} with the stored subject/body verbatim,
 * so the wording stays identical to the pre-outbox senders. The existing sender classes keep
 * their signatures but are no longer on the live path (kept for compatibility).
 */
@Component
public class EmailOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxDispatcher.class);

    static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);
    static final Duration MAX_BACKOFF = Duration.ofHours(4);
    static final int DEFAULT_BATCH = 50;

    private final EmailOutboxRepository outbox;
    private final JavaMailSender mailSender;
    private final String from;
    private final int maxAttempts;
    private final int batch;

    public EmailOutboxDispatcher(EmailOutboxRepository outbox, JavaMailSender mailSender,
            @Value("${claims.mail.from}") String from,
            @Value("${claims.outbox.max-attempts:8}") int maxAttempts,
            @Value("${claims.outbox.batch:50}") int batch) {
        this.outbox = outbox;
        this.mailSender = mailSender;
        this.from = from;
        this.maxAttempts = maxAttempts;
        this.batch = batch;
    }

    /**
     * One delivery pass: claim due rows, send each, record the outcome. Each row's send +
     * status update is its own transaction, so one poison address never blocks the batch.
     * Called by the scheduler in production and directly by controllers (post-commit flush)
     * and tests.
     */
    public void dispatch() {
        List<Long> due;
        try {
            due = outbox.claimDue(Math.max(1, Math.min(batch, DEFAULT_BATCH * 4)));
        } catch (RuntimeException ex) {
            log.error("Email outbox dispatch: failed to claim due rows", ex);
            return;
        }
        for (long id : due) {
            deliverOne(id);
        }
    }

    @Transactional
    void deliverOne(long id) {
        EmailOutboxRepository.OutboxMail mail = outbox.loadMail(id);
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(mail.toAddress());
            message.setSubject(mail.subject());
            message.setText(mail.body());
            mailSender.send(message);
            outbox.markSent(id, Instant.now());
        } catch (RuntimeException ex) {
            boolean exhausted = mail.attempts() >= maxAttempts;
            Instant next = Instant.now().plus(backoffForAttempt(mail.attempts()));
            String error = errorOf(ex);
            outbox.markAttemptFailed(id, error, next, exhausted);
            if (exhausted) {
                // Operations triage reference: the runbook query selects FAILED rows; this
                // log line ties the failure to the claim for log-grep.
                log.error("Email outbox delivery FAILED for outbox id {} (claim id {}): {}",
                        id, mail.claimId(), error);
            } else {
                log.warn("Email outbox delivery attempt {} for outbox id {} failed; "
                        + "next attempt at {}", mail.attempts(), id, next);
            }
        }
    }

    /** Exponential backoff 1m → 4h by completed-attempt count (attempts already incremented). */
    static Duration backoffForAttempt(int attempts) {
        long minutes = 1L << Math.min(Math.max(0, attempts - 1), 8);
        Duration backoff = Duration.ofMinutes(minutes);
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    private static String errorOf(RuntimeException ex) {
        String message = ex.getMessage();
        String name = ex.getClass().getSimpleName();
        if (message == null || message.isBlank()) {
            return name;
        }
        String combined = name + ": " + message;
        return combined.length() > 2000 ? combined.substring(0, 2000) : combined;
    }
}
