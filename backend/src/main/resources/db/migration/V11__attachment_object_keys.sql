-- V11 (R5 photo-storage seam): attachment.storage_path stops receiving absolute paths.
-- New rows store the portable object key ({claimId}/{uuid}{ext}); the base dir is resolved
-- at runtime by the PhotoStorage implementation (filesystem today, S3 later).
--
-- This migration converts legacy absolute-path rows to key form: everything from the last
-- two path segments ({claimId}/{file}) is kept, anything shorter is left untouched
-- (null-safe: NULL rows and already-key rows are never modified). Keys resolve under the
-- old upload dir too, so rollback-delete and downloads keep working during the switch.
UPDATE attachment
SET storage_path = regexp_replace(storage_path, '^.*([^/]+/[^/]+)$', '\1')
WHERE storage_path IS NOT NULL
  AND storage_path LIKE '/%'
  AND storage_path LIKE '%/%/%';
