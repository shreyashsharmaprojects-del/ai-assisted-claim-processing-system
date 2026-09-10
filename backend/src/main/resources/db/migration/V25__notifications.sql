-- V25 (V3 S10: notifications beyond email).
--
-- notification_preference is keyed by the claimant subject (no FK to claim —
-- prefs belong to the person, not to one filing). notification rows are the
-- in-app center, written in the same business transaction as the outbox mail
-- they mirror; channel carries only INAPP this slice (SMS is a seam only).
--
-- S10 also extends the V10 outbox kind CHECK with the three new claimant mail
-- kinds (NEED_INFO, REFERRAL, REOPEN). No old file is edited: the old CHECK
-- constraint is dropped and re-added with the extended list in this file, so
-- Flyway checksums on V1–V24 stay valid and already-applied rows pass.
ALTER TABLE email_outbox DROP CONSTRAINT IF EXISTS email_outbox_kind_check;
ALTER TABLE email_outbox ADD CONSTRAINT email_outbox_kind_check
    CHECK (kind IN ('FNOL', 'ASSIGNMENT', 'DECISION', 'NEED_INFO', 'REFERRAL', 'REOPEN'));

CREATE TABLE notification_preference (
    claimant_sub VARCHAR(100) PRIMARY KEY,
    email_events BOOLEAN NOT NULL DEFAULT TRUE,
    inapp_events BOOLEAN NOT NULL DEFAULT TRUE,
    sms_events BOOLEAN NOT NULL DEFAULT FALSE,
    phone VARCHAR(20) NULL
);
CREATE TABLE notification (
    id BIGSERIAL PRIMARY KEY,
    claim_id BIGINT NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    claimant_sub VARCHAR(100) NOT NULL,
    channel VARCHAR(10) NOT NULL CHECK (channel IN ('INAPP')),
    event VARCHAR(40) NOT NULL,
    title VARCHAR(160) NOT NULL, body VARCHAR(2000) NOT NULL,
    read_at TIMESTAMPTZ NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_sub ON notification (claimant_sub, created_at DESC);
