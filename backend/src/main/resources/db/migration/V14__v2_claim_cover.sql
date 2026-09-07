-- V14 (V2-2: multi-cover FNOL + tracker).
--
-- All additive; V1–V13 untouched. One row per filed cover (plan §Data model):
-- FILED state is claimed_amount + decision PENDING; assessed/approved/net_payable
-- arrive with V2-5 (assessment + financial model) and stay NULL until then.
--
-- Locked rules applied: sub-limits constrain assessment/approval, never filing —
-- hence NO check of claimed_amount against any sub-limit here; above-limit filings
-- are accepted and the flag is derived (claimed > sub_limit) at read time.
-- Remaining limits stay derived queries (CockpitService), never stored counters.

CREATE TABLE claim_cover (
    id                BIGSERIAL PRIMARY KEY,
    claim_id          BIGINT NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    cover_code        VARCHAR(40) NOT NULL,
    claimed_amount    NUMERIC(14,2) NOT NULL CHECK (claimed_amount > 0),
    assessed_amount   NUMERIC(14,2) NULL CHECK (assessed_amount IS NULL OR assessed_amount >= 0),
    approved_amount   NUMERIC(14,2) NULL CHECK (approved_amount IS NULL OR approved_amount >= 0),
    deductible_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (deductible_amount >= 0),
    adjustment_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    net_payable       NUMERIC(14,2) NULL CHECK (net_payable IS NULL OR net_payable >= 0),
    decision          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                      CHECK (decision IN ('PENDING', 'APPROVED', 'REJECTED')),
    decision_remarks  TEXT NULL,
    is_proposal       BOOLEAN NOT NULL DEFAULT FALSE,
    decided_by        BIGINT NULL REFERENCES app_user (id),
    decided_at        TIMESTAMPTZ NULL,
    UNIQUE (claim_id, cover_code)
);

CREATE INDEX idx_claim_cover_claim_id ON claim_cover (claim_id);

-- Server-computed sum of the filed cover amounts (never client-trusted). NULL on
-- pre-V2-2 rows (single-figure V1 claims predate cover splits).
ALTER TABLE claim ADD COLUMN claimed_total NUMERIC(14,2) NULL
    CHECK (claimed_total IS NULL OR claimed_total > 0);

-- Duplicate-FNOL guard (plan V2-2 risk pin): same policy + loss date + cover set
-- within 24h. The cover-set comparison lives in the service (claim_cover rows);
-- this index keeps the candidate lookup cheap.
CREATE INDEX idx_claim_policy_loss_created ON claim (policy_id, loss_date, created_at);
