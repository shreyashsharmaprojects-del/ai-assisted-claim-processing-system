-- Demo seed for the sales demo. DEV `claims` DATABASE ONLY — never claims_e2e
-- (both npm scripts hardcode `-d claims`, and this file refuses any other DB).
--
-- Marker convention (what demo:reset deletes): policy_number LIKE 'POL-DEMO-%',
-- claimant_sub LIKE 'demo-%'. Holder names/emails are fictitious (@example.test).
-- Rungs covered: UNASSIGNED → UNDER_REVIEW (L1 with reserve, L2 with note) →
-- ESCALATED_SUPERVISOR → CLOSED (APPROVED with payment, DENIED with remarks) →
-- NEED_INFO (sent back to claimant, pending their response) →
-- V2 staged: REVIEW multi-cover triage claim, VERIFICATION claim with history,
-- DECISION claim with saved above-authority proposals, CLOSED PARTIALLY_APPROVED
-- claim with net payables (the payment + outbox dispatcher story).
-- Photo-free on purpose: no disk files, so reset never orphans the volume.
-- Audit rows ARE seeded (the supervisor audit view reads them) and are left
-- behind by demo:reset — audit_log is append-only (V7 trigger rejects DELETE).
-- Idempotent: prior demo rows are cleared first (FK order; audit stays, see above).

DO $$ BEGIN
  IF current_database() <> 'claims' THEN
    RAISE EXCEPTION 'demo seed refuses database "%" (dev claims only)', current_database();
  END IF;
END $$;

BEGIN;

-- 0. Clear prior demo rows (FK order). Audit rows stay: append-only by design.
-- Also clears throwaway 'Shot demo:%' claims filed live by shots.spec.ts (their
-- Keycloak subs are random UUIDs, so the description prefix is the marker).
DELETE FROM email_outbox WHERE claim_id IN (SELECT id FROM claim WHERE claimant_sub LIKE 'demo-%' OR loss_description LIKE 'Shot demo:%');
DELETE FROM verification WHERE claim_id IN (SELECT id FROM claim WHERE claimant_sub LIKE 'demo-%' OR loss_description LIKE 'Shot demo:%');
DELETE FROM claim_cover WHERE claim_id IN (SELECT id FROM claim WHERE claimant_sub LIKE 'demo-%' OR loss_description LIKE 'Shot demo:%');
DELETE FROM payment WHERE claim_id IN (SELECT id FROM claim WHERE claimant_sub LIKE 'demo-%' OR loss_description LIKE 'Shot demo:%');
DELETE FROM internal_note WHERE claim_id IN (SELECT id FROM claim WHERE claimant_sub LIKE 'demo-%' OR loss_description LIKE 'Shot demo:%');
DELETE FROM fnol_submission WHERE claimant_sub LIKE 'demo-%' OR claim_id IN (SELECT id FROM claim WHERE loss_description LIKE 'Shot demo:%');
DELETE FROM attachment WHERE claim_id IN (SELECT id FROM claim WHERE claimant_sub LIKE 'demo-%' OR loss_description LIKE 'Shot demo:%');
DELETE FROM claim WHERE claimant_sub LIKE 'demo-%' OR loss_description LIKE 'Shot demo:%';
DELETE FROM policy_cover WHERE policy_id IN (SELECT id FROM policy WHERE policy_number LIKE 'POL-DEMO-%');
DELETE FROM policy WHERE policy_number LIKE 'POL-DEMO-%';

-- 1. The book: 8 policies. V1 HOME/AUTO rows route as before; the two HLTH-PLUS
-- rows carry opted covers so the multi-cover FNOL path is fileable as-is.
INSERT INTO policy (policy_number, product_code, holder_name, holder_email, coverage) VALUES
  ('POL-DEMO-01', 'HOME', 'Eleanor Vance',  'demo.eleanor@example.test',  '{"type":"home","sum_insured":450000}'::jsonb),
  ('POL-DEMO-02', 'HOME', 'Theodore Marsh', 'demo.theodore@example.test', '{"type":"home","sum_insured":620000}'::jsonb),
  ('POL-DEMO-03', 'AUTO', 'Priya Nair',     'demo.priya@example.test',   '{"type":"auto","sum_insured":28000}'::jsonb),
  ('POL-DEMO-04', 'HOME', 'Samuel Okafor',  'demo.samuel@example.test',  '{"type":"home","sum_insured":510000}'::jsonb),
  ('POL-DEMO-05', 'AUTO', 'Ingrid Halvors', 'demo.ingrid@example.test',  '{"type":"auto","sum_insured":35000}'::jsonb),
  ('POL-DEMO-06', 'HOME', 'Tomas Reyes',    'demo.tomas@example.test',   '{"type":"home","sum_insured":380000}'::jsonb);

-- V2 products backing the staged showcase rows below: one HLTH-PLUS family policy
-- (10L sum insured, 5-cover set) and one HLTH-CRIT policy (25L, lump sum + hospital).
INSERT INTO policy (policy_number, product_code, holder_name, holder_email, coverage,
                    status, sum_insured, valid_from, valid_to) VALUES
  ('POL-DEMO-07', 'HLTH-PLUS', 'Anaya Desai', 'demo.anaya@example.test',
   '{"type":"health","plan":"plus-family"}', 'ACTIVE', 1000000.00, '2026-01-01', '2026-12-31'),
  ('POL-DEMO-08', 'HLTH-CRIT', 'Kabir Rao', 'demo.kabir@example.test',
   '{"type":"health","plan":"critical-illness"}', 'ACTIVE', 2500000.00, '2026-01-01', '2026-12-31');

-- Opted covers for the V2 showcase policies (sub-limits/deductibles mirror V13).
INSERT INTO policy_cover (policy_id, cover_code, display_name, sub_limit, deductible_default, sort_order)
SELECT p.id, c.cover_code, c.display_name, c.sub_limit, c.deductible_default, c.sort_order
FROM policy p
JOIN (VALUES
    ('POL-DEMO-07', 'HOSPITALIZATION', 'In-patient Hospitalization', 500000.00, 10000.00, 1),
    ('POL-DEMO-07', 'ROOM_RENT',       'Room Rent',                  100000.00,  5000.00, 2),
    ('POL-DEMO-07', 'DAYCARE',         'Daycare Procedures',         100000.00,  5000.00, 3),
    ('POL-DEMO-07', 'OPD',             'Out-patient (OPD)',           30000.00,  2000.00, 4),
    ('POL-DEMO-07', 'MATERNITY',       'Maternity',                   75000.00, 10000.00, 5),
    ('POL-DEMO-08', 'CRITICAL_ILLNESS','Critical Illness (lump sum)',2500000.00,     0.00, 1),
    ('POL-DEMO-08', 'HOSPITALIZATION', 'In-patient Hospitalization', 500000.00, 10000.00, 2)
) AS c(policy_number, cover_code, display_name, sub_limit, deductible_default, sort_order)
  ON c.policy_number = p.policy_number;

-- 2. Claims at each ladder rung. Numbers come from claim_number_seq (kept in sync);
-- created_at is staggered so the demo shows aging pressure on the overview.
-- NOTE: every dependent CTE below reads the `seeded` RETURNING set, never the
-- claim table — same-statement CTEs share a snapshot and cannot see each other's
-- writes, so all needed columns ride the RETURNING list.
WITH seeded AS (
  INSERT INTO claim (claim_number, policy_id, claimant_sub, level, status,
      loss_date, loss_location, loss_description, claimant_remarks,
      created_at, assigned_adjuster_id, assigned_at,
      reserve_amount, decision, decision_remarks, indemnity_amount, closed_at,
      claimed_total, stage, need_info_reason, need_info_prior_stage)
  VALUES
    -- Fresh filing, not yet picked up.
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-01'),
     'demo-claimant-01', 'L1', 'UNASSIGNED',
     CURRENT_DATE - 1, 'Bristol', 'Burst pipe flooded the kitchen overnight.',
     'Prefer email contact.', CURRENT_TIMESTAMP - INTERVAL '2 hours',
     NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'REVIEW', NULL, NULL),
    -- With an L1 adjuster, reserve set.
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-02'),
     'demo-claimant-02', 'L1', 'UNDER_REVIEW',
     CURRENT_DATE - 3, 'Leeds', 'Storm tore ridge tiles; rain ingress in two rooms.',
     NULL, CURRENT_TIMESTAMP - INTERVAL '2 days',
     (SELECT id FROM app_user WHERE keycloak_sub = '10000000-0000-0000-0000-000000000001'),
     CURRENT_TIMESTAMP - INTERVAL '2 days',
     1800.00, NULL, NULL, NULL, NULL, NULL, 'REVIEW', NULL, NULL),
    -- With the L2 adjuster, internal note on file.
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-03'),
     'demo-claimant-03', 'L2', 'UNDER_REVIEW',
     CURRENT_DATE - 4, 'Manchester', 'Two-car collision at a roundabout; front wing damage.',
     NULL, CURRENT_TIMESTAMP - INTERVAL '3 days',
     (SELECT id FROM app_user WHERE keycloak_sub = '10000000-0000-0000-0000-000000000003'),
     CURRENT_TIMESTAMP - INTERVAL '3 days',
     4200.00, NULL, NULL, NULL, NULL, NULL, 'REVIEW', NULL, NULL),
    -- Above L2 authority: waiting on the supervisor (assignee always null here).
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-04'),
     'demo-claimant-04', 'L2', 'ESCALATED_SUPERVISOR',
     CURRENT_DATE - 6, 'Bath', 'Fire in the garage spread to the roof structure.',
     NULL, CURRENT_TIMESTAMP - INTERVAL '6 days',
     NULL, NULL, 9500.00, NULL, NULL, NULL, NULL, NULL, 'DECISION', NULL, NULL),
    -- Closed: supervisor approved (payment recorded, authorizer NULL = supervisor).
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-05'),
     'demo-claimant-05', 'L2', 'CLOSED',
     CURRENT_DATE - 12, 'York', 'Hail damage across bonnet and roof; assessor confirmed.',
     NULL, CURRENT_TIMESTAMP - INTERVAL '12 days',
     NULL, NULL, 7900.00, 'APPROVED', NULL, 8200.00,
     CURRENT_TIMESTAMP - INTERVAL '1 day', NULL, 'DECISION', NULL, NULL),
    -- Closed: adjuster denied (rationale is the claimant-visible remarks).
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-06'),
     'demo-claimant-06', 'L1', 'CLOSED',
     CURRENT_DATE - 9, 'Exeter', 'Shed roof collapsed under snow load.',
     NULL, CURRENT_TIMESTAMP - INTERVAL '9 days',
     (SELECT id FROM app_user WHERE keycloak_sub = '10000000-0000-0000-0000-000000000002'),
     CURRENT_TIMESTAMP - INTERVAL '9 days',
     900.00, 'DENIED', 'The policy excludes outbuildings from storm cover.', NULL,
     CURRENT_TIMESTAMP - INTERVAL '2 days', NULL, 'REVIEW', NULL, NULL),
    -- V2-1: multi-cover REVIEW triage with L1 Priya (HOSP 300k filed + DAYCARE 40k,
    -- DAYCARE assessed within its flag-free range; decision still pending).
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-07'),
     'demo-claimant-07', 'L1', 'UNDER_REVIEW',
     CURRENT_DATE - 2, 'Pune', 'Gallbladder surgery with two daycare follow-ups.',
     NULL, CURRENT_TIMESTAMP - INTERVAL '1 day',
     (SELECT id FROM app_user WHERE keycloak_sub = '10000000-0000-0000-0000-000000000001'),
     CURRENT_TIMESTAMP - INTERVAL '1 day',
     240000.00, NULL, NULL, NULL, NULL, 340000.00, 'REVIEW', NULL, NULL),
    -- V2-2: VERIFICATION claim with L1 Aisha (DIGITAL record complete, outcome on
    -- file; assessment still to come).
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-07'),
     'demo-claimant-08', 'L1', 'UNDER_REVIEW',
     CURRENT_DATE - 5, 'Jaipur', 'Knee arthroscopy; discharge summary attached.',
     NULL, CURRENT_TIMESTAMP - INTERVAL '4 days',
     (SELECT id FROM app_user WHERE keycloak_sub = '10000000-0000-0000-0000-000000000005'),
     CURRENT_TIMESTAMP - INTERVAL '4 days',
     120000.00, NULL, NULL, NULL, NULL, 150000.00, 'VERIFICATION', NULL, NULL),
    -- V2-3: DECISION claim with above-authority proposals saved (L2 Rahul proposed
    -- 900k approved against his 400k HLTH-PLUS limit — referred to the L3 queue).
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-07'),
     'demo-claimant-09', 'L3', 'UNDER_REVIEW',
     CURRENT_DATE - 7, 'Kochi', 'Liver transplant package; staged billing.',
     NULL, CURRENT_TIMESTAMP - INTERVAL '6 days',
     (SELECT id FROM app_user WHERE keycloak_sub = '10000000-0000-0000-0000-000000000007'),
     CURRENT_TIMESTAMP - INTERVAL '1 day',
     850000.00, NULL, NULL, NULL, NULL, 950000.00, 'DECISION', NULL, NULL),
    -- V2-4: CLOSED PARTIALLY_APPROVED on HLTH-CRIT (lump sum approved, hospital
    -- rejected; net payables recorded, single payment issued).
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-08'),
     'demo-claimant-10', 'L2', 'CLOSED',
     CURRENT_DATE - 15, 'Delhi', 'Bypass surgery with unrelated cosmetic add-on.',
     NULL, CURRENT_TIMESTAMP - INTERVAL '15 days',
     (SELECT id FROM app_user WHERE keycloak_sub = '10000000-0000-0000-0000-000000000006'),
     CURRENT_TIMESTAMP - INTERVAL '15 days',
     900000.00, 'PARTIALLY_APPROVED', 'Cosmetic add-on excluded; lump sum approved.', 900000.00,
     CURRENT_TIMESTAMP - INTERVAL '3 days', 1400000.00, 'DECISION', NULL, NULL),
    -- NEED_INFO: sent back to the claimant from REVIEW (assignee cleared, prior
    -- stage + requested items kept). L1 Priya asked for the itemised final bill;
    -- the claim waits on the claimant, not on an adjuster.
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-07'),
     'demo-claimant-11', 'L1', 'NEED_INFO',
     CURRENT_DATE - 3, 'Nagpur', 'Cataract surgery; insurer asked for the itemised bill.',
     NULL, CURRENT_TIMESTAMP - INTERVAL '2 days',
     NULL, NULL,
     NULL, NULL, NULL, NULL, NULL, 190000.00, 'REVIEW',
     'Please upload the itemised final bill and the discharge summary.', 'REVIEW')
  RETURNING id, claim_number, claimant_sub, status, level,
            assigned_adjuster_id, decision, indemnity_amount
),
note AS (
  INSERT INTO internal_note (claim_id, author_id, body)
  SELECT s.id,
         (SELECT id FROM app_user WHERE keycloak_sub = '10000000-0000-0000-0000-000000000003'),
         'Demo: repair estimate 4200 received; coverage confirmed, awaiting decision.'
  FROM seeded s WHERE s.claimant_sub = 'demo-claimant-03'
),
-- Filed covers for the four V2 showcase claims (decision state per comment above).
covers AS (
  INSERT INTO claim_cover (claim_id, cover_code, claimed_amount, assessed_amount,
      approved_amount, deductible_amount, adjustment_amount, net_payable,
      decision, decision_remarks, is_proposal, decided_by, decided_at)
  SELECT s.id, c.cover_code, c.claimed, c.assessed, c.approved, c.deductible,
         c.adjustment, c.net, c.decision::VARCHAR(20), c.remarks, c.proposal,
         (SELECT id FROM app_user WHERE keycloak_sub = c.decider), c.decided_at
  FROM seeded s
  JOIN (VALUES
    -- demo-07: filed only (PENDING, nothing assessed yet).
    ('demo-claimant-07', 'HOSPITALIZATION', 300000.00, NULL, NULL, 0, 0, NULL,
     'PENDING', NULL, FALSE, NULL, NULL),
    ('demo-claimant-07', 'DAYCARE', 40000.00, NULL, NULL, 0, 0, NULL,
     'PENDING', NULL, FALSE, NULL, NULL),
    -- demo-08: filed only (PENDING; verification happens off-cover).
    ('demo-claimant-08', 'HOSPITALIZATION', 120000.00, NULL, NULL, 0, 0, NULL,
     'PENDING', NULL, FALSE, NULL, NULL),
    ('demo-claimant-08', 'DAYCARE', 30000.00, NULL, NULL, 0, 0, NULL,
     'PENDING', NULL, FALSE, NULL, NULL),
    -- demo-09: assessed + approved as ABOVE-AUTHORITY proposals by L2 Rahul
    -- (900k approved vs 400k HLTH-PLUS L2 limit; 10k deductible on HOSP).
    ('demo-claimant-09', 'HOSPITALIZATION', 480000.00, 460000.00, 450000.00, 10000.00, 0, 440000.00,
     'PENDING', NULL, TRUE, '10000000-0000-0000-0000-000000000006',
     CURRENT_TIMESTAMP - INTERVAL '1 day'),
    ('demo-claimant-09', 'MATERNITY', 470000.00, 460000.00, 450000.00, 0, 0, 450000.00,
     'PENDING', NULL, TRUE, '10000000-0000-0000-0000-000000000006',
     CURRENT_TIMESTAMP - INTERVAL '1 day'),
    -- demo-10: finalized mixed outcome (proposals cleared, L2 Rahul decided).
    ('demo-claimant-10', 'CRITICAL_ILLNESS', 900000.00, 900000.00, 900000.00, 0, 0, 900000.00,
     'APPROVED', NULL, FALSE, '10000000-0000-0000-0000-000000000006',
     CURRENT_TIMESTAMP - INTERVAL '3 days'),
    ('demo-claimant-10', 'HOSPITALIZATION', 500000.00, 500000.00, NULL, 0, 0, NULL,
     'REJECTED', 'Cosmetic add-on excluded from hospital cover.', FALSE,
     '10000000-0000-0000-0000-000000000006', CURRENT_TIMESTAMP - INTERVAL '3 days'),
    -- demo-11: filed only (PENDING; parked in NEED_INFO before assessment).
    ('demo-claimant-11', 'HOSPITALIZATION', 150000.00, NULL, NULL, 0, 0, NULL,
     'PENDING', NULL, FALSE, NULL, NULL),
    ('demo-claimant-11', 'OPD', 40000.00, NULL, NULL, 0, 0, NULL,
     'PENDING', NULL, FALSE, NULL, NULL)
  ) AS c(sub, cover_code, claimed, assessed, approved, deductible, adjustment, net,
         decision, remarks, proposal, decider, decided_at)
    ON c.sub = s.claimant_sub
),
-- Verification history: PENDING seed rows (as ADVANCE opens them) plus the
-- COMPLETE DIGITAL record on the VERIFICATION claim and the referred DECISION one.
verifs AS (
  INSERT INTO verification (claim_id, type, status, outcome, notes, evidence_refs,
      performed_by, started_at, completed_at)
  SELECT s.id, v.type, v.status::VARCHAR(20), v.outcome::VARCHAR(20), v.notes, v.evidence,
         v.performer, v.started, v.completed
  FROM seeded s
  JOIN (VALUES
    ('demo-claimant-08', NULL, 'PENDING', NULL,
     NULL, NULL, '10000000-0000-0000-0000-000000000005',
     CURRENT_TIMESTAMP - INTERVAL '4 days', NULL),
    ('demo-claimant-08', 'DIGITAL', 'COMPLETE', 'PASSED',
     'Demo: discharge summary verified against hospital records.', 'attachment-1',
     '10000000-0000-0000-0000-000000000005',
     CURRENT_TIMESTAMP - INTERVAL '3 days', CURRENT_TIMESTAMP - INTERVAL '3 days'),
    ('demo-claimant-09', 'PHYSICAL', 'COMPLETE', 'PASSED',
     'Demo: site visit confirmed staged billing schedule.', 'site-report-7',
     '10000000-0000-0000-0000-000000000006',
     CURRENT_TIMESTAMP - INTERVAL '5 days', CURRENT_TIMESTAMP - INTERVAL '4 days')
  ) AS v(sub, type, status, outcome, notes, evidence, performer, started, completed)
    ON v.sub = s.claimant_sub
),
pay AS (
  INSERT INTO payment (claim_id, amount, authorized_by_id)
  SELECT s.id, 8200.00, NULL FROM seeded s WHERE s.claimant_sub = 'demo-claimant-05'
),
-- Single payment for the partially-approved claim (net payable total, per the
-- single-payment invariant; authorizer = deciding L2).
pay2 AS (
  INSERT INTO payment (claim_id, amount, authorized_by_id)
  SELECT s.id, 900000.00,
         (SELECT id FROM app_user WHERE keycloak_sub = '10000000-0000-0000-0000-000000000006')
  FROM seeded s WHERE s.claimant_sub = 'demo-claimant-10'
),
-- Outbox rows mirroring production shapes: FNOL + ASSIGNMENT for every demo
-- claim (SENT), DECISION for the two closed ones (SENT: approved + denied), a
-- PENDING DECISION row on the referred claim (rebroadcast after senior sign-off),
-- and one FAILED row to show the retry surface.
mbox AS (
  INSERT INTO email_outbox (claim_id, kind, to_address, subject, body, status,
      attempts, next_attempt_at, sent_at, last_error)
  SELECT s.id, m.kind::VARCHAR(20), m.to_addr, m.subject, m.body, m.status::VARCHAR(10),
         m.attempts, m.next_at, m.sent, m.err
  FROM seeded s
  JOIN (VALUES
    ('demo-claimant-01', 'FNOL', 'demo.eleanor@example.test',
     'Claim filed', 'Demo: FNOL received.', 'SENT', 1,
     CURRENT_TIMESTAMP - INTERVAL '2 hours', CURRENT_TIMESTAMP - INTERVAL '2 hours', NULL),
    ('demo-claimant-02', 'FNOL', 'demo.theodore@example.test',
     'Claim filed', 'Demo: FNOL received.', 'SENT', 1,
     CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '2 days', NULL),
    ('demo-claimant-02', 'ASSIGNMENT', 'demo.theodore@example.test',
     'Claim assigned', 'Demo: an adjuster picked up the claim.', 'SENT', 1,
     CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '2 days', NULL),
    ('demo-claimant-03', 'ASSIGNMENT', 'demo.priya@example.test',
     'Claim assigned', 'Demo: an adjuster picked up the claim.', 'SENT', 1,
     CURRENT_TIMESTAMP - INTERVAL '3 days', CURRENT_TIMESTAMP - INTERVAL '3 days', NULL),
    ('demo-claimant-03', 'FNOL', 'demo.priya@example.test',
     'Claim filed', 'Demo: FNOL received.', 'FAILED', 3,
     CURRENT_TIMESTAMP + INTERVAL '1 hour', NULL, 'Demo: SMTP timeout (retryable)'),
    ('demo-claimant-05', 'DECISION', 'demo.ingrid@example.test',
     'Decision on claim', 'Demo: approved, payment on its way.', 'SENT', 1,
     CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP - INTERVAL '1 day', NULL),
    ('demo-claimant-06', 'DECISION', 'demo.tomas@example.test',
     'Decision on claim', 'Demo: denied with rationale.', 'SENT', 1,
     CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '2 days', NULL),
    ('demo-claimant-09', 'DECISION', 'demo.anaya9@example.test',
     'Decision on claim', 'Demo: rebroadcast after senior sign-off.', 'PENDING', 0,
     CURRENT_TIMESTAMP - INTERVAL '10 minutes', NULL, NULL),
    ('demo-claimant-10', 'DECISION', 'demo.kabir@example.test',
     'Decision on claim', 'Demo: partially approved; lump sum paid.', 'SENT', 1,
     CURRENT_TIMESTAMP - INTERVAL '3 days', CURRENT_TIMESTAMP - INTERVAL '3 days', NULL),
    ('demo-claimant-11', 'FNOL', 'demo.anaya11@example.test',
     'Claim filed', 'Demo: FNOL received.', 'SENT', 1,
     CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '2 days', NULL),
    ('demo-claimant-11', 'ASSIGNMENT', 'demo.anaya11@example.test',
     'Claim assigned', 'Demo: an adjuster picked up the claim.', 'SENT', 1,
     CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '2 days', NULL)
  ) AS m(sub, kind, to_addr, subject, body, status, attempts, next_at, sent, err)
    ON m.sub = s.claimant_sub
),
created AS (
  INSERT INTO audit_log (actor_sub, action, entity_type, entity_id, before, after, rationale)
  SELECT s.claimant_sub, 'CLAIM_CREATED', 'CLAIM', s.id, NULL,
         jsonb_build_object('claimNumber', s.claim_number, 'level', s.level, 'status', s.status),
         'Demo seed'
  FROM seeded s
),
assigned AS (
  INSERT INTO audit_log (actor_sub, action, entity_type, entity_id, before, after, rationale)
  SELECT NULL, 'CLAIM_ASSIGNED', 'CLAIM', s.id, NULL,
         jsonb_build_object('claimNumber', s.claim_number, 'assignedTo', u.display_name, 'status', s.status),
         'Demo seed'
  FROM seeded s JOIN app_user u ON u.id = s.assigned_adjuster_id
  WHERE s.assigned_adjuster_id IS NOT NULL
),
escalated AS (
  INSERT INTO audit_log (actor_sub, action, entity_type, entity_id, before, after, rationale)
  SELECT '10000000-0000-0000-0000-000000000001', 'CLAIM_ESCALATED', 'CLAIM', s.id,
         jsonb_build_object('claimNumber', s.claim_number, 'status', 'UNDER_REVIEW'),
         jsonb_build_object('claimNumber', s.claim_number, 'status', 'ESCALATED_SUPERVISOR'),
         'Demo seed: above L2 authority'
  FROM seeded s WHERE s.claimant_sub = 'demo-claimant-04'
),
staged AS (
  -- Staged-flow history for the V2 showcase rows (production action names).
  INSERT INTO audit_log (actor_sub, action, entity_type, entity_id, before, after, rationale)
  SELECT v.actor, v.action, 'CLAIM', s.id, v.before, v.after, 'Demo seed'
  FROM seeded s
  JOIN (VALUES
    ('demo-claimant-07', '10000000-0000-0000-0000-000000000001', 'CLAIM_CREATED',
     NULL,
     '{"status":"UNASSIGNED","stage":"REVIEW"}'::jsonb),
    ('demo-claimant-07', NULL, 'CLAIM_ASSIGNED',
     NULL,
     '{"status":"UNDER_REVIEW","assignedTo":"Priya Sharma"}'::jsonb),
    ('demo-claimant-08', '10000000-0000-0000-0000-000000000005', 'REVIEW_ADVANCED',
     '{"status":"UNDER_REVIEW","stage":"REVIEW"}'::jsonb,
     '{"status":"UNDER_REVIEW","stage":"VERIFICATION"}'::jsonb),
    ('demo-claimant-08', '10000000-0000-0000-0000-000000000005', 'VERIFICATION_UPDATED',
     '{"status":"IN_PROGRESS"}'::jsonb,
     '{"status":"COMPLETE","outcome":"PASSED"}'::jsonb),
    ('demo-claimant-09', '10000000-0000-0000-0000-000000000006', 'ASSESSMENT_RECORDED',
     '{"status":"UNDER_REVIEW","stage":"VERIFICATION"}'::jsonb,
     '{"status":"UNDER_REVIEW","stage":"DECISION"}'::jsonb),
    ('demo-claimant-09', '10000000-0000-0000-0000-000000000006', 'PROPOSALS_SAVED',
     '{"status":"UNDER_REVIEW","stage":"DECISION"}'::jsonb,
     '{"proposedTotal":900000.00,"authorityLimit":400000.00}'::jsonb),
    ('demo-claimant-09', '10000000-0000-0000-0000-000000000006', 'CLAIM_REFERRED',
     '{"status":"UNDER_REVIEW","level":"L2","stage":"DECISION"}'::jsonb,
     '{"status":"UNDER_REVIEW","level":"L3","escalatedTo":"L3"}'::jsonb),
    ('demo-claimant-10', '10000000-0000-0000-0000-000000000006', 'DECISION',
     '{"status":"UNDER_REVIEW","stage":"DECISION"}'::jsonb,
     '{"status":"CLOSED","decision":"PARTIALLY_APPROVED"}'::jsonb),
    ('demo-claimant-11', '10000000-0000-0000-0000-000000000001', 'NEED_INFO_SENT',
     '{"status":"UNDER_REVIEW","stage":"REVIEW"}'::jsonb,
     '{"status":"NEED_INFO","needInfoPriorStage":"REVIEW","needInfoReason":"Please upload the itemised final bill and the discharge summary."}'::jsonb)
  ) AS v(sub, actor, action, before, after) ON v.sub = s.claimant_sub
)
-- DECISION rows mirror production shapes: supervisor approval (actor = the
-- provisioned supervisor subject, payment authorized_by NULL) and adjuster
-- denial (actor = the holding L2 adjuster's subject, rationale = remarks).
INSERT INTO audit_log (actor_sub, action, entity_type, entity_id, before, after, rationale)
SELECT CASE WHEN s.claimant_sub = 'demo-claimant-05'
            THEN '10000000-0000-0000-0000-000000000004'
            ELSE '10000000-0000-0000-0000-000000000002' END,
       'DECISION', 'CLAIM', s.id,
       jsonb_build_object('claimNumber', s.claim_number, 'status', 'ESCALATED_SUPERVISOR'),
       jsonb_build_object('claimNumber', s.claim_number, 'status', 'CLOSED',
                          'decision', s.decision, 'indemnityAmount', s.indemnity_amount),
       'Demo seed'
FROM seeded s
WHERE s.claimant_sub IN ('demo-claimant-05', 'demo-claimant-06', 'demo-claimant-10');

COMMIT;
