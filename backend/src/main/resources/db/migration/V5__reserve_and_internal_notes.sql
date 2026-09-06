-- Slice 3 (adjuster works the claim & the visibility wall).
--
-- reserve_amount: the adjuster's internal estimate. NOT authority-gated (plan: only the
-- indemnity/payment is gated) and never visible to a claimant. The plan model puts the
-- reserve on claim; decision columns arrive with their slices.
--
-- internal_note: adjuster notes on a claim, never claimant-visible. author_id is nullable
-- because a SUPERVISOR role token may not have an app_user (staff-cache) row — their
-- identity is still carried by the audit/creator path where applicable (see
-- docs/decisions.md, slice-3 note).

ALTER TABLE claim ADD COLUMN reserve_amount NUMERIC(14, 2);

CREATE TABLE internal_note (
    id         BIGSERIAL PRIMARY KEY,
    claim_id   BIGINT       NOT NULL REFERENCES claim (id),
    author_id  BIGINT       REFERENCES app_user (id),
    body       TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_internal_note_claim_id ON internal_note (claim_id);
