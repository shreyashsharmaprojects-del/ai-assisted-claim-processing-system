-- Demo seed for the sales demo. DEV `claims` DATABASE ONLY — never claims_e2e
-- (both npm scripts hardcode `-d claims`, and this file refuses any other DB).
--
-- Marker convention (what demo:reset deletes): policy_number LIKE 'POL-DEMO-%',
-- claimant_sub LIKE 'demo-%'. Holder names/emails are fictitious (@example.test).
-- Rungs covered: UNASSIGNED → UNDER_REVIEW (L1 with reserve, L2 with note) →
-- ESCALATED_SUPERVISOR → CLOSED (APPROVED with payment, DENIED with remarks).
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
DELETE FROM payment WHERE claim_id IN (SELECT id FROM claim WHERE claimant_sub LIKE 'demo-%');
DELETE FROM internal_note WHERE claim_id IN (SELECT id FROM claim WHERE claimant_sub LIKE 'demo-%');
DELETE FROM fnol_submission WHERE claimant_sub LIKE 'demo-%';
DELETE FROM attachment WHERE claim_id IN (SELECT id FROM claim WHERE claimant_sub LIKE 'demo-%');
DELETE FROM claim WHERE claimant_sub LIKE 'demo-%';
DELETE FROM policy WHERE policy_number LIKE 'POL-DEMO-%';

-- 1. The book: 6 policies. HOME/AUTO only — the only products in authority_config,
-- so every demo policy is fileable/routable as-is (HOME routes L1, AUTO routes L2).
INSERT INTO policy (policy_number, product_code, holder_name, holder_email, coverage) VALUES
  ('POL-DEMO-01', 'HOME', 'Eleanor Vance',  'demo.eleanor@example.test',  '{"type":"home","sum_insured":450000}'::jsonb),
  ('POL-DEMO-02', 'HOME', 'Theodore Marsh', 'demo.theodore@example.test', '{"type":"home","sum_insured":620000}'::jsonb),
  ('POL-DEMO-03', 'AUTO', 'Priya Nair',     'demo.priya@example.test',   '{"type":"auto","sum_insured":28000}'::jsonb),
  ('POL-DEMO-04', 'HOME', 'Samuel Okafor',  'demo.samuel@example.test',  '{"type":"home","sum_insured":510000}'::jsonb),
  ('POL-DEMO-05', 'AUTO', 'Ingrid Halvors', 'demo.ingrid@example.test',  '{"type":"auto","sum_insured":35000}'::jsonb),
  ('POL-DEMO-06', 'HOME', 'Tomas Reyes',    'demo.tomas@example.test',   '{"type":"home","sum_insured":380000}'::jsonb);

-- 2. Claims at each ladder rung. Numbers come from claim_number_seq (kept in sync);
-- created_at is staggered so the demo shows aging pressure on the overview.
-- NOTE: every dependent CTE below reads the `seeded` RETURNING set, never the
-- claim table — same-statement CTEs share a snapshot and cannot see each other's
-- writes, so all needed columns ride the RETURNING list.
WITH seeded AS (
  INSERT INTO claim (claim_number, policy_id, claimant_sub, level, status,
      loss_date, loss_location, loss_description, claimant_remarks,
      created_at, assigned_adjuster_id, assigned_at,
      reserve_amount, decision, decision_remarks, indemnity_amount, closed_at)
  VALUES
    -- Fresh filing, not yet picked up.
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-01'),
     'demo-claimant-01', 'L1', 'UNASSIGNED',
     CURRENT_DATE - 1, 'Bristol', 'Burst pipe flooded the kitchen overnight.',
     'Prefer email contact.', CURRENT_TIMESTAMP - INTERVAL '2 hours',
     NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    -- With an L1 adjuster, reserve set.
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-02'),
     'demo-claimant-02', 'L1', 'UNDER_REVIEW',
     CURRENT_DATE - 3, 'Leeds', 'Storm tore ridge tiles; rain ingress in two rooms.',
     NULL, CURRENT_TIMESTAMP - INTERVAL '2 days',
     (SELECT id FROM app_user WHERE keycloak_sub = '10000000-0000-0000-0000-000000000001'),
     CURRENT_TIMESTAMP - INTERVAL '2 days',
     1800.00, NULL, NULL, NULL, NULL),
    -- With the L2 adjuster, internal note on file.
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-03'),
     'demo-claimant-03', 'L2', 'UNDER_REVIEW',
     CURRENT_DATE - 4, 'Manchester', 'Two-car collision at a roundabout; front wing damage.',
     NULL, CURRENT_TIMESTAMP - INTERVAL '3 days',
     (SELECT id FROM app_user WHERE keycloak_sub = '10000000-0000-0000-0000-000000000003'),
     CURRENT_TIMESTAMP - INTERVAL '3 days',
     4200.00, NULL, NULL, NULL, NULL),
    -- Above L2 authority: waiting on the supervisor (assignee always null here).
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-04'),
     'demo-claimant-04', 'L2', 'ESCALATED_SUPERVISOR',
     CURRENT_DATE - 6, 'Bath', 'Fire in the garage spread to the roof structure.',
     NULL, CURRENT_TIMESTAMP - INTERVAL '6 days',
     NULL, NULL, 9500.00, NULL, NULL, NULL, NULL),
    -- Closed: supervisor approved (payment recorded, authorizer NULL = supervisor).
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-05'),
     'demo-claimant-05', 'L2', 'CLOSED',
     CURRENT_DATE - 12, 'York', 'Hail damage across bonnet and roof; assessor confirmed.',
     NULL, CURRENT_TIMESTAMP - INTERVAL '12 days',
     NULL, NULL, 7900.00, 'APPROVED', NULL, 8200.00,
     CURRENT_TIMESTAMP - INTERVAL '1 day'),
    -- Closed: adjuster denied (rationale is the claimant-visible remarks).
    ('CLM-' || lpad(nextval('claim_number_seq')::text, 6, '0'),
     (SELECT id FROM policy WHERE policy_number = 'POL-DEMO-06'),
     'demo-claimant-06', 'L1', 'CLOSED',
     CURRENT_DATE - 9, 'Exeter', 'Shed roof collapsed under snow load.',
     NULL, CURRENT_TIMESTAMP - INTERVAL '9 days',
     (SELECT id FROM app_user WHERE keycloak_sub = '10000000-0000-0000-0000-000000000002'),
     CURRENT_TIMESTAMP - INTERVAL '9 days',
     900.00, 'DENIED', 'The policy excludes outbuildings from storm cover.', NULL,
     CURRENT_TIMESTAMP - INTERVAL '2 days')
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
pay AS (
  INSERT INTO payment (claim_id, amount, authorized_by_id)
  SELECT s.id, 8200.00, NULL FROM seeded s WHERE s.claimant_sub = 'demo-claimant-05'
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
WHERE s.claimant_sub IN ('demo-claimant-05', 'demo-claimant-06');

COMMIT;
