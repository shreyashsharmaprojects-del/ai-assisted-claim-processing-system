-- Demo reset: deletes every row demo-seed.sql planted. DEV `claims` ONLY.
-- Demo `audit_log` rows are INTENTIONALLY left behind — the log is append-only
-- (V7 trigger rejects DELETE); they read as history, keyed by claimNumber in
-- the `after` payload. No photos are ever seeded, so the volume needs no cleanup.

DO $$ BEGIN
  IF current_database() <> 'claims' THEN
    RAISE EXCEPTION 'demo reset refuses database "%" (dev claims only)', current_database();
  END IF;
END $$;

BEGIN;

DELETE FROM email_outbox WHERE claim_id IN (SELECT id FROM claim WHERE claimant_sub LIKE 'demo-%');
DELETE FROM verification WHERE claim_id IN (SELECT id FROM claim WHERE claimant_sub LIKE 'demo-%');
DELETE FROM claim_cover WHERE claim_id IN (SELECT id FROM claim WHERE claimant_sub LIKE 'demo-%');
DELETE FROM payment WHERE claim_id IN (SELECT id FROM claim WHERE claimant_sub LIKE 'demo-%');
DELETE FROM internal_note WHERE claim_id IN (SELECT id FROM claim WHERE claimant_sub LIKE 'demo-%');
DELETE FROM fnol_submission WHERE claimant_sub LIKE 'demo-%';
DELETE FROM attachment WHERE claim_id IN (SELECT id FROM claim WHERE claimant_sub LIKE 'demo-%');
DELETE FROM claim WHERE claimant_sub LIKE 'demo-%';
DELETE FROM policy WHERE policy_number LIKE 'POL-DEMO-%';

COMMIT;
