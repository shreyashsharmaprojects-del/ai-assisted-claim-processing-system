-- V13 (V2-1 dummy data): products/policies/staff/skills reference rows.
--
-- Migration (not demo-seed) because the V2-1 integration tests assert against these
-- rows on the hermetic test database, exactly like V1's V2/V3/V4 seeds. Scenario
-- *claims* (S1–S10) are NOT seeded here — they are filed through the API in later
-- slices/tests so every workflow assertion observes real transitions.
--
-- Identity discipline (from V4): keycloak_sub values are FIXED and match
-- keycloak/realm-export.template.json. New staff added there in the same change:
-- adjuster.four (Aisha, L1), adjuster.five (Rahul, L2), adjuster.six (Meera, L3).
-- V1 rows (Priya/Marcus L1, Ines L2) are untouched.
--
-- Holder emails double as the claimant-cockpit link key (policy.holder_email =
-- claimant account email), so each customer below owns a distinct example.test
-- address used by the cockpit tests and the demo script.

-- --- V1 policy enrichment (POL-10001 -> HLTH-PLUS 5-cover set; POL-20002 untouched
-- --- shape, backfilled sums already in V12) ----------------------------------------
UPDATE policy
SET product_code = 'HLTH-PLUS',
    holder_email = 'ada.lovelace@example.test',
    sum_insured = 1000000.00,
    rating_params = '{"sum_insured_band": "10L", "room_rent_cap_per_day": 8000, "waiting_period_months": 3, "zone": "Zone A", "family_size": 4}'::jsonb,
    clauses = '{"covered": ["In-patient hospitalization", "Daycare procedures", "Pre/post hospitalization (30/60 days)", "Maternity after 9 months"], "excluded": ["Cosmetic surgery", "Dental unless accident", "Pre-existing before waiting period"], "scope": "India-wide cashless + reimbursement"}'::jsonb,
    valid_from = '2026-01-01',
    valid_to = '2026-12-31'
WHERE policy_number = 'POL-10001';

UPDATE policy
SET sum_insured = 30000.00,
    rating_params = '{"idv": 30000, "ncb_percent": 20, "zone": "Urban"}'::jsonb,
    clauses = '{"covered": ["Own damage", "Third-party liability"], "excluded": ["Drink-and-drive", "No valid licence"], "scope": "India"}'::jsonb,
    valid_from = '2026-01-01',
    valid_to = '2026-12-31'
WHERE policy_number = 'POL-20002';

-- --- new customers/policies ---------------------------------------------------------
-- POL-30001 health-basic individual; POL-30002 critical illness (high limits, S4 fuel);
-- POL-30003 motor comprehensive; POL-30004 home (property); POL-30005 family plus;
-- POL-30006 low sum insured (S3 exhaustion); POL-30007 RETIRED (E9); POL-30008
-- EXPIRED (E9); POL-30009 orphan product (S5/E5); POL-30010 basic second holder
-- (S6 tie-break pair fuel).
INSERT INTO policy (policy_number, product_code, holder_name, holder_email, coverage,
                    status, sum_insured, rating_params, clauses, valid_from, valid_to)
VALUES
    ('POL-30001', 'HLTH-BASIC', 'Ravi Menon', 'ravi.menon@example.test',
     '{"type":"health","plan":"basic"}', 'ACTIVE', 500000.00,
     '{"sum_insured_band": "5L", "room_rent_cap_per_day": 5000, "waiting_period_months": 3, "zone": "Zone B"}',
     '{"covered": ["In-patient hospitalization", "Daycare procedures", "OPD up to sub-limit"], "excluded": ["Cosmetic surgery", "AYUSH beyond cap"], "scope": "India-wide"}',
     '2026-01-01', '2026-12-31'),
    ('POL-30002', 'HLTH-CRIT', 'Fatima Khan', 'fatima.khan@example.test',
     '{"type":"health","plan":"critical-illness"}', 'ACTIVE', 2500000.00,
     '{"sum_insured_band": "25L", "survival_period_days": 30, "waiting_period_months": 6, "zone": "Zone A"}',
     '{"covered": ["Listed critical illnesses (lump sum)", "Hospitalization"], "excluded": ["Early-stage CIS", "Pre-existing before waiting period"], "scope": "India-wide"}',
     '2026-01-01', '2026-12-31'),
    ('POL-30003', 'AUTO-COM', 'David D''Souza', 'david.dsouza@example.test',
     '{"type":"motor","plan":"comprehensive"}', 'ACTIVE', 800000.00,
     '{"idv": 800000, "ncb_percent": 35, "zone": "Urban"}',
     '{"covered": ["Own damage", "Third-party liability", "Zero-dep add-on"], "excluded": ["Drink-and-drive", "No valid licence"], "scope": "India"}',
     '2026-01-01', '2026-12-31'),
    ('POL-30004', 'PROP-HOME', 'Lakshmi Iyer', 'lakshmi.iyer@example.test',
     '{"type":"home"}', 'ACTIVE', 2000000.00,
     '{"structure_si": 1500000, "contents_si": 500000, "construction": "RCC"}',
     '{"covered": ["Fire", "Flood", "Burglary (contents)"], "excluded": ["Wear and tear", "War"], "scope": "Registered address"}',
     '2026-01-01', '2026-12-31'),
    ('POL-30005', 'HLTH-PLUS', 'Arjun Nair', 'arjun.nair@example.test',
     '{"type":"health","plan":"plus-family"}', 'ACTIVE', 1000000.00,
     '{"sum_insured_band": "10L", "room_rent_cap_per_day": 8000, "waiting_period_months": 3, "zone": "Zone A", "family_size": 5}',
     '{"covered": ["In-patient hospitalization", "Daycare procedures", "Maternity after 9 months"], "excluded": ["Cosmetic surgery", "Dental unless accident"], "scope": "India-wide cashless + reimbursement"}',
     '2026-01-01', '2026-12-31'),
    ('POL-30006', 'HLTH-BASIC', 'Kavya Reddy', 'kavya.reddy@example.test',
     '{"type":"health","plan":"basic-low-si"}', 'ACTIVE', 50000.00,
     '{"sum_insured_band": "50K", "room_rent_cap_per_day": 3000, "waiting_period_months": 3, "zone": "Zone C"}',
     '{"covered": ["In-patient hospitalization", "Daycare procedures"], "excluded": ["Cosmetic surgery"], "scope": "India-wide"}',
     '2026-01-01', '2026-12-31'),
    ('POL-30007', 'HLTH-BASIC', 'Retired Holder', 'retired.holder@example.test',
     '{"type":"health","plan":"basic"}', 'RETIRED', 500000.00,
     '{"sum_insured_band": "5L"}', '{"covered": ["In-patient hospitalization"], "excluded": [], "scope": "India-wide"}',
     '2025-01-01', '2025-12-31'),
    ('POL-30008', 'HLTH-PLUS', 'Expired Holder', 'expired.holder@example.test',
     '{"type":"health","plan":"plus"}', 'EXPIRED', 1000000.00,
     '{"sum_insured_band": "10L"}', '{"covered": ["In-patient hospitalization"], "excluded": [], "scope": "India-wide"}',
     '2025-01-01', '2025-12-31'),
    ('POL-30009', 'HLTH-ORPHAN', 'Orphan Holder', 'orphan.holder@example.test',
     '{"type":"health","plan":"orphan"}', 'ACTIVE', 500000.00,
     '{"sum_insured_band": "5L"}', '{"covered": ["In-patient hospitalization"], "excluded": [], "scope": "India-wide"}',
     '2026-01-01', '2026-12-31'),
    ('POL-30010', 'HLTH-BASIC', 'Vikram Rao', 'vikram.rao@example.test',
     '{"type":"health","plan":"basic"}', 'ACTIVE', 500000.00,
     '{"sum_insured_band": "5L", "room_rent_cap_per_day": 5000, "waiting_period_months": 3, "zone": "Zone B"}',
     '{"covered": ["In-patient hospitalization", "Daycare procedures", "OPD up to sub-limit"], "excluded": ["Cosmetic surgery"], "scope": "India-wide"}',
     '2026-01-01', '2026-12-31');

-- --- covers -------------------------------------------------------------------------
-- Health 5-cover set (sub-limits + deductibles in INR). Single-cover products get one row.
-- POL-10001 (Ada, HLTH-PLUS): HOSPITALIZATION 5L / ROOM_RENT 8k-per-day modelled as
-- 100k event cap / DAYCARE 1L / OPD 30k / MATERNITY 75k — the S1 partial-approval fuel.
INSERT INTO policy_cover (policy_id, cover_code, display_name, sub_limit, deductible_default, sort_order)
SELECT p.id, c.cover_code, c.display_name, c.sub_limit, c.deductible_default, c.sort_order
FROM policy p
JOIN (VALUES
    ('POL-10001', 'HOSPITALIZATION', 'In-patient Hospitalization', 500000.00, 10000.00, 1),
    ('POL-10001', 'ROOM_RENT',       'Room Rent',                  100000.00,  5000.00, 2),
    ('POL-10001', 'DAYCARE',         'Daycare Procedures',         100000.00,  5000.00, 3),
    ('POL-10001', 'OPD',             'Out-patient (OPD)',           30000.00,  2000.00, 4),
    ('POL-10001', 'MATERNITY',       'Maternity',                   75000.00, 10000.00, 5),
    ('POL-30001', 'HOSPITALIZATION', 'In-patient Hospitalization', 300000.00,  5000.00, 1),
    ('POL-30001', 'DAYCARE',         'Daycare Procedures',          50000.00,  2500.00, 2),
    ('POL-30001', 'OPD',             'Out-patient (OPD)',           15000.00,  1000.00, 3),
    ('POL-30002', 'CRITICAL_ILLNESS','Critical Illness (lump sum)',2500000.00,     0.00, 1),
    ('POL-30002', 'HOSPITALIZATION', 'In-patient Hospitalization', 500000.00, 10000.00, 2),
    ('POL-30003', 'OWN_DAMAGE',      'Own Damage',                 800000.00,  5000.00, 1),
    ('POL-30003', 'THIRD_PARTY',     'Third-party Liability',      750000.00,      0.00, 2),
    ('POL-30004', 'STRUCTURE',       'Structure',                 1500000.00, 10000.00, 1),
    ('POL-30004', 'CONTENTS',        'Contents',                   500000.00,  5000.00, 2),
    ('POL-30005', 'HOSPITALIZATION', 'In-patient Hospitalization', 500000.00, 10000.00, 1),
    ('POL-30005', 'ROOM_RENT',       'Room Rent',                  100000.00,  5000.00, 2),
    ('POL-30005', 'DAYCARE',         'Daycare Procedures',         100000.00,  5000.00, 3),
    ('POL-30005', 'OPD',             'Out-patient (OPD)',           30000.00,  2000.00, 4),
    ('POL-30005', 'MATERNITY',       'Maternity',                   75000.00, 10000.00, 5),
    ('POL-30006', 'HOSPITALIZATION', 'In-patient Hospitalization',  40000.00,  2000.00, 1),
    ('POL-30006', 'OPD',             'Out-patient (OPD)',           10000.00,  1000.00, 2),
    ('POL-30010', 'HOSPITALIZATION', 'In-patient Hospitalization', 300000.00,  5000.00, 1),
    ('POL-30010', 'DAYCARE',         'Daycare Procedures',          50000.00,  2500.00, 2),
    ('POL-30010', 'OPD',             'Out-patient (OPD)',           15000.00,  1000.00, 3)
) AS c(policy_number, cover_code, display_name, sub_limit, deductible_default, sort_order)
  ON c.policy_number = p.policy_number;

-- Grace's AUTO policy: single default cover so V1's single-indemnity journey keeps a
-- cover to ride on when V2-2 arrives (degenerate single-cover case from the plan fork).
INSERT INTO policy_cover (policy_id, cover_code, display_name, sub_limit, deductible_default, sort_order)
SELECT id, 'OWN_DAMAGE', 'Own Damage', 30000.00, 1000.00, 1 FROM policy WHERE policy_number = 'POL-20002';

-- --- new staff (L1 + L2 + first L3) ---------------------------------------------------
INSERT INTO app_user (keycloak_sub, display_name, email, level) VALUES
    ('10000000-0000-0000-0000-000000000005', 'Aisha Verma',   'aisha.verma@claims.test',   'L1'),
    ('10000000-0000-0000-0000-000000000006', 'Rahul Singh',   'rahul.singh@claims.test',   'L2'),
    ('10000000-0000-0000-0000-000000000007', 'Meera Nair',    'meera.nair@claims.test',    'L3');

-- --- skill matrix ----------------------------------------------------------------------
-- Overlap on HLTH-BASIC (Priya + Aisha: S6 tie-break fuel). L3 Meera is catch-all
-- EXCEPT the orphan product (S5/E5: HLTH-ORPHAN deliberately empty). Legacy HOME/AUTO
-- map to the V1 staff so V1 routing keeps working until V2-3 replaces it.
INSERT INTO adjuster_skill (adjuster_id, product_code)
SELECT a.id, s.product_code
FROM app_user a
JOIN (VALUES
    -- keycloak_sub fragment, product
    ('000000000001', 'HOME'), ('000000000001', 'HLTH-BASIC'), ('000000000001', 'HLTH-PLUS'),
    ('000000000002', 'HOME'), ('000000000002', 'PROP-HOME'),  ('000000000002', 'PROP-FIRE'),
    ('000000000003', 'AUTO'), ('000000000003', 'AUTO-COM'),   ('000000000003', 'HLTH-CRIT'),
    ('000000000005', 'HLTH-BASIC'), ('000000000005', 'HLTH-PLUS'),
    ('000000000006', 'HLTH-PLUS'),  ('000000000006', 'HLTH-CRIT'), ('000000000006', 'AUTO-COM'),
    ('000000000007', 'HLTH-BASIC'), ('000000000007', 'HLTH-PLUS'), ('000000000007', 'HLTH-CRIT'),
    ('000000000007', 'AUTO-STD'),   ('000000000007', 'AUTO-COM'),
    ('000000000007', 'PROP-HOME'),  ('000000000007', 'PROP-FIRE'),
    ('000000000007', 'HOME'),       ('000000000007', 'AUTO')
) AS s(sub_frag, product_code) ON a.keycloak_sub LIKE '%' || s.sub_frag;
