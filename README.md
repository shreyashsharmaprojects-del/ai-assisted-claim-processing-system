# Claims Processing System

A small single-carrier claims-handling system: a claimant reports a loss (FNOL) with
photos, the claim is classified L1/L2 and load-balanced to the least-loaded adjuster, an
adjuster works it (coverage, reserve, internal notes), and a decision closes it — enforced
by an authority gate (L1 < L2 < supervisor) and a hard visibility wall between carrier and
claimant. A supervisor sees the team queue, approves escalations, manages aging, edits the
authority table, and reads the immutable audit log.

All plan slices (0–7) are built, reviewed, and green: **140 backend tests** (54 unit + 86
integration) and **11 E2E journeys**. `docs/progress.md` is the source of truth for status.

```
Angular SPA (frontend/)  --OIDC/PKCE-->  Keycloak (:8090)
       |  --/api (Bearer JWT)-->  Spring Boot (backend/)  --JDBC-->  PostgreSQL 16
       |                                                              (claims, claims_e2e)
       +-- Playwright E2E (e2e/) ----+          email --> Mailpit (:1025 SMTP / :8025 UI)
```

## Layout

| Path | What |
|---|---|
| `backend/` | Spring Boot (Java 21, Maven), Spring Data JPA + Flyway, OIDC resource server. Claimant surface, internal work surface, decision/gate, escalation + aging, and supervisor admin (authority config, reassign, audit). |
| `frontend/` | Angular 22 SPA, `keycloak-js`. Home, FNOL form, claimant status, adjuster queue, claim detail, escalations, authority settings. |
| `e2e/` | Playwright (11 journeys) against the dedicated `claims_e2e` DB. |
| `keycloak/` | Realm template + `render-realm.mjs` (passwords render from env, see below). |
| `docker/` | DB bootstrap (creates `claims_e2e`). |
| `docker-compose.yml` | Postgres 16, Mailpit, Keycloak 26. |
| `.github/workflows/ci.yml` | Backend (Testcontainers), frontend build, E2E (full compose stack). |
| `api.http` | REST Client examples for every endpoint. |

## Prerequisites

- Java 21, Maven 3.9+, Node 22 + npm, Docker.

## Setup (first time)

Credentials are **never committed** — copy the example env file once and render the
Keycloak realm from its template:

```bash
npm run setup        # copies .env.example -> .env and renders keycloak/realm-export.json
```

`.env.example` lists every variable (`DB_USERNAME`, `DB_PASSWORD`,
`KEYCLOAK_ADMIN_USERNAME`, `KEYCLOAK_ADMIN_PASSWORD`, `ADJUSTER_PASSWORD`,
`SUPERVISOR_PASSWORD`). Edit `.env` if you want different local values; the values there
are DEV-ONLY for the throwaway local Postgres/Keycloak.

## Run it

```bash
# 1. Stack: Postgres ("claims" + "claims_e2e"), Mailpit, Keycloak (realm "claims").
#    Renders the realm from .env first.
npm run db:up

# 2. Install frontend dependencies (first time or after pulls)
npm ci --prefix frontend

# 3. Backend on http://localhost:8081 — Flyway migrates on boot (V1..V7 + seeds).
#    Sources .env so DB_USERNAME/DB_PASSWORD are set.
npm run backend

# 4. Frontend on http://localhost:4200 (dev server proxies /api -> :8081)
npm --prefix frontend start
```

Open http://localhost:4200. **Claimant:** File a claim → register in Keycloak
(self-registered users are `claimant`) → FNOL against seeded policy `POL-10001`
(Ada Lovelace / ada.lovelace@example.test) or `POL-20002` (AUTO → L2) → claim number
immediately → Track this claim for the status screen (steps only — never reserve/notes;
a closed claim shows the decision). **Adjuster:** sign in as `adjuster.one`/`adjuster.two`
(L1) or `adjuster.three` (L2) with the `ADJUSTER_PASSWORD` from `.env`. **Supervisor:**
sign in as `supervisor` with `SUPERVISOR_PASSWORD` → Escalations and Authority settings.
Keycloak admin console: http://localhost:8090 (`KEYCLOAK_ADMIN_USERNAME`/
`KEYCLOAK_ADMIN_PASSWORD`). Emails land in Mailpit at http://localhost:8025.

Ports: backend **8081** and Keycloak **8090** because 8080 on this machine is taken.
Health check: `GET http://localhost:8081/api/health` (public liveness).

## Migrations

Flyway runs on backend boot: empty database → current in one step.
`backend/src/main/resources/db/migration/`. Tables are intentionally vertical; later
slices add columns via new migrations (see `docs/decisions.md`).

## Tests

```bash
# Backend: unit + integration (real Postgres + Mailpit via Testcontainers, or a dedicated
# claims_test DB fallback when Docker is unavailable — see docs/decisions.md).
npm run backend:test

# E2E — start the stack once (renders the realm + waits for health), install Chromium once:
npm run db:up
npx --prefix e2e playwright install chromium
npm run e2e
```

Expected: backend **140 tests** (54 unit + 86 integration) and **11 E2E journeys** — all
green. E2E never touches the dev `claims` database: Playwright boots the backend on
port 8082 against `claims_e2e` (the dev backend stays on 8081).

## CI

`.github/workflows/ci.yml` on every push/PR: backend (`mvn test`), frontend build, and E2E
(full compose stack, realm rendered from env first). Nothing merges red. CI injects the
dev credentials as job-level env vars (overridable with GitHub secrets for any non-local
run).

## Deploy, backup, rollback

No hosting target is specified in `docs/requirements.md`, so there is no deployment
artifact beyond the standard pieces: a Spring Boot jar (`backend/`), an Angular static
build (`frontend/dist/`), and Postgres/Keycloak/Mailpit containers. Deploying means running
those three with production credentials injected via the environment (never committed).

- **Backup:** `pg_dump` the `claims` database (and archive `claims.uploads.dir`, the
  on-disk photo evidence). Restore with `pg_restore` into a fresh database, then re-point
  `SPRING_DATASOURCE_URL`.
- **Rollback:** redeploy the previous application image/commit. Flyway migrations are
  forward-only by design (the audit log is immutable, so no down-migrations); a schema
  rollback means restoring the database from a backup taken before the migration.
- **Logs:** the backend logs to stdout (read them from the container/orchestrator). Logs
  carry no passwords, tokens, or personal data — errors are logged server-side with a
  generic message returned to the client.

## From a fresh clone

```bash
git clone <repo> && cd <repo>
npm run setup
npm run db:up
npm ci --prefix frontend && npm ci --prefix e2e
npm run backend:test
npx --prefix e2e playwright install chromium
npm run e2e
```

> First boot on a machine whose `pgdata` volume predates slice 1: `docker compose down -v`
> once so the init script can create `claims_e2e`.

## Status

All plan slices (0–7) are done and reviewed; the pre-ship hardening pass is complete.
`docs/progress.md` is the source of truth for where the project stands.
