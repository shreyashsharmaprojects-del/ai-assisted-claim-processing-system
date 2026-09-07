-- V8 (production hardening): FNOL rate limiting, creator audit attribution,
-- supervisor queue performance, and wall-clock defaults.
--
-- fnol_submission: one row per filed claim, written in the FNOL transaction. It is the
-- rate-limit ledger (count recent rows per claimant) and the durable record of which
-- claimant subject filed from which IP. A policy-carrier-wide UNIQUE cap is NOT imposed
-- here (the scale is hundreds of claims/year); the limit is a per-claimant rolling window
-- enforced in ClaimService, so legitimate filing is never blocked. Housekeeping rows for a
-- TEST claimant may be deleted by integration tests; production rows are append-only like
-- the audit log (the V7 trigger pattern is intentionally not repeated here — tests need
-- to reset the ledger between runs without truncating claim tables).
CREATE TABLE fnol_submission (
    id           BIGSERIAL PRIMARY KEY,
    claimant_sub VARCHAR(100) NOT NULL,
    claim_id     BIGINT       NOT NULL REFERENCES claim (id),
    ip_address   VARCHAR(45),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_fnol_submission_claimant_created
    ON fnol_submission (claimant_sub, created_at DESC);

-- Supervisor team-queue read path: teamQueue()/supervisorEscalationQueue() filter open
-- claims by status and order by created_at, id. The pre-existing idx_claim_status covers
-- the filter; this composite covers the (status, created_at, id) scan+sort in one index.
CREATE INDEX idx_claim_status_created_id
    ON claim (status, created_at, id);

-- Adjuster "own queue": the per-adjuster open-claim filter+sort.
CREATE INDEX idx_claim_assignee_status_created
    ON claim (assigned_adjuster_id, status, created_at, id);

-- Timezone honesty: created_at/assigned_at/closed_at had no documented zone on fresh
-- rows. Postgres TIMESTAMPTZ already stores UTC; this migration only backfills rows that
-- predate the change (none in practice) and documents the contract for operators.
-- No DDL change: TIMESTAMPTZ is already the column type. See docs/operations.md.
