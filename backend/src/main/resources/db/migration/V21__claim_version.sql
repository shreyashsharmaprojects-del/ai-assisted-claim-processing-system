-- V21 (V3 S5: optimistic concurrency — no more silent overwrites).
--
-- All additive; V1–V20 untouched.
--
-- claim.version: JPA @Version counter for compare-and-swap on the six money-path
-- writers (reserve, assessment, cover-decision, legacy decision,
-- escalation-decision, escalation-cover-decision). DEFAULT 0 backfills existing rows.
ALTER TABLE claim ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
