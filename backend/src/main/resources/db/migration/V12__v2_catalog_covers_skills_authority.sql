-- V12 (V2-1: catalog, policy enrichment, skill matrix, authority/SLA foundations).
--
-- All additive; V1–V11 untouched. V1 rows (HOME/AUTO policies, HOME/AUTO authority
-- rows, 3 adjusters) are preserved and backfilled so every V1 test and journey keeps
-- passing. New V2 tables carry only what V2-1 writes: the product catalog, per-policy
-- covers, the adjuster<->product skill matrix, and the L3/SLA/authority-basis columns
-- the later V2 slices will consume. claim_cover / verification / stage columns arrive
-- with the slices that write them (V2-2 / V2-4), exactly like V1's vertical slicing.
--
-- Locked rules applied (2026-09-07): limits constrain assessment/approval, never FNOL
-- (no filing-time CHECK on claimed vs sub-limit here); remaining limits are derived
-- queries, not stored counters; EXPIRED joins RETIRED as a non-fileable policy status.

-- --- product catalog ------------------------------------------------------------
CREATE TABLE product (
    code         VARCHAR(20) PRIMARY KEY,
    family       VARCHAR(20) NOT NULL CHECK (family IN ('HEALTH', 'NON_HEALTH', 'PROPERTY')),
    display_name VARCHAR(200) NOT NULL,
    description  TEXT NOT NULL DEFAULT ''
);

-- V1 codes are catalog rows too (family mapping: HOME -> PROPERTY, AUTO -> NON_HEALTH).
INSERT INTO product (code, family, display_name, description) VALUES
    ('HOME',        'PROPERTY',   'Home (legacy V1)',        'Legacy V1 home product. Preserved for V1 regression.'),
    ('AUTO',        'NON_HEALTH', 'Auto (legacy V1)',        'Legacy V1 auto product. Preserved for V1 regression.'),
    ('HLTH-BASIC',  'HEALTH',     'Health Basic',            'Entry health cover: hospitalization, daycare, OPD.'),
    ('HLTH-PLUS',   'HEALTH',     'Health Plus',             'Family health cover with maternity and room-rent options.'),
    ('HLTH-CRIT',   'HEALTH',     'Critical Illness',        'Lump-sum critical-illness cover plus hospitalization.'),
    ('AUTO-STD',    'NON_HEALTH', 'Motor Standard',          'Third-party + own-damage motor cover.'),
    ('AUTO-COM',    'NON_HEALTH', 'Motor Comprehensive',     'Comprehensive motor cover with add-ons.'),
    ('PROP-HOME',   'PROPERTY',   'Home Insurance',          'Structure + contents home cover.'),
    ('PROP-FIRE',   'PROPERTY',   'Fire & Perils',           'Fire and allied perils cover for dwellings.'),
    ('HLTH-ORPHAN', 'HEALTH',     'Health Orphan (unmapped)', 'Product with deliberately no mapped adjuster (S5/E5).');

-- --- policy enrichment ----------------------------------------------------------
-- sum_insured: the policy-period cap (remaining = sum_insured - prior net payables,
-- derived at decision time; never a stored counter).
-- rating_params: the numbers that define the cover (sum insured band, room-rent cap,
-- waiting periods, zone, ...). clauses: {covered[], excluded[], scope} wording.
-- valid_to in the past (with status ACTIVE) reads as EXPIRED at FNOL; the status
-- column itself gains EXPIRED for explicitly lapsed rows.
ALTER TABLE policy
    ADD COLUMN sum_insured NUMERIC(14,2),
    ADD COLUMN rating_params JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN clauses JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN valid_from DATE,
    ADD COLUMN valid_to DATE;

ALTER TABLE policy DROP CONSTRAINT IF EXISTS chk_policy_status;
ALTER TABLE policy
    ADD CONSTRAINT chk_policy_status CHECK (status IN ('ACTIVE', 'RETIRED', 'EXPIRED'));

-- Backfill the two V1 seeds from their legacy coverage blobs.
UPDATE policy SET sum_insured = 500000.00 WHERE policy_number = 'POL-10001';
UPDATE policy SET sum_insured = 30000.00 WHERE policy_number = 'POL-20002';

-- --- policy covers (the opted covers on a policy) --------------------------------
CREATE TABLE policy_cover (
    id                  BIGSERIAL PRIMARY KEY,
    policy_id           BIGINT NOT NULL REFERENCES policy (id),
    cover_code          VARCHAR(40) NOT NULL,
    display_name        VARCHAR(200) NOT NULL,
    sub_limit           NUMERIC(14,2) NOT NULL CHECK (sub_limit > 0),
    deductible_default  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (deductible_default >= 0),
    sort_order          INT NOT NULL DEFAULT 0,
    UNIQUE (policy_id, cover_code)
);

CREATE INDEX idx_policy_cover_policy_id ON policy_cover (policy_id);

-- --- adjuster <-> product skill matrix (assignment eligibility; V2-3 consumes it) --
CREATE TABLE adjuster_skill (
    adjuster_id  BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    product_code VARCHAR(20) NOT NULL REFERENCES product (code),
    PRIMARY KEY (adjuster_id, product_code)
);

CREATE INDEX idx_adjuster_skill_product ON adjuster_skill (product_code);

-- --- app_user: L3 rung + active flag ---------------------------------------------
-- level gains L3 (senior adjuster). active=FALSE removes an adjuster from assignment
-- eligibility without deleting history. Existing rows stay active.
ALTER TABLE app_user DROP CONSTRAINT IF EXISTS app_user_level_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_level_check CHECK (level IN ('L1', 'L2', 'L3'));
ALTER TABLE app_user ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

-- --- authority_config: L3 limit + basis + SLA ------------------------------------
-- authority_basis: which aggregate the gate compares (APPROVED_TOTAL default,
-- conservative; NET_PAYABLE_TOTAL per product). SLA: warning flags only; breach1/2
-- run the configured action. V1 HOME/AUTO rows are backfilled to V1 semantics
-- (warn 2d, 3d -> next rung, 5d -> supervisor) so the V1 aging tests keep passing.
ALTER TABLE authority_config
    ADD COLUMN l3_limit_amount NUMERIC(14,2),
    ADD COLUMN authority_basis VARCHAR(20) NOT NULL DEFAULT 'APPROVED_TOTAL',
    ADD COLUMN sla_warning_days INT NOT NULL DEFAULT 2,
    ADD COLUMN sla_breach1_days INT NOT NULL DEFAULT 3,
    ADD COLUMN sla_breach1_action VARCHAR(30) NOT NULL DEFAULT 'ESCALATE_NEXT_LEVEL',
    ADD COLUMN sla_breach2_days INT,
    ADD COLUMN sla_breach2_action VARCHAR(30);

ALTER TABLE authority_config
    ADD CONSTRAINT chk_authority_basis CHECK (authority_basis IN ('APPROVED_TOTAL', 'NET_PAYABLE_TOTAL')),
    ADD CONSTRAINT chk_breach1_action CHECK (sla_breach1_action IN ('ESCALATE_NEXT_LEVEL', 'ESCALATE_SUPERVISOR')),
    ADD CONSTRAINT chk_breach2_action CHECK (sla_breach2_action IS NULL OR sla_breach2_action IN ('ESCALATE_NEXT_LEVEL', 'ESCALATE_SUPERVISOR'));

UPDATE authority_config
    SET l3_limit_amount = 25000.00,
        sla_breach2_days = 5,
        sla_breach2_action = 'ESCALATE_SUPERVISOR'
    WHERE product_code IN ('HOME', 'AUTO');

-- New V2 product rows: limits rise with severity (INR); entry level in route_level.
-- HLTH-ORPHAN has no skill mappings (S5/E5) but still needs a routing row.
INSERT INTO authority_config
    (product_code, route_level, l1_limit_amount, l2_limit_amount, l3_limit_amount,
     authority_basis, sla_warning_days, sla_breach1_days, sla_breach1_action,
     sla_breach2_days, sla_breach2_action)
VALUES
    ('HLTH-BASIC',  'L1',  50000.00,  200000.00,  500000.00,  'APPROVED_TOTAL', 2, 3, 'ESCALATE_NEXT_LEVEL', 5, 'ESCALATE_SUPERVISOR'),
    ('HLTH-PLUS',   'L1',  100000.00, 400000.00,  1000000.00, 'APPROVED_TOTAL', 2, 3, 'ESCALATE_NEXT_LEVEL', 5, 'ESCALATE_SUPERVISOR'),
    ('HLTH-CRIT',   'L2',  200000.00, 800000.00,  2500000.00, 'APPROVED_TOTAL', 2, 3, 'ESCALATE_NEXT_LEVEL', 5, 'ESCALATE_SUPERVISOR'),
    ('AUTO-STD',    'L1',  50000.00,  200000.00,  500000.00,  'APPROVED_TOTAL', 2, 3, 'ESCALATE_NEXT_LEVEL', 5, 'ESCALATE_SUPERVISOR'),
    ('AUTO-COM',    'L2',  100000.00, 400000.00,  1000000.00, 'APPROVED_TOTAL', 2, 3, 'ESCALATE_NEXT_LEVEL', 5, 'ESCALATE_SUPERVISOR'),
    ('PROP-HOME',   'L1',  100000.00, 500000.00,  2000000.00, 'APPROVED_TOTAL', 2, 3, 'ESCALATE_NEXT_LEVEL', 5, 'ESCALATE_SUPERVISOR'),
    ('PROP-FIRE',   'L1',  100000.00, 500000.00,  2000000.00, 'APPROVED_TOTAL', 2, 3, 'ESCALATE_NEXT_LEVEL', 5, 'ESCALATE_SUPERVISOR'),
    ('HLTH-ORPHAN', 'L1',  50000.00,  200000.00,  500000.00,  'APPROVED_TOTAL', 2, 3, 'ESCALATE_NEXT_LEVEL', 5, 'ESCALATE_SUPERVISOR');
