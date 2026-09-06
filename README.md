# Claims Processing System

Slices 0–3 of the plan in `docs/plan.md` are done, green, and reviewed. The system today
covers the claimant journey end to end so far: a claimant can **register (Keycloak), file
a claim (FNOL) with photos and remarks against a seeded policy**, and immediately get a
claim number; the claim is classified L1/L2, **assigned to the least-loaded adjuster of
its level** (with an assignment email naming the adjuster), and moves to under review.
An adjuster (or supervisor) then **works the claim from a queue** — full internal view with
policy/coverage, **reserve** and **internal notes** that are structurally invisible to the
claimant (the visibility wall), and photo downloads. Claimants track their own claim on a
status screen.

`docs/progress.md` is the source of truth for status, tests, and the next slice
(4 — decision & the authority gate).

```
Angular SPA (frontend/)  --OIDC/PKCE-->  Keycloak (:8090)
       |  --/api (Bearer JWT)-->  Spring Boot (backend/)  --JDBC-->  PostgreSQL 16
       |                                                              (claims, claims_e2e)
       +-- Playwright E2E (e2e/) ----+          email --> Mailpit (:1025 SMTP / :8025 UI)
```

## Layout

| Path | What |
|---|---|
| `backend/` | Spring Boot (Java 21, Maven), Spring Data JPA + Flyway, OIDC resource server. Public `GET /api/policies`; claimant surface (`POST /api/claims` FNOL, `GET /api/claims/{n}` status); internal surface (`GET /api/queue`, `GET /api/claims/{n}/full`, `PUT .../reserve`, `POST .../notes`, `GET .../attachments/{id}`) with role + per-claim authorization (404 for non-assignees). |
| `frontend/` | Angular 22 SPA, `keycloak-js`. Home (public), FNOL form + claim status screen (claimant), queue + claim detail screens (adjuster/supervisor). |
| `e2e/` | Playwright, 6 journeys. Boots the backend on the dedicated `claims_e2e` DB. |
| `keycloak/` | Realm export (`claims` realm: self-registration, roles, public SPA client, three provisioned adjuster users with fixed subjects). |
| `docker/` | DB bootstrap (creates `claims_e2e`). |
| `docker-compose.yml` | Postgres 16, Mailpit, Keycloak 26. |
| `.github/workflows/ci.yml` | Backend (Testcontainers), frontend build, E2E (full compose stack). |
| `api.http` | REST Client examples for every endpoint. |

## Prerequisites

- Java 21 (JDK 24 works — builds target release 21), Maven 3.9+, Node 22 + npm, Docker.

## Run it (canonical path)

```bash
# 1. Stack: Postgres ("claims" + "claims_e2e"), Mailpit, Keycloak (realm "claims")
docker compose up -d --wait db mailpit keycloak

# 2. Install frontend dependencies (first time or after pulls)
npm ci --prefix frontend

# 3. Backend on http://localhost:8081 — Flyway migrates on boot (V1..V5 + seeds)
mvn -f backend/pom.xml spring-boot:run

# 4. Frontend on http://localhost:4200 (dev server proxies /api -> :8081)
npm --prefix frontend start
```

Open http://localhost:4200. Claimant: **File a claim** → register in Keycloak
(self-registered users are `claimant`) → FNOL against seeded policy `POL-10001`
(Ada Lovelace / ada.lovelace@example.test) or `POL-20002` (AUTO, routes L2) → claim number
immediately → **Track this claim** for the status screen. The FNOL and assignment emails
(assignee named) land in Mailpit at http://localhost:8025. Internal: **Adjuster queue** →
sign in as `adjuster.one`/`adjuster.two` (L1) or `adjuster.three` (L2), password
`adjuster-Pass-123` → open a claim and set a reserve, add notes, download photos.
Keycloak admin console: http://localhost:8090 (`admin`/`admin`).
Ports: backend **8081** and Keycloak **8090** because 8080 on this machine is taken.

## Migrations

Flyway runs on backend boot: empty database → current in one step. Migrations:
`backend/src/main/resources/db/migration/`. Slice-1 tables (`claim`, `attachment`,
`authority_config`) are intentionally vertical — later slices add their columns via new
migrations (see `docs/decisions.md`).

## Tests

```bash
# Unit + integration (real Postgres + Mailpit via Testcontainers):
mvn -f backend/pom.xml test

# E2E — start the stack once, install Chromium once, then:
docker compose up -d --wait db mailpit keycloak
npx --prefix e2e playwright install chromium
npm --prefix e2e test
```

Expected results: backend **56 tests** (18 unit + 38 integration) and **6 E2E journeys**
— all green. E2E never touches the dev `claims` database: Playwright boots the backend on
port 8082 against `claims_e2e` (the dev backend stays on 8081).

## CI

`.github/workflows/ci.yml` on every push/PR:
1. **backend** — `mvn test` (unit + integration, Testcontainers Postgres + Mailpit)
2. **frontend** — `npm ci && npm run build`
3. **e2e** — starts the compose stack (db + Mailpit + Keycloak), waits for the realm, runs
   Playwright

Nothing merges red. Lint/format gating is deferred until a feature slice adopts ESLint.

## From a fresh clone

```bash
git clone <repo> && cd <repo>
docker compose up -d db mailpit keycloak
npm ci --prefix frontend && npm ci --prefix e2e
mvn -f backend/pom.xml test
npx --prefix e2e playwright install chromium
npm --prefix e2e test
```

> First boot on a machine whose `pgdata` volume predates slice 1: `docker compose down -v`
> once so the init script can create `claims_e2e`.

## Status

Slices 0–3 done, green, and reviewed (slice 0 externally; slices 1–3 by fresh-context
review agents, fixes applied). Slice-2 and slice-3 CI runs passed all three jobs on push.
Next: slice 4 (decision & the authority gate).
`docs/progress.md` is the source of truth for where the project stands and what is next.
