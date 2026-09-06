# Progress

Last updated: 2026-09-06

This file exists so a new session can pick up cold. Write it for someone who has never
seen this project. Rewrite it, don't append to it.

## Right now

**Slice 1 (FNOL & claim number) is done, verified green, and reviewed.** A fresh-context
review agent produced findings (2026-09-06); all blocking + should-fix findings were fixed
and re-verified. Nothing from the review is outstanding except optional polish items
listed in `docs/decisions.md` "Slice-1 review fixes". Note: the workflow's ideal review is
a *strong-model fresh session*; this one used the in-harness fresh-context agent — a
strong-model pass is still worthwhile before slice 2 if you want it.

Next up: Slice 2 (assignment & adjuster queue) — do not start without the user's go.

## Run it (canonical — Docker)

```bash
docker compose up -d db mailpit keycloak   # Postgres :5432 (claims + claims_e2e),
                                           # Mailpit :1025/:8025, Keycloak :8090 (admin admin/admin)
mvn -f backend/pom.xml spring-boot:run     # backend  -> http://localhost:8081 (Flyway migrates on boot)
npm --prefix frontend start                # frontend -> http://localhost:4200
```

Open http://localhost:4200 → **File a claim** → register in Keycloak (self-registered
users get `claimant`) → FNOL against seeded `POL-10001` (Ada Lovelace /
ada.lovelace@example.test) or `POL-20002` (AUTO → L2). Email lands in Mailpit :8025.
Ports 8081/8090 exist because 8080 on this machine is taken.

## Tests

```bash
mvn -f backend/pom.xml test   # 25 tests: unit + integration (Testcontainers Postgres + Mailpit)
npm ci --prefix frontend && npm --prefix frontend run build
docker compose up -d db mailpit keycloak            # once
npm --prefix e2e test         # boots backend on :8082 against claims_e2e + Angular dev server; real Keycloak
```

## Slices

| # | Slice | Status | Reviewed |
|---|---|---|---|
| 0 | Walking skeleton | done | yes (external 2026-09-03) |
| 1 | FNOL & claim number | **done** (2026-09-06) | yes — fresh-context agent review; fixes applied (strong-model pass optional) |
| 2 | Assignment & adjuster queue | not started | no |

Slice 2 preview (from `docs/plan.md`): assignment email, status → UNDER_REVIEW, least-loaded
routing with tie-break (unit), queue + assignment auth incl. 404-for-non-assignee
(integration), E2E journey 3. Needs `app_user` (staff cache) seeded + internal roles in
Keycloak (adjusters provisioned, not self-registered).

## Test counts

Unit: 12 · Integration: 13 (incl. context smoke) · E2E: 3 · All green: yes (2026-09-06)

- Unit 12: PolicyViewMapper 4 · ClaimClassifier 3 · ClaimantClaimView 3 · ClaimNumberFormatter 1 · AuditJson 1.
- Integration 13: FnolApi 11 · PolicyApi 1 · context smoke 1.
- E2E 3: skeleton page · journey 1 (Keycloak register → FNOL w/ photo → claim number) ·
  rejected-FNOL-stays-on-form with the server error.

## Blocked on

- Nothing. Slice 2 awaits the user's go. CI (`.github/workflows/ci.yml`) is authored for
  the full compose stack but has never run — the repo still has no git remote; the first
  push should be watched (the workflow's compose/Keycloak steps were fixed during review
  but remain unexecuted until then).

## Notes for whoever picks this up

- Docs: concept `docs/claims-product-concept.md`; requirements `docs/requirements.md`;
  plan `docs/plan.md`; decisions `docs/decisions.md` (slice-1 entries 2026-09-06 +
  review-fix entry).
- Two load-bearing rules unchanged: the **authority gate** (slice 4) and the **visibility
  wall** (the claimant DTO exists and the integration test now asserts internal fields are
  absent from the wire body).
- Review fixes applied in slice 1 (full list in `docs/decisions.md`): CI E2E job starts
  Keycloak; E2E backend runs on its own port 8082 against `claims_e2e` (reuse disabled —
  a dev machine's 8081 backend can never absorb E2E writes); policy numbers matched
  leniently (trim/uppercase) with holder details case-insensitive; photos validated before
  any write + upload dir removed on transaction rollback; multipart caps sit above the
  app-level caps and oversize uploads map to a readable 400; audit payloads built with a
  Jackson serializer (Boot 4 = Jackson 3, `tools.jackson`); `/api/policies` permitAll
  constrained to GET.
- Backend (Boot 4.1.1, Java 21): V3 has only slice-1 columns; claim numbers `CLM-%06d`
  from `claim_number_seq`; audit writer = parameterized JdbcTemplate insert. `mockito-core`
  was removed as unused (it arrives transitively on the test classpath anyway).
- Frontend (Angular 22): lazy `home` + `fnol` (claimantGuard via `keycloak-js`); E2E uses
  `frontend/proxy.e2e.conf.json` (:8082); dev proxy stays :8081.
- E2E is **not** fully parallel (concurrent Keycloak registration flows are flaky);
  Playwright workers still parallelize across files. Keycloak registration selectors are
  the default theme's (`#firstName`, `#username`, `#password-confirm`, button "Register").
- Boot 4 facts: starter names (`spring-boot-starter-webmvc`,
  `-security-oauth2-resource-server`, `-flyway`, `-test` variants); Jackson 3
  (`tools.jackson.*`); Testcontainers 2.0.5 non-generic `PostgreSQLContainer`.
- Sandbox: Docker enabled via host socket group (daemon restart reverts); app :8081,
  Keycloak :8090; first `docker compose up` on a pre-existing `pgdata` volume won't create
  `claims_e2e` (delete the volume once: `docker compose down -v`).
