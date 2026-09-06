-- Slice 7 (compliance & admin): audit-log immutability at the DATA layer.
--
-- The audit log was append-only by application discipline from slice 1 (the writer only
-- ever INSERTs; any correction is a new row). A trigger now makes UPDATE/DELETE impossible
-- for any caller — a future app bug or a manual edit cannot quietly rewrite history. This
-- is what the plan's "audit-log append-only (reject UPDATE/DELETE)" integration test pins.
--
-- TRUNCATE deliberately stays legal: it fires no row triggers and is the integration-test
-- reset path (ClaimTableResettingTest). No production code truncates the log.

CREATE FUNCTION audit_log_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: rows may be inserted, never updated or deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION audit_log_append_only();
