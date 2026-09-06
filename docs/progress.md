# Progress

Last updated: 2026-09-06

This file exists so a new session can pick up cold. Write it for someone who has never
seen this project. Rewrite it, don't append to it.

## Right now

**Slice 2 (assignment & the adjuster queue) is done and verified green.** FNOL now
assigns every new claim, inside the creating transaction, to the least-loaded adjuster of
its level (fewest open claims, lowest `app_user.id` on ties — row-locked so concurrent
FNOLs cannot double-assign), moves it to UNDER_REVIEW, records a CLAIM_ASSIGNED audit row,
and emails the claimant who their adjuster is. Adjusters (and supervisors) get a queue:
`GET /api/queue` returns own claims for an adjuster and the whole team for a supervisor,
oldest first; the SPA has a `/queue` screen behind an internal-role guard. Backend **39
tests**, **4 E2E journeys** — all green. Slice 2 has NOT had its fresh-context review;
that belongs in a new session (see below).

Next up: Slice 3 (adjuster works the claim & the visibility wall) — do not start without
the user's go, and only after a fresh-session review of slice 2.

## Run it (canonical — Docker)

```bash
docker compose up -d db mailpit keycloak   # Postgres :5432 (claims + claims_e2e),
                                           # Mailpit :1025/:8025, Keycloak :8090 (admin admin/admin)
mvn -f backend/pom.xml spring-boot:run     # backend  -> http://localhost:8081 (Flyway migrates on boot)
npm --prefix frontend start                # frontend -> http://localhost:4200
```

Open http://localhost:4200. Claimant: **File a claim** → register in Keycloak
(self-registered users get `claimant`) → FNOL against seeded `POL-10001` (Ada Lovelace /
ada.lovelace@example.test) or `POL-20002` (AUTO → L2). You'll see the claim number
immediately with an "under review" step; the FNOL **and** assignment emails (the latter
naming your adjuster) land in Mailpit :8025. Internal: **Adjuster queue** → sign in as a
provisioned adjuster (adjuster.one / adjuster.two = L1, adjuster.three = L2, password
`adjuster-Pass-123`) → the queue shows the claims assigned to you. Ports 8081/8090 exist
because 8080 on this machine is taken.

## Tests

```bash
mvn -f backend/pom.xml test   # 39 tests: unit + integration (Testcontainers Postgres + Mailpit)
npm ci --prefix frontend && npm --prefix frontend run build
docker compose up -d db mailpit keycloak            # once
npm --prefix e2e test         # boots backend on :8082 against claims_e2e + Angular dev server; real Keycloak
```

## Slices

| # | Slice | Status | Reviewed |
|---|---|---|---|
| 0 | Walking skeleton | done | yes (external 2026-09-03) |
| 1 | FNOL & claim number | done (2026-09-06) | yes — fresh-context agent review; fixes applied (strong-model pass optional) |
| 2 | Assignment & adjuster queue | **done** (2026-09-06) | no — fresh-session review outstanding (workflow rule: never self-review) |
| 3 | Adjuster works the claim & the visibility wall | not started | no |

Slice 2 delivered, per `docs/plan.md`: `app_user` staff cache (V4) seeded with two L1 +
one L2 adjuster whose `keycloak_sub` values match provisioned Keycloak users (fixed
subjects honored on realm import); atomic least-loaded assignment at FNOL with the
lowest-id tie-break (candidate rows `SELECT ... FOR UPDATE` serialize concurrent FNOLs);
status → UNDER_REVIEW; CLAIM_ASSIGNED audit as a system action; assignment email to the
claimant naming the adjuster; `GET /api/queue` (own for adjusters incl. 401/403 and
empty-queue behavior, team for supervisor, oldest-first); SPA `/queue` behind an
internal-role guard. Deliberate deviations from the plan are recorded in
`docs/decisions.md` (2026-09-06 entries): `app_user.level` column (routing needs the
level without an admin-API call), assignment inside the FNOL transaction, assignment
email to the claimant while the screen never names the assignee, fixed Keycloak subjects,
**404-for-non-assignee integration case deferred to slice 3** (no per-claim internal
endpoint exists until then), CLAIM_ASSIGNED actor NULL.

Slice 3 preview (from `docs/plan.md`): Flow 3 — adjuster opens the claim, verifies
coverage against the seeded policy, reads description/photos, sets reserve, writes
internal notes; the visibility wall (reserve/notes absent from every claimant surface)
becomes load-bearing. Needs the internal full-view + attachment endpoints (`GET
/api/claims/{claimNumber}/full`, photo GET with `Content-Disposition: attachment` —
see decisions.md deferred (a) from slice-1 review), `reserve` + `internal_note` tables
(V5), the adjuster claim screen, and E2E journeys 2 and 4. Slice 2's 404-for-non-assignee
integration case rides in with the full view.

## Test counts

Unit: 18 · Integration: 21 (incl. context smoke) · E2E: 4 · All green: yes (2026-09-06)

- Unit 18: PolicyViewMapper 4 · ClaimClassifier 3 · LoadBalancer 5 (slice 2) ·
  ClaimantClaimView 4 · ClaimNumberFormatter 1 · AuditJson 1.
- Integration 21: FnolApi 11 · AssignmentQueue 8 (slice 2: load-balance + tie-break
  determinism, L2 routing, audit + assignment email, two-thread no-double-assign, queue
  own-vs-team/order/empty/auth) · PolicyApi 1 · context smoke 1.
- E2E 4: skeleton page · journey 1 (Keycloak register → FNOL w/ photo → claim number) ·
  rejected-FNOL-stays-on-form · **journey 3** (filed claim appears in exactly one L1
  adjuster queue, never the L2 queue).

## Blocked on

- Nothing. Slice 3 awaits the user's go and a fresh-session review of slice 2. CI state
  for the slice-2 branch: **not yet pushed** — backend + frontend + E2E verified locally
  exactly as the CI jobs run them; push to `origin`/`main` triggers GitHub Actions.

## Notes for whoever picks this up

- Docs: concept `docs/claims-product-concept.md`; requirements `docs/requirements.md`;
  plan `docs/plan.md`; decisions `docs/decisions.md` (slice-2 entries 2026-09-06 first
  under Decisions; one Deferred entry).
- Two load-bearing rules unchanged: the **authority gate** (slice 4) and the **visibility
  wall** (slice 1 began it; the FnolApi test now also asserts `assignedTo` is absent from
  the claimant wire body). Slice 2 adds a third: the queue is **role-driven** — an
  adjuster only ever sees their own assignments (supervisors see the team) — and
  cross-tenant 404 semantics arrive with slice-3 detail endpoints.
- Assignment facts: seeded adjusters are ids 1/2 (L1) and 3 (L2) on a fresh database
  (V4 insert order); first L1 FNOL → id 1 (tie-break), balancing alternates from there.
  `AssignmentQueueIntegrationTest` truncates `claim`/`attachment`/`audit_log` between
  tests so its outcomes are deterministic; the other integration classes don't truncate
  (their assertions are per-claim), so don't add cross-test-global assertions there.
- E2E is **not** fully parallel (concurrent Keycloak registration flows are flaky);
  Playwright workers still parallelize across files. Journey 3 deliberately asserts
  "exactly one L1 adjuster holds the claim" instead of predicting *which* one, because
  `claims_e2e` accumulates claims across runs (assignment alternates between the two L1
  adjusters as loads shift).
- Keycloak staff provisioning lives in `keycloak/realm-export.json` with **fixed user
  ids** (`10000000-...0001..0003`); realm import honors them (verified), so subjects match
  the V4 `app_user.keycloak_sub` seeds. Passwords: adjusters `adjuster-Pass-123`.
  Recreate the container to re-import: `docker compose up -d --force-recreate keycloak`.
- Backend (Boot 4.1.1, Java 21): `LoadBalancer` (pure picker, unit-tested),
  `ClaimAssigner` (component running inside the FNOL transaction — candidate rows
  `FOR UPDATE`, count via grouped SQL, claim mutated via `assignTo`), `QueueService`
  (JdbcTemplate + RowMapper), `AppUser` entity + repo. Spring Security gained
  `GET /api/queue` for the three internal roles only.
- Frontend (Angular 22): lazy `queue` route (`/queue`) behind `internalGuard`
  (adjuster_l1/adjuster_l2/supervisor); rows expose `data-testid` selectors
  (`queue-row`, `queue-claim-number`, `queue-empty`, …). Prettier-formatted.
- `api.http`: added the `GET /api/queue` example (needs an adjuster token pasted).
- Slice-1 facts that still hold: E2E backend on :8082 against `claims_e2e` (reuse
  disabled); backend `claims` dev DB migrates on next boot (V4 pending there until then);
  Keycloak registration selectors are the default theme's (`#firstName`, `#username`,
  `#password-confirm`, button "Register"); login uses `#username`, `#password`,
  `#kc-login`.
