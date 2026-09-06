-- Runs on first boot of the compose Postgres container (docker-entrypoint-initdb.d).
-- Creates the dedicated E2E database. The E2E backend (Playwright) points at
-- claims_e2e so journeys that create data never touch the dev `claims` database.
CREATE DATABASE claims_e2e;
