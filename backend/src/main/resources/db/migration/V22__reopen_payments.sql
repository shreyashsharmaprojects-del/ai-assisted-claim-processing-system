-- V22 (V3 S6: claim reopen / appeal — payment history per closure).
--
-- payment today is UNIQUE(claim_id) (one payment per claim). A re-decision after
-- a supervisor reopen must keep row 1 and add row 2, so the uniqueness moves to
-- (claim_id, seq). DEFAULT 1 backfills existing rows. V1–V21 untouched.
ALTER TABLE payment ADD COLUMN seq INT NOT NULL DEFAULT 1;
ALTER TABLE payment DROP CONSTRAINT IF EXISTS payment_claim_id_key;
ALTER TABLE payment ADD CONSTRAINT payment_claim_seq_unique UNIQUE (claim_id, seq);
-- claim needs no new status: reopened claims re-enter UNDER_REVIEW[REVIEW].
