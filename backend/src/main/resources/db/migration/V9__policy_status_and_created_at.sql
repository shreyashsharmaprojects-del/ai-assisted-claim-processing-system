-- V9 (R1 policy admin): policies become supervisor-managed instead of seed-only.
--
-- policy.status: ACTIVE (default, fileable) or RETIRED (kept readable for history, rejected
-- at FNOL with the existing policy-mismatch shape). Rows are never deleted — claims
-- reference them. policy.created_at anchors the admin list's newest-first order.
-- The policy_number UNIQUE constraint is untouched (duplicate guard stays at the DB level).
ALTER TABLE policy
    ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE policy
    ADD CONSTRAINT chk_policy_status CHECK (status IN ('ACTIVE', 'RETIRED'));
