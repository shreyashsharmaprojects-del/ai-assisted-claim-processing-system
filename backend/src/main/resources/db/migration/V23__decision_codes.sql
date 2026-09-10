-- V23 (V3 S8: structured decisions + audit export).
--
-- Denial codes on per-cover rejects. Codes are a Java enum (DenialReason, no
-- lookup table this slice):
-- NOT_COVERED | EXCLUDED_PER_CLAUSE | ABOVE_SUB_LIMIT_EXHAUSTED |
-- INSUFFICIENT_EVIDENCE | DUPLICATE_PRE_EXISTING | FRAUD_SUSPECTED_REFERRAL | OTHER
ALTER TABLE claim_cover ADD COLUMN denial_reason VARCHAR(60) NULL;
