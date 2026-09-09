-- V17 (claim timeline: who said/attached what and when + verification-linked documents).
--
-- All additive; V1–V16 untouched.
--
-- attachment.uploaded_by_sub: the uploader's Keycloak subject (the holding adjuster, a
--   supervisor, or the claimant on a NEED_INFO response upload). NULL on pre-V17 rows,
--   which render as un-attributed in the timeline.
-- attachment.verification_id: optional link to the verification the document supports
--   (the adjuster's per-check attach). NULL = claim-level document.
-- internal_note.author_sub: the author's Keycloak subject. author_id stays the display
--   join, but supervisors have no app_user row (NULL author_id), so the subject is the
--   only identity on their notes.
ALTER TABLE attachment
    ADD COLUMN uploaded_by_sub VARCHAR(100) NULL,
    ADD COLUMN verification_id BIGINT NULL REFERENCES verification (id) ON DELETE SET NULL;

CREATE INDEX idx_attachment_verification_id ON attachment (verification_id);

ALTER TABLE internal_note
    ADD COLUMN author_sub VARCHAR(100) NULL;
