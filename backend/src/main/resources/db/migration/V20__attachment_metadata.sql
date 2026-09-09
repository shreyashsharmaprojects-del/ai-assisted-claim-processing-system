-- V20 (V3 S4: document metadata + supersede — versioning without a DMS).
--
-- All additive; V1–V19 untouched.
--
-- attachment.doc_type: advisory document type (mirrors required_document.doc_key
--   for checklist evidence, free for ad-hoc uploads). NULL on pre-V20 rows.
-- attachment.replaces_attachment_id: the prior attachment this upload supersedes
--   (re-upload chains). NULL = original. SET NULL on delete — history is
--   append-only, old bytes stay, the chain just loses the dead link.
ALTER TABLE attachment
    ADD COLUMN doc_type VARCHAR(60) NULL,
    ADD COLUMN replaces_attachment_id BIGINT NULL REFERENCES attachment (id) ON DELETE SET NULL;

CREATE INDEX idx_attachment_replaces ON attachment (replaces_attachment_id);
