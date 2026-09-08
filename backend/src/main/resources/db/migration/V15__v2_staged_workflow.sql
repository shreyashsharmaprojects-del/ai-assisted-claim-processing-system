-- V15 (V2-4/V2-5/V2-6 backend: staged adjuster workflow).
--
-- All additive; V1–V14 untouched. Existing rows default to stage REVIEW (new
-- workflow position for open claims; harmless on CLOSED rows, which never gate on
-- stage). claim.decision gains PARTIALLY_APPROVED by value only (VARCHAR, no CHECK
-- on that column — nothing to alter). The verification table is per plan §Data
-- model; assessment/financial columns on claim_cover already exist from V14.

-- --- claim stage + NEED_INFO fields --------------------------------------------
ALTER TABLE claim
    ADD COLUMN stage VARCHAR(20) NOT NULL DEFAULT 'REVIEW',
    ADD COLUMN need_info_reason TEXT NULL,
    ADD COLUMN need_info_prior_stage VARCHAR(20) NULL;

ALTER TABLE claim
    ADD CONSTRAINT chk_claim_stage CHECK (stage IN ('REVIEW', 'VERIFICATION', 'DECISION')),
    ADD CONSTRAINT chk_claim_need_info_prior_stage CHECK (
        need_info_prior_stage IS NULL
        OR need_info_prior_stage IN ('REVIEW', 'VERIFICATION'));

-- --- verification (first-class stage record; history preserved) ------------------
CREATE TABLE verification (
    id            BIGSERIAL PRIMARY KEY,
    claim_id      BIGINT NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    type          VARCHAR(20) NULL
                  CHECK (type IS NULL OR type IN ('DIGITAL', 'PHYSICAL')),
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETE', 'CANCELLED')),
    outcome       VARCHAR(20) NULL
                  CHECK (outcome IS NULL OR outcome IN ('PASSED', 'FAILED', 'WAIVED', 'INCONCLUSIVE')),
    notes         TEXT NULL,
    evidence_refs TEXT NULL,
    performed_by  VARCHAR(100) NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ NULL
);

-- claim.level was VARCHAR(2) CHECK (level IN ('L1','L2')) (V3): the V2-1 L3 rung
-- needs the same widening V12 applied to app_user.level (V12 only widened the
-- staff table, so L3-routed claims could never persist until now).
ALTER TABLE claim DROP CONSTRAINT IF EXISTS claim_level_check;
ALTER TABLE claim ADD CONSTRAINT claim_level_check CHECK (level IN ('L1', 'L2', 'L3'));

-- claim.decision was VARCHAR(10) (V6: APPROVED | DENIED). PARTIALLY_APPROVED
-- (18 chars) needs a wider column: widen in place (no data rewrite beyond the
-- type change; existing APPROVED/DENIED values are unaffected).
ALTER TABLE claim ALTER COLUMN decision TYPE VARCHAR(20);

CREATE INDEX idx_verification_claim_id ON verification (claim_id);
