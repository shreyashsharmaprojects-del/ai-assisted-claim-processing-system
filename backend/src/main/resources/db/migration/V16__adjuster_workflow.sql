-- V16 (adjuster-workflow tightening: default verification checklist, NEED_INFO at
-- every stage, labelled documents).
--
-- All additive; V1–V15 untouched. Existing verification rows (DIGITAL/PHYSICAL)
-- keep their values; the type CHECK only widens, so no data rewrite.

-- --- verification: the default checklist types ---------------------------------
-- DOCUMENT (paperwork check) and CLAUSE (policy-clause check) join DIGITAL and
-- PHYSICAL. The backend opens exactly PHYSICAL + DOCUMENT + CLAUSE on review
-- advance; extra DIGITAL/PHYSICAL rows stay allowed for follow-ups.
ALTER TABLE verification DROP CONSTRAINT IF EXISTS verification_type_check;
ALTER TABLE verification ADD CONSTRAINT verification_type_check CHECK (
    type IS NULL
    OR type IN ('DIGITAL', 'PHYSICAL', 'DOCUMENT', 'CLAUSE'));

-- --- NEED_INFO from the decision stage ------------------------------------------
-- need_info_prior_stage may now also name DECISION (V15 allowed REVIEW and
-- VERIFICATION only). The CHECK widen is data-safe: existing values are kept.
ALTER TABLE claim DROP CONSTRAINT IF EXISTS chk_claim_need_info_prior_stage;
ALTER TABLE claim ADD CONSTRAINT chk_claim_need_info_prior_stage CHECK (
    need_info_prior_stage IS NULL
    OR need_info_prior_stage IN ('REVIEW', 'VERIFICATION', 'DECISION'));

-- --- attachment: human label ------------------------------------------------------
-- A document label chosen from the dropdown or typed free-text (custom name).
-- NULL on pre-V16 rows (FNOL photos predate labelling); the download name stays
-- the stored original filename.
ALTER TABLE attachment
    ADD COLUMN label VARCHAR(120) NULL;
