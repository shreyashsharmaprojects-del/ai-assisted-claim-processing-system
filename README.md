# OpenClaimFlow  (AI Assisted Claim Processing System)

![CI](https://github.com/shreyashsharmaprojects-del/ai-assisted-claim-processing-system/actions/workflows/ci.yml/badge.svg)
![Backend tests](https://img.shields.io/badge/backend-299%2F299-green)
![E2E](https://img.shields.io/badge/E2E-Plawright-green)
![Java](https://img.shields.io/badge/Java-21-orange)
![Angular](https://img.shields.io/badge/Angular-22-red)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)

**OpenClaimFlow** is a complete, production-grade **insurance claim processing system**:
claimants file losses online (FNOL — First Notice of Loss), the claim is auto-routed to
the least-loaded qualified adjuster, adjusters assess it through a staged workflow
(review → verification → assessment → decision), and supervisors oversee the whole book
(escalations, aging, authority limits, audit, privacy). Built with an
**Angular 22** single-page app, a **Spring Boot 3 (Java 21)** REST API,
**PostgreSQL 16** with Flyway migrations, **Keycloak** SSO (OIDC/PKCE), and
**Playwright** end-to-end tests.

> 📕 **Full visual demo:** [`demo/OpenClaimFlow-Complete-Demo.pdf`](demo/OpenClaimFlow-Complete-Demo.pdf)
> — 59 pages covering every role and every feature (45 live screenshots + 11
> capability deep-dives + system map). Start here if you are evaluating the project.

## ✨ Features

**Claimant journey**
- 2-step FNOL form (policy identity → loss details + per-cover picker with claimed amounts)
- Instant `CLM-` claim number; duplicate filings return the original number (HTTP 409)
- Plain-words status tracker (steps only — reserves, notes and assignees never leak)
- My-claims history with search + status filters; policy cockpit with per-cover
  sub-limit / claimed / remaining math; PDF + photo evidence upload

**Adjuster workspace**
- Mine-only work queue with age/SLA flags (On track · Due soon · Breaching), tabs, search + sort
- Claim work surface: facts, Review → Verification → Decision stepper, reserve + internal notes
- Staged assessment: per-cover review triage, verification records, send-back to claimant
  (claim parks; tracker shows what was asked), per-cover assessment with server-side
  guardrails, approve/reject/split decision grid
- **Authority gate** — inside your limit closes + pays; above it saves a proposal and
  refers upward; **optimistic locking** — concurrent edits get a conflict banner, never a
  silent overwrite (HTTP 409)
- **AI assistant drawer** — chat about the claim (waiting periods, sub-limits, wording)
  answered from the claim's own covers and clauses, with a Live/Fallback serving path;
  the per-cover advisory artifact stays gated until Decision
- **Policy clauses panel** — every clause in scope for the claim's product and covers,
  expandable to full wording; scoping is server-side (covers the claim does not carry
  never appear)
- **Policy in place** — either policy link opens the whole policy page in a modal
  (cover summary, covers with remaining limits, terms); File-a-claim is hidden for staff

**Supervisor console**
- Book-wide overview dashboard (open, exposure, approvals, aging) + escalations queue
- Mid-flight reassign, supervisor-only **claim reopen** with rationale + sequenced payments
- Policy book admin (create, CSV import, retire), editable **authority ladder** (L1/L2/L3/supervisor per product)
- Notification outbox (SENT/FAILED + retry), append-only **audit log** with one-click CSV export

**Platform (production-grade)**
| Capability | Detail |
|---|---|
| Evidence integrity | PDF + photo upload, magic-byte validation, SHA-256 + size per attachment |
| Object storage | Filesystem default, S3-compatible seam (MinIO dev, real S3 prod, zero code change) |
| Required documents | Per-product checklist per claim — link an upload or waive with rationale |
| Document versioning | Supersede (new version replaces old, timeline records it), full history kept |
| Structured decisions | 7 denial codes, rationale ≥ 20 chars, CSV export of audit + decisions |
| Privacy (GDPR) | Claimant self-export, supervisor anonymize, 7-year retention report |
| Notifications | Bell + unread count, in-app center, email/in-app/SMS prefs, outbox never loses mail |
| Locale & a11y | INR (`₹1,500.00`), en-GB dates, tenant timezone, full keyboard + screen-reader support |
| AI (DeepSeek) | Chat + per-cover advisory via OpenAI-wire REST (no SDK); `DEEPSEEK_API_KEY` empty → rules-only fallback, never a failure |

**Security by design** — Keycloak OIDC/PKCE; 404-not-403 on every cross-owner read;
hard claimant/carrier visibility wall (asserted in tests); rate-limited FNOL;
append-only audit log (no edit/delete endpoint exists).

## 🏗️ Tech stack

| Layer | Technology |
|---|---|
| Frontend | Angular 22 SPA, `keycloak-js`, Intl formatters, `frontend/` |
| Backend | Spring Boot 3, Java 21, Maven, Spring Data JPA + Flyway (V1–V30), `backend/` |
| Database | PostgreSQL 16 (`claims` dev, `claims_e2e` hermetic E2E) |
| Auth | Keycloak 26, OIDC/PKCE, realm `claims` |
| Email | Mailpit (SMTP :1025, UI :8025), transactional outbox pattern |
| E2E | Playwright, hermetic (own backend :8082 + own `ng serve`, per-run unique data) |
| CI | GitHub Actions — backend tests + frontend build + full E2E stack |

```
Angular SPA (frontend/)  --OIDC/PKCE-->  Keycloak (:8090)
       |  --/api (Bearer JWT)-->  Spring Boot (backend/)  --JDBC-->  PostgreSQL 16
       |                                                              (claims, claims_e2e)
       +-- Playwright E2E (e2e/) ----+          email --> Mailpit (:1025 SMTP / :8025 UI)
```

## 📁 Project structure

| Path | What |
|---|---|
| `frontend/` | Angular 22 SPA, `keycloak-js`. Home, FNOL form, claimant status + my-claims, adjuster queue, claim detail (AI drawer, clauses, policy modal), escalations, supervisor overview, authority settings, notifications, staff + privacy admin. |
| `backend/` | Spring Boot (Java 21, Maven), Spring Data JPA + Flyway (V1–V30), OIDC resource server. Claimant surface (FNOL, `/api/claims/mine`), internal work surface, staged workflow, decision/gate, escalation + aging, supervisor admin (reassign, reopen, dashboard, authority config, audit export, policy book, email outbox, metrics), staff, privacy, notifications, AI chat/advisory (DeepSeek), policy clauses. |
| `e2e/` | Playwright (18 spec files) against the dedicated `claims_e2e` DB. `e2e/shots/` + `shots.spec.ts` capture the demo screenshots; `new-shots.spec.ts` captures the AI/clauses/modal pages. |
| `demo/` | [`OpenClaimFlow-Complete-Demo.pdf`](demo/OpenClaimFlow-Complete-Demo.pdf) (59-page visual demo), [`OpenClaimFlow-Demo.pdf`](demo/OpenClaimFlow-Demo.pdf) (46-page journey), [`DEMO-PROMPT.md`](demo/DEMO-PROMPT.md) (reusable demo-generation prompt), `build_pdf.py` + `build_full_demo_pdf.py` (PDF builders). |
| `keycloak/` | Realm template + `render-realm.mjs` (passwords render from env, see below). |
| `scripts/` | `demo-seed.sql` / `demo-reset.sql` — sales-demo book (dev `claims` DB only). |
| `docker/` | DB bootstrap (creates `claims_e2e`). |
| `docker-compose.yml` | Postgres 16, Mailpit, Keycloak 26 (+ MinIO for S3 dev). |
| `.github/workflows/ci.yml` | Backend (Testcontainers), frontend build, E2E (full compose stack). |
| `api.http` | REST Client examples for every endpoint. |
| `docs/` | `progress.md` (status source of truth), `plan-v3.md` (S1–S11 specs), `decisions.md` (decision log), `operations.md` (S3 + timezone runbook). |

## 🚀 Quickstart

**Prerequisites:** Java 21, Maven 3.9+, Node 22 + npm, Docker.

```bash
git clone https://github.com/shreyashsharmaprojects-del/ai-assisted-claim-processing-system.git
cd ai-assisted-claim-processing-system
npm run setup        # copies .env.example -> .env and renders keycloak/realm-export.json
npm run db:up        # Postgres + Mailpit + Keycloak (realm "claims"), waits for health
npm ci --prefix frontend
npm run backend      # Spring Boot on http://localhost:8081 (Flyway migrates V1..V30 on boot)
npm --prefix frontend start   # Angular on http://localhost:4200 (proxies /api -> :8081)
```

Credentials are **never committed** — `.env.example` lists every variable
(`DB_USERNAME`, `DB_PASSWORD`, `KEYCLOAK_ADMIN_*`, `ADJUSTER_PASSWORD`,
`SUPERVISOR_PASSWORD`, `CLAIMANT_PASSWORD`, `DEEPSEEK_API_KEY`,
`DEEPSEEK_MODEL`, `DEEPSEEK_BASE_URL`). Values in `.env` are DEV-ONLY for the
throwaway local stack. Leave `DEEPSEEK_API_KEY` empty to run the AI surfaces on
the rules-only fallback (no live LLM calls).

**Sign in at http://localhost:4200:**
- **Claimant:** `ada.lovelace` (POL-10001, HLTH-PLUS ₹10L) · `grace.hopper`
  (POL-20002, AUTO) · plus `ravi.menon`, `fatima.khan`, `david.dsouza`,
  `lakshmi.iyer`, `arjun.nair`, `kavya.reddy`, `vikram.rao` — all with
  `CLAIMANT_PASSWORD` (`claims-Pass-123` default). File → get a `CLM-` number →
  Track this claim (steps only, never reserves/notes).
- **Adjuster:** `adjuster.one` / `adjuster.two` (L1, ₹1L) or `adjuster.three`
  (L2, ₹4L) with `ADJUSTER_PASSWORD` — the workspace is the queue only.
- **Supervisor:** `supervisor` with `SUPERVISOR_PASSWORD` → overview, escalations,
  staff, authority, privacy, outbox.
- Mailpit UI: http://localhost:8025 · Keycloak admin: http://localhost:8090 ·
  health: `GET http://localhost:8081/api/health`.

> First boot on a machine whose `pgdata` volume predates slice 1:
> `docker compose down -v` once so the init script can create `claims_e2e`.

## 🧪 Tests

```bash
npm run backend:test   # unit + integration: 299/299 green (Testcontainers Postgres+Mailpit,
                       # or claims_test DB fallback when Docker is unavailable)
npm run db:up
npx --prefix e2e playwright install chromium
npm run e2e            # hermetic Playwright: own backend :8082 + ng serve, claims_e2e truncated
```

Backend **299/299** green · frontend build green · hermetic E2E green across
`fnol`, `covers`, `queue`, `staged`, `conflict`, `reopen`, `staff`,
`structured-decision`, `privacy`, `notifications`, `need-info-docs`,
`ai-chat`, `clauses`.
E2E never touches the dev `claims` database. `docs/progress.md` is the source of
truth for status; `docs/decisions.md` logs every slice decision (S1–S11).

## 🎬 Demo

```bash
npm run demo:seed    # 6 POL-DEMO-* policies + claims at each ladder rung
                     # (UNASSIGNED → UNDER_REVIEW → ESCALATED_SUPERVISOR →
                     # CLOSED approved + denied), wall-safe demo markers
npm run demo:reset   # deletes all demo rows (audit_log rows stay: append-only)
```

Screenshots: `npx playwright test --config shots.config.ts` from `e2e/` (after
`npm run demo:seed`) → `e2e/shots/` → then `npx playwright test --config
new-shots.config.ts` for the AI/clauses/modal pages → `python3
demo/build_full_demo_pdf.py` rebuilds the 59-page PDF (or `build_pdf.py` for the
46-page journey). See `demo/DEMO-PROMPT.md` for the reusable prompt.

## 🗄️ Migrations

Flyway runs on backend boot: empty database → current in one step (V1–V30).
`backend/src/main/resources/db/migration/`. Tables are intentionally vertical; later
slices add columns via new migrations — V1–V17 are never edited
(see `docs/decisions.md`).

## 🔁 CI / Deploy / Backup

- **CI:** `.github/workflows/ci.yml` on every push/PR — backend tests, frontend
  build, full-stack E2E (realm rendered from env first). Nothing merges red.
- **Deploy:** standard pieces — Spring Boot jar (`backend/`), Angular static build
  (`frontend/dist/`), Postgres/Keycloak/Mailpit containers — with production
  credentials injected via environment (never committed).
- **Backup:** `pg_dump` the `claims` DB + archive `claims.uploads.dir` (on-disk
  evidence). Restore with `pg_restore`, then re-point `SPRING_DATASOURCE_URL`.
- **Rollback:** redeploy the previous image/commit. Flyway is forward-only
  (audit log is immutable — no down-migrations); schema rollback = restore a
  pre-migration backup. Backend logs to stdout, free of secrets and PII.

## 📜 Status

All plan slices S1–S11 (plan V3) are built, tested and committed on top of the
V1 + V2 foundation — 11/11 done. `docs/progress.md` is the source of truth.
