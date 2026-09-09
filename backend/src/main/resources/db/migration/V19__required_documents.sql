-- V19 (V3 S3: required-documents checklist per product).
--
-- Per-product required documents (claim-level rows; cover_code NULL) plus the
-- per-claim checklist (claim_document_check, one row per required doc, seeded
-- PENDING at FNOL). Advisory only: nothing blocks decisions on incompleteness.
--
-- Product-code mapping (catalog V12 carries HOME/AUTO/HLTH-BASIC/HLTH-PLUS/
-- HLTH-CRIT/AUTO-STD/AUTO-COM/PROP-HOME/PROP-FIRE/HLTH-ORPHAN; the slice names
-- families HEALTH/AUTO/PROPERTY): HEALTH rows are duplicated per HEALTH product
-- code (HLTH-BASIC, HLTH-PLUS, HLTH-CRIT), AUTO rows per AUTO code (AUTO-STD,
-- AUTO-COM, plus legacy V1 AUTO which still files claims via POL-20002),
-- PROPERTY rows per PROPERTY code (PROP-HOME, PROP-FIRE, plus legacy V1 HOME).
-- HLTH-ORPHAN gets no rows (deliberately unmapped product — empty checklist).
-- The UNIQUE-with-COALESCE from the slice is a unique index (Postgres does not
-- allow expressions in UNIQUE constraints).

CREATE TABLE required_document (
    id BIGSERIAL PRIMARY KEY,
    product_code VARCHAR(40) NOT NULL,
    cover_code VARCHAR(40) NULL,
    doc_key VARCHAR(60) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_required_document_product_cover_key
    ON required_document (product_code, (COALESCE(cover_code, '-')), doc_key);

CREATE TABLE claim_document_check (
    id BIGSERIAL PRIMARY KEY,
    claim_id BIGINT NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    required_document_id BIGINT NOT NULL REFERENCES required_document (id),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','RECEIVED','WAIVED')),
    attachment_id BIGINT NULL REFERENCES attachment (id) ON DELETE SET NULL,
    decided_by VARCHAR(100) NULL, decided_at TIMESTAMPTZ NULL,
    UNIQUE (claim_id, required_document_id)
);

CREATE INDEX idx_claim_doc_check_claim ON claim_document_check (claim_id);

-- HEALTH family: 3 claim-level docs per HEALTH product code.
INSERT INTO required_document (product_code, cover_code, doc_key, display_name, sort_order)
VALUES
    ('HLTH-BASIC', NULL, 'DISCHARGE_SUMMARY', 'Discharge summary', 1),
    ('HLTH-BASIC', NULL, 'FINAL_BILL', 'Final hospital bill', 2),
    ('HLTH-BASIC', NULL, 'ID_PROOF', 'Government-issued ID proof', 3),
    ('HLTH-PLUS', NULL, 'DISCHARGE_SUMMARY', 'Discharge summary', 1),
    ('HLTH-PLUS', NULL, 'FINAL_BILL', 'Final hospital bill', 2),
    ('HLTH-PLUS', NULL, 'ID_PROOF', 'Government-issued ID proof', 3),
    ('HLTH-CRIT', NULL, 'DISCHARGE_SUMMARY', 'Discharge summary', 1),
    ('HLTH-CRIT', NULL, 'FINAL_BILL', 'Final hospital bill', 2),
    ('HLTH-CRIT', NULL, 'ID_PROOF', 'Government-issued ID proof', 3);

-- AUTO family: 3 claim-level docs per AUTO product code (incl. legacy V1 AUTO).
INSERT INTO required_document (product_code, cover_code, doc_key, display_name, sort_order)
VALUES
    ('AUTO-STD', NULL, 'PHOTOS', 'Photos of the damage', 1),
    ('AUTO-STD', NULL, 'ESTIMATE', 'Repair estimate', 2),
    ('AUTO-STD', NULL, 'RC_COPY', 'Registration certificate copy', 3),
    ('AUTO-COM', NULL, 'PHOTOS', 'Photos of the damage', 1),
    ('AUTO-COM', NULL, 'ESTIMATE', 'Repair estimate', 2),
    ('AUTO-COM', NULL, 'RC_COPY', 'Registration certificate copy', 3),
    ('AUTO', NULL, 'PHOTOS', 'Photos of the damage', 1),
    ('AUTO', NULL, 'ESTIMATE', 'Repair estimate', 2),
    ('AUTO', NULL, 'RC_COPY', 'Registration certificate copy', 3);

-- PROPERTY family: 3 claim-level docs per PROPERTY product code (incl. legacy V1 HOME).
INSERT INTO required_document (product_code, cover_code, doc_key, display_name, sort_order)
VALUES
    ('PROP-HOME', NULL, 'PHOTOS', 'Photos of the damage', 1),
    ('PROP-HOME', NULL, 'ESTIMATE', 'Repair estimate', 2),
    ('PROP-HOME', NULL, 'OWNERSHIP_PROOF', 'Proof of ownership', 3),
    ('PROP-FIRE', NULL, 'PHOTOS', 'Photos of the damage', 1),
    ('PROP-FIRE', NULL, 'ESTIMATE', 'Repair estimate', 2),
    ('PROP-FIRE', NULL, 'OWNERSHIP_PROOF', 'Proof of ownership', 3),
    ('HOME', NULL, 'PHOTOS', 'Photos of the damage', 1),
    ('HOME', NULL, 'ESTIMATE', 'Repair estimate', 2),
    ('HOME', NULL, 'OWNERSHIP_PROOF', 'Proof of ownership', 3);
