-- Slice 4 (decision & the authority gate): the money columns, the config thresholds the
-- gate evaluates, and the one-payment-per-claim table.
--
-- claim.decision/decision_remarks/indemnity_amount/closed_at: the decision slice writes
-- them at closure. decision_remarks is claimant-visible (denials show it); the internal
-- notes never are (the visibility wall holds on CLOSED claims). indemnity_amount is
-- NUMERIC(14,2), same bound/scale discipline as reserve_amount.
--
-- authority_config.l1_limit_amount/l2_limit_amount: the monetary authority thresholds
-- (the "amount" parameters), evaluated for the first time here when the indemnity figure
-- is known. The plan model carries them on authority_config; existing seeded rows (HOME,
-- AUTO) get values now. Limits are per-claim amounts (no aggregate exposure cap in v1).
--
-- payment: one per claim, amount always == claim.indemnity_amount (unique claim_id
-- enforces exactly one), authorized_by = the deciding adjuster (app_user cache row).

ALTER TABLE claim
    ADD COLUMN decision          VARCHAR(10),  -- APPROVED | DENIED; nullable until closure
    ADD COLUMN decision_remarks  TEXT,
    ADD COLUMN indemnity_amount  NUMERIC(14, 2),
    ADD COLUMN closed_at         TIMESTAMPTZ;

-- The thresholds arrive nullable so the seeded rows can be backfilled before the columns
-- are tightened (the table already holds HOME/AUTO rows).
ALTER TABLE authority_config
    ADD COLUMN l1_limit_amount NUMERIC(14, 2),
    ADD COLUMN l2_limit_amount NUMERIC(14, 2);

-- Seed thresholds for the two seeded products. A claim is decided by the level that holds
-- it: L1 adjusters may approve up to l1_limit_amount, L2 up to l2_limit_amount; above the
-- acting level the claim escalates (to L2 when within l2_limit_amount, else supervisor).
UPDATE authority_config SET l1_limit_amount = 2500.00, l2_limit_amount = 10000.00
    WHERE product_code IN ('HOME', 'AUTO');

ALTER TABLE authority_config
    ALTER COLUMN l1_limit_amount SET NOT NULL,
    ALTER COLUMN l2_limit_amount SET NOT NULL;

CREATE TABLE payment (
    id               BIGSERIAL PRIMARY KEY,
    claim_id         BIGINT       NOT NULL UNIQUE REFERENCES claim (id),
    amount           NUMERIC(14, 2) NOT NULL,
    authorized_by_id BIGINT       REFERENCES app_user (id),
    authorized_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
