# Claims Processing System

Slices 0–1 of the plan in `docs/plan.md`: the walking skeleton plus the first vertical
feature — a claimant can **register (Keycloak), file a claim (FNOL) with photos and
remarks against a seeded policy, and immediately get a claim number** back, with the FNOL
email sent and the claim classified L1/L2 from the product code's routing config.

```
Angular SPA (frontend/)  --OIDC/PKCE-->  Keycloak (:8090)
       |  --/api (Bearer JWT)-->  Spring Boot (backend/)  --JDBC-->  PostgreSQL 16
       |                                                              (claims, claims_e2e)
       +-- Playwright E2E (e2e/) ----+          email --> Mailpit (:1025 SMTP / :8025 UI)
```

## Layout

| Path | What |
|---|---|
| `backend/` | Spring Boot (Java 21, Maven), Spring Data JPA + Flyway, OIDC resource server. `GET /api/policies` (public), `POST /api/claims` (multipart FNOL, ROLE_CLAIMANT). |
| `frontend/` | Angular 22 SPA, `keycloak-js`. Home (public) + `/claim/new` FNOL form behind a claimant guard. |
| `e2e/` | Playwright. Skeleton page + journey 1 (Keycloak registration → FNOL with photo → claim number). Boots backend on the dedicated `claims_e2e` DB. |
| `keycloak/` | Realm export (`claims` realm: self-registration, roles, public SPA client). |
| `docker/` | DB bootstrap (creates `claims_e2e`). |
| `docker-compose.yml` | Postgres 16, Mailpit, Keycloak 26. |
| `.github/workflows/ci.yml` | Backend (Testcontainers), frontend build, E2E (full compose stack). |
| `api.http` | REST Client examples (policies GET; FNOL POST template). |

## Prerequisites

- Java 21 (JDK 24 works — builds target release 21), Maven 3.9+, Node 22 + npm, Docker.

## Run it (canonical path)

```bash
# 1. Stack: Postgres ("claims" + "claims_e2e"), Mailpit, Keycloak (realm "claims")
docker compose up -d --wait db mailpit keycloak

# 2. Install frontend dependencies (first time or after pulls)
npm ci --prefix frontend

# 3. Backend on http://localhost:8081 — Flyway migrates on boot (V1..V3 + seeds)
mvn -f backend/pom.xml spring-boot:run

# 4. Frontend on http://localhost:4200 (dev server proxies /api -> :8081)
npm --prefix frontend start
```

Open http://localhost:4200. Click **File a claim** → register in Keycloak
(self-registered users are `claimant`) → submit FNOL against seeded policy
`POL-10001` (Ada Lovelace / ada.lovelace@example.test) or `POL-20002` (AUTO, routes L2).
You'll see the claim number immediately; the confirmation email is in Mailpit at
http://localhost:8025. Keycloak admin console: http://localhost:8090 (`admin`/`admin`).
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

Expected results: backend **25 tests** (12 unit + 13 integration) and **3 E2E journeys**
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

Slices 0–1 done, green, and reviewed (slice 0 externally; slice 1 by a fresh-context
review agent, fixes applied). `docs/progress.md` is the source of truth for where the
project stands and what is next.
