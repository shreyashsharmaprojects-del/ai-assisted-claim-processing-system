-- V24 (V3 S9: GDPR + retention story).
--
-- privacy_request records handled subject-rights requests: handled PII subject,
-- kind (EXPORT or ERASURE), status (COMPLETED only — a request row documents a
-- completed handling, never a pending workflow), plus who handled it and when.
-- No PII columns are added anywhere by this slice. Retention is policy +
-- report (see PrivacyService), not auto-delete.
CREATE TABLE privacy_request (
    id BIGSERIAL PRIMARY KEY,
    claimant_sub VARCHAR(100) NOT NULL,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('EXPORT','ERASURE')),
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED'
        CHECK (status IN ('COMPLETED')),
    handled_by VARCHAR(100) NOT NULL,
    handled_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_privacy_request_sub ON privacy_request (claimant_sub);
