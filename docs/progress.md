# Progress

Last updated: 2026-09-07

This file exists so a new session can pick up cold. Write it for someone who has never
seen this project. Rewrite it, don't append to it.

## Right now

**All eight plan slices (0–7) are built, reviewed, and green, and the pre-ship hardening
pass (phase 06) is complete.** The whole app passed the `phases/06-harden.md` checklist —
loading/empty/error/retry states, double-submit guards, access-control re-testing,
CSRF/CORS, dependency audit, rate limiting, accessibility, pagination/N+1, health check,
`.env.example`, backups/rollback, and README. The load-bearing rules were re-verified and
remain green (140 backend tests + 11 E2E journeys; CI structure unchanged).

What the hardening pass changed (see `docs/decisions.md`, 2026-09-07 hardening entry for
the full three-list report — fixed / needs-a-decision / deliberately accepted):

- **Dev credentials moved to environment variables** (the slice-2/3 deferred item). Postgres
  (`DB_USERNAME`/`DB_PASSWORD`) and Keycloak bootstrap admin (`KEYCLOAK_ADMIN_USERNAME`/
  `KEYCLOAK_ADMIN_PASSWORD`) are read from env by `docker-compose.yml` and
  `application.properties`; the provisioned realm users' passwords (`ADJUSTER_PASSWORD`,
  `SUPERVISOR_PASSWORD`) render into a **gitignored** `keycloak/realm-export.json` from the
  committed `keycloak/realm-export.template.json` via `keycloak/render-realm.mjs`. A
  committed `.env.example` lists every variable; `npm run setup` copies it to `.env` and
  renders the realm. Git history was checked — the old dev-only values still exist in
  history (see the hardening entry for the "rewrite history?" decision).
- **`GET /api/health`** public liveness endpoint (no DB check — readiness is
  `/api/policies`), integration-tested in `PolicyApiIntegrationTest` (now 2 tests).
- **Frontend hardening:** every fetch screen has a Loading state and a Try-again retry;
  reserve/note/decision/authority-save got double-submit guards; the reserve form no longer
  submits 0/NaN on a blank box and renders amounts with two decimals; a Sign-out link was
  added; the "Adjuster queue" nav link is hidden from the public; `:focus-visible` outline
  and muted-text contrast were fixed for WCAG AA.

## Run it (canonical — Docker)

```bash
npm run setup                                   # once: .env.example -> .env + render the realm
docker compose up -d --wait db mailpit keycloak # Postgres :5432 (claims + claims_e2e),
                                                # Mailpit :1025/:8025, Keycloak :8090
npm run backend                                 # sources .env; backend -> :8081 (Flyway migrates)
npm --prefix frontend start                     # frontend -> :4200
```

Open http://localhost:4200. Claimant: **File a claim** → register in Keycloak
(self-registered users get `claimant`) → FNOL against seeded `POL-10001` (Ada Lovelace /
ada.lovelace@example.test) or `POL-20002` (AUTO → L2) → claim number immediately, then
**Track this claim** → the status screen (steps only — no reserve/notes ever; an escalated
claim shows the Escalated step; a **closed** claim shows the decision). Internal:
**Adjuster queue** → sign in as a provisioned adjuster (`adjuster.one`/`adjuster.two` = L1,
`adjuster.three` = L2, password = `ADJUSTER_PASSWORD` from `.env`) → **Open claim** → full
internal view with coverage, reserve form, notes box, photo downloads, decision panel.
**Supervisor** (username `supervisor`, password `SUPERVISOR_PASSWORD`): **Escalations** →
escalation queue → **Review claim** → approve/deny with a rationale; **Authority settings**
→ edit each product's route level + L1/L2 limits. Health: `GET /api/health`. (The aging job
fires only at 03:00; the audit view and reassign endpoints are API-only — see `api.http`.)

## Tests

```bash
npm run backend:test     # 140: 54 unit + 86 integration (Testcontainers, or claims_test fallback)
npm --prefix frontend run build
docker compose up -d --wait db mailpit keycloak   # once (after npm run setup)
JAVA_HOME=/usr/lib/jvm/jdk-21.0.8-oracle-x64 npm run e2e   # boots backend :8082 + Angular dev server
```

## Slices

| # | Slice | Status | Reviewed |
|---|---|---|---|
| 0 | Walking skeleton | done | yes (external 2026-09-03) |
| 1 | FNOL & claim number | done | yes |
| 2 | Assignment & adjuster queue | done | yes |
| 3 | Adjuster works the claim & the visibility wall | done | yes |
| 4 | Decision & the authority gate | done | yes |
| 5 | Supervisor escalation & aging | done | yes |
| 6 | Claimant decision & notification | done | yes |
| 7 | Compliance & admin | done | yes |
| — | **Phase 06 — pre-ship hardening** | **done (2026-09-07)** | — |

## Blocked on

- Nothing. The approved slice list is complete and the hardening pass is done. Two items are
  deferred to the user for a decision (see `docs/decisions.md`, 2026-09-07 hardening entry):
  whether to rewrite git history to purge the pre-hardening dev-only credentials, and
  whether to add a per-claimant FNOL/email rate limit. Neither blocks a dev deployment.

## Notes for whoever picks this up

- Docs: concept `docs/claims-product-concept.md`; requirements `docs/requirements.md`;
  plan `docs/plan.md`; decisions `docs/decisions.md` (2026-09-07 hardening entry on top).
- Load-bearing rules (all regression-tested, still green): the **visibility wall**
  (reserve/notes/assignee/coverage never reach claimant surfaces, including CLOSED), the
  **404-not-403** rule (cross-tenant/non-assignee), the **authority gate** (L1 < L2 <
  supervisor; config read fresh per decision — no cache), **single-payment/closure
  atomicity**, the **role-driven queue**, the **aging ladder**, and **audit-log
  immutability** (V7 DB trigger).
- **Credentials:** env-var driven now (see `README.md`). The realm lives as a *template*
  (`keycloak/realm-export.template.json`) with `__ADJUSTER_PASSWORD__`/`__SUPERVISOR_PASSWORD__`
  placeholders; `keycloak/render-realm.mjs` writes the gitignored `realm-export.json` that
  compose imports (single-file mount). The realm-sync seam test reads the template (identity
  fields only, no passwords). `backend/src/test/resources/application.properties` must NOT
  exist (shadows main properties). `application.properties` datasource creds are
  `${DB_USERNAME}`/`${DB_PASSWORD}` (no default) — tests are shielded because
  `TestcontainersConfiguration` supplies its own `DataSource` bean, so the placeholders are
  never resolved in-test.
- The supervisor has **no app_user row**; audit/reassign rows carry the Keycloak subject as
  actor. Reassign reuses `ClaimAssigner` after the slice-4 `FOR UPDATE` claim lock.
- Integration classes each boot their own Testcontainers Postgres/Mailpit (Docker is now
  available in this environment) or fall back to `TEST_DB_URL` (`claims_test`). `authority_config`
  is a seed table, never truncated; tests that edit it (or delete an adjuster) restore in
  `finally`. Postgres jsonb normalizes numeric scale (1500.00 → 1500.0) — don't assert
  trailing-zero scales on jsonb payloads.
- E2E journey 9 owns AUTO (`POL-20002`); never point config edits at HOME (journeys 1–8
  assume HOME routes L1 with 2500/10000 limits). `queueShows` in `queue.spec.ts` waits for a
  terminal queue state before counting rows. E2E reads `ADJUSTER_PASSWORD`/`SUPERVISOR_PASSWORD`
  from env (`requiredEnv`), loaded from `../.env` by an inline loader in `playwright.config.ts`
  (no dotenv dependency); CI injects the same vars as job-level env.
- Frontend pages each carry `data-testid`s and a `loading`/`error` + retry pattern; forms
  disable their submit buttons during in-flight requests (double-submit guards).
