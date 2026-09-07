package com.claims.outbox;

import java.time.OffsetDateTime;

/**
 * One email_outbox row for the supervisor admin view: newest first, status-filterable.
 * The body is deliberately excluded (bulk + claimant PII in a list view); the subject
 * identifies the mail. {@code claimNumber} is the human reference for triage.
 */
public record OutboxRowView(long id, long claimId, String claimNumber, String kind,
        String toAddress, String subject, String status, int attempts, String lastError,
        OffsetDateTime nextAttemptAt, OffsetDateTime createdAt, OffsetDateTime sentAt) {
}
