-- Slice 2 (assignment & the adjuster queue): the internal staff cache plus the claim
-- columns assignment writes, and the queue indexes.
--
-- app_user mirrors the plan's staff cache. Identity + roles still live in Keycloak; the
-- `level` column here is a deliberate schema addition beyond the plan model: assignment
-- must pick L1 vs L2 adjusters at claim time without an admin-API call to Keycloak, so the
-- seeded rows carry the adjuster's level copied from their provisioned Keycloak role
-- (see docs/decisions.md — app_user carries the routing level).
--
-- keycloak_sub values are FIXED and match the user ids imported from
-- keycloak/realm-export.template.json (adjuster.one / adjuster.two = L1, adjuster.three = L2),
-- so "own queue" lookups by JWT subject work in dev and E2E alike.

CREATE TABLE app_user (
    id           BIGSERIAL PRIMARY KEY,
    keycloak_sub VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    email        VARCHAR(200) NOT NULL,
    level        VARCHAR(2)   NOT NULL CHECK (level IN ('L1', 'L2'))
);

ALTER TABLE claim
    ADD COLUMN assigned_adjuster_id BIGINT REFERENCES app_user (id),
    ADD COLUMN assigned_at TIMESTAMPTZ;

-- Queue + load-balance queries read by assignee and by open status.
CREATE INDEX idx_claim_assigned_adjuster_id ON claim (assigned_adjuster_id);
CREATE INDEX idx_claim_status               ON claim (status);

INSERT INTO app_user (keycloak_sub, display_name, email, level) VALUES
    ('10000000-0000-0000-0000-000000000001', 'Priya Sharma',  'priya.sharma@claims.test',  'L1'),
    ('10000000-0000-0000-0000-000000000002', 'Marcus Webb',   'marcus.webb@claims.test',   'L1'),
    ('10000000-0000-0000-0000-000000000003', 'Ines Kowalski', 'ines.kowalski@claims.test', 'L2');
