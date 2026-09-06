-- Slice 1 (FNOL): claim, attachment, authority_config.
--
-- Vertical slicing note: each table carries only the columns this slice writes. Columns
-- the approved plan specifies for later slices (assignment, reserve, decision, payment,
-- audit-writer support fields on claim; authority amount thresholds) arrive through later
-- migrations when those slices write them. `claim.claimant_remarks` is a schema addition
-- beyond the plan model — approved by the user (FNOL "remarks" need a home).

CREATE SEQUENCE claim_number_seq START 1;

CREATE TABLE authority_config (
    id           BIGSERIAL PRIMARY KEY,
    product_code VARCHAR(20) NOT NULL UNIQUE,
    route_level  VARCHAR(2)  NOT NULL CHECK (route_level IN ('L1', 'L2'))
);

CREATE TABLE claim (
    id               BIGSERIAL PRIMARY KEY,
    claim_number     VARCHAR(20) NOT NULL UNIQUE,
    policy_id        BIGINT      NOT NULL REFERENCES policy (id),
    claimant_sub     VARCHAR(100) NOT NULL,
    level            VARCHAR(2)  NOT NULL CHECK (level IN ('L1', 'L2')),
    status           VARCHAR(20) NOT NULL DEFAULT 'UNASSIGNED',
    loss_date        DATE        NOT NULL,
    loss_location    VARCHAR(200) NOT NULL,
    loss_description TEXT        NOT NULL,
    claimant_remarks TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_claim_policy_id  ON claim (policy_id);
CREATE INDEX idx_claim_claimant   ON claim (claimant_sub);
CREATE INDEX idx_claim_created_at ON claim (created_at);

CREATE TABLE attachment (
    id            BIGSERIAL PRIMARY KEY,
    claim_id      BIGINT       NOT NULL REFERENCES claim (id),
    storage_path  TEXT         NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_attachment_claim_id ON attachment (claim_id);

-- Authority routing seeds: every seeded policy's product code routes somewhere.
INSERT INTO authority_config (product_code, route_level) VALUES
    ('HOME', 'L1'),
    ('AUTO', 'L2');

-- A second seeded policy so L2 routing is exercisable end to end
-- (FNOL matching + classification) without hand-crafting rows.
INSERT INTO policy (policy_number, product_code, holder_name, holder_email, coverage)
VALUES ('POL-20002', 'AUTO', 'Grace Hopper', 'grace.hopper@example.test', '{"type":"auto","sum_insured":30000}'::jsonb);
