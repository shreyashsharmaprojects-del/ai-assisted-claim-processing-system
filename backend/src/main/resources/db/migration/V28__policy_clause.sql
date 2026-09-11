-- V28 (V4 S1: policy clause reference table).
--
-- Clauses are reference data, not claim state: the wording an adjuster reads
-- when deciding a cover. cover_code NULL means product-level (applies to every
-- cover of the product, e.g. a family-wide fraud condition or a DEFINITION).
--
-- The three typed columns are the point of the design: they let Java do date
-- and money arithmetic (waiting-period and sub-limit checks in Session 2)
-- without an LLM. Each is non-null exactly for its own clause_type — enforced
-- by CHECK, so a WAITING_PERIOD row with NULL waiting_period_days cannot exist.
--
-- Clause selection is ALWAYS by the claim's loss_date, never today:
--   effective_from <= loss_date AND (effective_to IS NULL OR effective_to > loss_date)
-- (Session 1's maternity pair — 270 days superseded 2024-04-01 by 180 days —
-- exists so a test asserts both directions of this selection.)
--
-- All additive; V1–V25 untouched.

CREATE TABLE policy_clause (
    id                  BIGSERIAL PRIMARY KEY,
    product_code        VARCHAR(20) NOT NULL REFERENCES product (code),
    cover_code          VARCHAR(40) NULL,
    clause_ref          VARCHAR(40) NOT NULL,
    clause_type         VARCHAR(30) NOT NULL CHECK (clause_type IN
                            ('COVERAGE', 'EXCLUSION', 'WAITING_PERIOD',
                             'SUB_LIMIT', 'CO_PAY', 'CONDITION', 'DEFINITION')),
    title               VARCHAR(200) NOT NULL,
    clause_text         TEXT NOT NULL,
    waiting_period_days INT NULL CHECK (waiting_period_days IS NULL OR waiting_period_days > 0),
    sub_limit_amount    NUMERIC(14,2) NULL CHECK (sub_limit_amount IS NULL OR sub_limit_amount > 0),
    co_pay_percent      NUMERIC(5,2) NULL CHECK (co_pay_percent IS NULL OR co_pay_percent >= 0),
    effective_from      DATE NOT NULL,
    effective_to        DATE NULL,
    sort_order          INT NOT NULL DEFAULT 0,
    UNIQUE (product_code, clause_ref, effective_from),
    -- Each typed column is populated exactly for its own type.
    CHECK (
        (clause_type = 'WAITING_PERIOD' AND waiting_period_days IS NOT NULL
            AND sub_limit_amount IS NULL AND co_pay_percent IS NULL)
        OR (clause_type = 'SUB_LIMIT' AND sub_limit_amount IS NOT NULL
            AND waiting_period_days IS NULL AND co_pay_percent IS NULL)
        OR (clause_type = 'CO_PAY' AND co_pay_percent IS NOT NULL
            AND waiting_period_days IS NULL AND sub_limit_amount IS NULL)
        OR (clause_type IN ('COVERAGE', 'EXCLUSION', 'CONDITION', 'DEFINITION')
            AND waiting_period_days IS NULL AND sub_limit_amount IS NULL
            AND co_pay_percent IS NULL)
    ),
    -- A superseded clause must end after it starts; open-ended is NULL.
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- Non-product FK rows resolve against the catalogue (a clause for a cover the
-- product does not sell is a data bug). Product-level rows (cover_code NULL)
-- skip it. Enforced per row via trigger (a partial composite FK is not
-- expressible declaratively).
CREATE OR REPLACE FUNCTION trg_policy_clause_cover() RETURNS trigger AS $$
BEGIN
    IF NEW.cover_code IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM product_cover pc
                       WHERE pc.product_code = NEW.product_code
                         AND pc.cover_code = NEW.cover_code) THEN
        RAISE EXCEPTION 'policy_clause: (%, %) is not a catalogued cover',
            NEW.product_code, NEW.cover_code;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_policy_clause_cover ON policy_clause;

CREATE TRIGGER trg_policy_clause_cover
    BEFORE INSERT OR UPDATE OF product_code, cover_code ON policy_clause
    FOR EACH ROW EXECUTE FUNCTION trg_policy_clause_cover();

-- Read path: clauses for a product (+cover filter) by type, then ordered.
CREATE INDEX idx_policy_clause_lookup
    ON policy_clause (product_code, cover_code, clause_type);
