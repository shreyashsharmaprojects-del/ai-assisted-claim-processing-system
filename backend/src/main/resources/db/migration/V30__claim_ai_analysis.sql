-- V30 (V4 S2: AI advisory snapshots).
--
-- Advisory snapshots, not claim state: one row per claim version, recording
-- what the adjuster saw when they asked for AI help at DECISION. The claim
-- snapshot + clause ids + model output are frozen so a later dispute can
-- replay exactly what the panel showed — even after the claim moves on.
--
-- Dedupe is (claim_id, claim_version): requesting analysis twice for the same
-- version returns the stored row, never a second LLM call. A new version
-- (re-assessment, send-back return) earns a fresh row. claim_version mirrors
-- claim.version at request time.
--
-- JSONB columns (clause_ids, claim_snapshot, output_json) are schemaless on
-- purpose: the prompt contract evolves without migrations. The Java layer
-- validates the model output before persisting (unknown clause ids and
-- non-covered suggestions are rejected, never stored).
--
-- Provider outcome is recorded, not hidden: status is COMPLETED, DEGRADED
-- (rules-only fallback when the provider is unreachable — the panel says so),
-- or FAILED (neither rules nor provider produced output). error_message carries
-- the provider failure for ops; never claimant-visible.
--
-- Claimant-invisible by design: no endpoint serves these rows to claimants
-- (assignee/supervisor only, 404-not-403). No FK to claim (retention-deleted
-- claims must not drag snapshots; orphan rows are harmless reference).
--
-- All additive; V1–V29 untouched.

CREATE TABLE claim_ai_analysis (
    id                  BIGSERIAL PRIMARY KEY,
    claim_id            BIGINT NOT NULL,
    claim_version       BIGINT NOT NULL,
    status              VARCHAR(20) NOT NULL CHECK (status IN
                            ('COMPLETED', 'DEGRADED', 'FAILED')),
    model               VARCHAR(100) NOT NULL,
    clause_ids          JSONB NOT NULL DEFAULT '[]'::jsonb,
    claim_snapshot      JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_json         JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message       TEXT NULL,
    requested_by        VARCHAR(200) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (claim_id, claim_version)
);

CREATE INDEX idx_claim_ai_analysis_claim
    ON claim_ai_analysis (claim_id);
