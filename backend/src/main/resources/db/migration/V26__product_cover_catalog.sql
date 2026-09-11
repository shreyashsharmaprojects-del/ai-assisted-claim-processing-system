-- V26 (V4 S1: cover catalogue).
--
-- Cover codes existed only as ad-hoc strings on policy_cover rows. product_cover
-- catalogues them per product: the display name, the default sub-limit and the
-- default deductible a newly opted cover starts from. A policy may still differ
-- from the product default (its own sub_limit/deductible_default stay on
-- policy_cover) — the catalogue is the reference, not a constraint.
--
-- Catalogue assignments follow each product's V12 description:
--   HLTH-BASIC ("hospitalization, daycare, OPD") gets exactly those three —
--     never MATERNITY (that belongs to HLTH-PLUS).
--   HLTH-PLUS (maternity + room rent) gets the full 5-cover health set.
--   HLTH-CRIT ("lump-sum critical-illness plus hospitalization") gets those two.
--   AUTO-STD ("third-party + own-damage") gets both although no seed policy
--     uses it yet; without THIRD_PARTY the description would be a lie.
--   AUTO-COM gets the two covers its seed policy (POL-30003) already uses.
--   PROP-HOME ("structure + contents") gets both (POL-30004 uses both).
--   PROP-FIRE ("fire and allied perils") gets FIRE — a new code, no seed use yet.
--   HOME (legacy V1) mirrors STRUCTURE + CONTENTS so a legacy home policy can
--     resolve covers if ever given them; no seed policy_cover row uses HOME.
--   AUTO (legacy V1) gets the OWN_DAMAGE its seed policy (POL-20002) uses.
--   HLTH-ORPHAN gets NO rows on purpose: it exists to be unmapped (S5/E5), and
--     an empty catalogue keeps it that way (mirrors the empty required-docs
--     checklist for orphan in S3).
--
-- All additive; V1–V25 untouched.

CREATE TABLE product_cover (
    product_code        VARCHAR(20) NOT NULL REFERENCES product (code),
    cover_code          VARCHAR(40) NOT NULL,
    display_name        VARCHAR(200) NOT NULL,
    default_sub_limit   NUMERIC(14,2) NOT NULL CHECK (default_sub_limit > 0),
    default_deductible  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (default_deductible >= 0),
    sort_order          INT NOT NULL DEFAULT 0,
    PRIMARY KEY (product_code, cover_code)
);

-- Display names match the V13 seed rows exactly so V27's display_name alignment
-- is a verifiable no-op on current data (and fixes drift if any is introduced).
INSERT INTO product_cover
    (product_code, cover_code, display_name, default_sub_limit, default_deductible, sort_order)
VALUES
    -- HOME (legacy V1): mirrors PROP-HOME.
    ('HOME',        'STRUCTURE',       'Structure',                      1500000.00, 10000.00, 1),
    ('HOME',        'CONTENTS',        'Contents',                        500000.00,  5000.00, 2),
    -- AUTO (legacy V1): as used by POL-20002.
    ('AUTO',        'OWN_DAMAGE',      'Own Damage',                       30000.00,  1000.00, 1),
    -- HLTH-BASIC: hospitalization + daycare + OPD. No maternity by design.
    ('HLTH-BASIC',  'HOSPITALIZATION', 'In-patient Hospitalization',      300000.00,  5000.00, 1),
    ('HLTH-BASIC',  'DAYCARE',         'Daycare Procedures',               50000.00,  2500.00, 2),
    ('HLTH-BASIC',  'OPD',             'Out-patient (OPD)',                15000.00,  1000.00, 3),
    -- HLTH-PLUS: the full 5-cover family set.
    ('HLTH-PLUS',   'HOSPITALIZATION', 'In-patient Hospitalization',      500000.00, 10000.00, 1),
    ('HLTH-PLUS',   'ROOM_RENT',       'Room Rent',                       100000.00,  5000.00, 2),
    ('HLTH-PLUS',   'DAYCARE',         'Daycare Procedures',              100000.00,  5000.00, 3),
    ('HLTH-PLUS',   'OPD',             'Out-patient (OPD)',                30000.00,  2000.00, 4),
    ('HLTH-PLUS',   'MATERNITY',       'Maternity',                        75000.00, 10000.00, 5),
    -- HLTH-CRIT: lump sum + hospitalization.
    ('HLTH-CRIT',   'CRITICAL_ILLNESS','Critical Illness (lump sum)',    2500000.00,     0.00, 1),
    ('HLTH-CRIT',   'HOSPITALIZATION', 'In-patient Hospitalization',      500000.00, 10000.00, 2),
    -- AUTO-STD: no seed policy yet; catalogue-first rows.
    ('AUTO-STD',    'OWN_DAMAGE',      'Own Damage',                      500000.00,  2000.00, 1),
    ('AUTO-STD',    'THIRD_PARTY',     'Third-party Liability',           750000.00,     0.00, 2),
    -- AUTO-COM: as used by POL-30003.
    ('AUTO-COM',    'OWN_DAMAGE',      'Own Damage',                      800000.00,  5000.00, 1),
    ('AUTO-COM',    'THIRD_PARTY',     'Third-party Liability',           750000.00,     0.00, 2),
    -- PROP-HOME: as used by POL-30004.
    ('PROP-HOME',   'STRUCTURE',       'Structure',                      1500000.00, 10000.00, 1),
    ('PROP-HOME',   'CONTENTS',        'Contents',                        500000.00,  5000.00, 2),
    -- PROP-FIRE: new FIRE code; no seed use yet.
    ('PROP-FIRE',   'FIRE',            'Fire & Allied Perils',            2000000.00, 10000.00, 1);
