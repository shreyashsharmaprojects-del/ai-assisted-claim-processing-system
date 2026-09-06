-- Slice 0 (walking skeleton): the two tables the plan requires up front.
-- policy      : seeded, read-only; the skeleton page reads it (real DB -> backend -> browser).
-- audit_log   : laid down now per plan ("not a bolted-on slice"); the append-only writer
--               service that emits entries lands in slice 1 (the first write: claim created),
--               not with the first decision. Append-only is application-enforced (no
--               UPDATE/DELETE ever issued); corrections are new rows.

CREATE TABLE policy (
    id            BIGSERIAL PRIMARY KEY,
    policy_number VARCHAR(50)  NOT NULL UNIQUE,
    product_code  VARCHAR(20)  NOT NULL,
    holder_name   VARCHAR(200) NOT NULL,
    holder_email  VARCHAR(200) NOT NULL,
    coverage      JSONB        NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor_sub   VARCHAR(100),
    action      VARCHAR(50)  NOT NULL,
    entity_type VARCHAR(50)  NOT NULL,
    entity_id   BIGINT,
    before      JSONB,
    after       JSONB,
    rationale   TEXT, -- required on decision rows; null elsewhere
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_entity     ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_log_created_at ON audit_log (created_at);
