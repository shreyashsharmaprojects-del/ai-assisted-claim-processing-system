-- V10 (R2 email outbox): every notification email is first written here in the same
-- transaction as the business change that triggers it (FNOL, assignment, closure) and only
-- then delivered by a scheduled dispatcher. A mail outage therefore delays mail but never
-- loses it and never rolls back a decision (best-effort ordering preserved).
--
-- Lifecycle: PENDING (due when next_attempt_at <= now) -> SENT, or after max attempts ->
-- FAILED (supervisor-visible, retryable back to PENDING). attempts counts deliveries tried.
CREATE TABLE email_outbox (
    id              BIGSERIAL PRIMARY KEY,
    claim_id        BIGINT       NOT NULL REFERENCES claim (id),
    kind            VARCHAR(20)  NOT NULL CHECK (kind IN ('FNOL', 'ASSIGNMENT', 'DECISION')),
    to_address      VARCHAR(200) NOT NULL,
    subject         VARCHAR(300) NOT NULL,
    body            TEXT         NOT NULL,
    status          VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);

-- Dispatcher read path: due PENDING rows first.
CREATE INDEX idx_email_outbox_status_next
    ON email_outbox (status, next_attempt_at);
