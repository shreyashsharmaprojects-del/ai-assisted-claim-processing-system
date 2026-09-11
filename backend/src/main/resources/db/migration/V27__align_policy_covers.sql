-- V27 (V4 S1: align seed policy_cover rows with the V26 catalogue).
--
-- V12/V13 are immutable and untouched. Every row V13 inserted already resolves
-- to a V26 catalogue row (verified in the Session 1 integration test), so this
-- migration only:
--   1. aligns display_name on existing rows to the catalogue spelling, and
--   2. adds FK enforcement (policy_cover cover usage must resolve to the
--      product's catalogue) — but deferred to the END, after alignment.
--
-- Deliberately NOT done here (per the session contract):
--   - sub_limit / deductible_default are left alone: a policy legitimately
--     differs from the product default (e.g. POL-30001 HOSPITALIZATION 300000
--     vs the 300000 HLTH-BASIC default is coincidence, not a rule; POL-10001
--     HOSPITALIZATION 500000 vs 500000 ditto — either may diverge later).
--   - no missing-row INSERTs: every seed policy already carries exactly the
--     covers its product catalogues (checked, not assumed — the integration
--     test fails the slice if this ever drifts).
--   - HLTH-ORPHAN (POL-30009) carries no covers and the catalogue holds none
--     for it; that emptiness is the S5/E5 fixture and stays.
--
-- The FK is a plain (non-validating) addition: all existing rows resolve, so a
-- validating FK is safe. Future policy_cover writers (admin import, new seeds)
-- must resolve against product_cover first.

-- 1. Align display names to the catalogue spelling.
UPDATE policy_cover AS pc
SET display_name = cat.display_name
FROM policy AS p, product_cover AS cat
WHERE pc.policy_id = p.id
  AND cat.product_code = p.product_code
  AND cat.cover_code = pc.cover_code
  AND pc.display_name IS DISTINCT FROM cat.display_name;

-- 2. Safety net: fail loudly if any row still does not resolve (instead of
-- adding a constraint that would then fail cryptically).
DO $$ BEGIN
    IF EXISTS (
        SELECT 1
        FROM policy_cover pc
        JOIN policy p ON p.id = pc.policy_id
        LEFT JOIN product_cover cat
          ON cat.product_code = p.product_code
         AND cat.cover_code = pc.cover_code
        WHERE cat.product_code IS NULL
    ) THEN
        RAISE EXCEPTION 'V27: unresolvable policy_cover rows exist (see product_cover catalogue)';
    END IF;
END $$;

-- 3. Enforce resolution for all future writes. product_cover's PK is the
-- (product_code, cover_code) pair, so the FK carries the policy's product code
-- on each policy_cover row. A trigger keeps it maintenance-free: writers only
-- ever set (policy_id, cover_code) and the product code follows the policy.
ALTER TABLE policy_cover
    ADD COLUMN product_code VARCHAR(20) NULL REFERENCES product (code);

UPDATE policy_cover AS pc
SET product_code = p.product_code
FROM policy AS p
WHERE pc.policy_id = p.id;

ALTER TABLE policy_cover
    ALTER COLUMN product_code SET NOT NULL;

CREATE OR REPLACE FUNCTION trg_policy_cover_product_code() RETURNS trigger AS $$
BEGIN
    SELECT p.product_code INTO NEW.product_code
    FROM policy p WHERE p.id = NEW.policy_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'policy_cover: unknown policy_id %', NEW.policy_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_policy_cover_product_code ON policy_cover;

CREATE TRIGGER trg_policy_cover_product_code
    BEFORE INSERT OR UPDATE OF policy_id ON policy_cover
    FOR EACH ROW EXECUTE FUNCTION trg_policy_cover_product_code();

ALTER TABLE policy_cover
    ADD CONSTRAINT fk_policy_cover_product_cover
    FOREIGN KEY (product_code, cover_code) REFERENCES product_cover (product_code, cover_code);
