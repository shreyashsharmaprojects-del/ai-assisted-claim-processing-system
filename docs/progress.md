# Progress

Last updated: 2026-09-06

This file exists so a new session can pick up cold. Write it for someone who has never
seen this project. Rewrite it, don't append to it.

## Right now

**Slice 3 (adjuster works the claim & the visibility wall) is done and verified green.**
An adjuster (or supervisor) opens a claim from the queue and gets the full internal view —
policy + coverage, the loss, photos, the reserve, and internal notes; they can set/update
the reserve and write internal notes. The claimant side gains a status screen for their
own claim. The visibility wall is now load-bearing and asserted on the wire: reserve and
notes are structurally absent from every claimant-facing response and screen (E2E journey
2 checks screen AND network body). Backend **52 tests**, **6 E2E journeys** — all green.
Slice 3 has NOT had its fresh-context review; that belongs in a new session.

Slice 2 was pushed to `origin`/`main` (commits 27561e7, 0106a6d) and the GitHub Actions
run **passed all three jobs** (backend Testcontainers, frontend build, E2E compose) —
2026-09-06. Slice 3 is committed locally, not yet pushed.

Next up: Slice 4 (decision & the authority gate) — do not start without the user's go,
and only after a fresh-session review of slice 3.

## Run it (canonical — Docker)

```bash
docker compose up -d db mailpit keycloak   # Postgres :5432 (claims + claims_e2e),
                                           # Mailpit :1025/:8025, Keycloak :8090 (admin admin/admin)
mvn -f backend/pom.xml spring-boot:run     # backend  -> http://localhost:8081 (Flyway migrates on boot)
npm --prefix frontend start                # frontend -> http://localhost:4200
```

Open http://localhost:4200. Claimant: **File a claim** → register in Keycloak
(self-registered users get `claimant`) → FNOL against seeded `POL-10001` (Ada Lovelace /
ada.lovelace@example.test) or `POL-20002` (AUTO → L2) → claim number immediately, then
**Track this claim** → the status screen (steps only — no reserve/notes ever). Internal:
**Adjuster queue** → sign in as a provisioned adjuster (adjuster.one / adjuster.two = L1,
adjuster.three = L2, password `adjuster-Pass-123`) → **Open claim** on a row → full
internal view with coverage, the reserve form, the notes box, and photo downloads. Ports
8081/8090 exist because 8080 on this machine is taken.

## Tests

```bash
mvn -f backend/pom.xml test   # 52 tests: unit + integration (Testcontainers Postgres + Mailpit)
npm ci --prefix frontend && npm --prefix frontend run build
docker compose up -d db mailpit keycloak            # once
npm --prefix e2e test         # boots backend on :8082 against claims_e2e + Angular dev server; real Keycloak
```

## Slices

| # | Slice | Status | Reviewed |
|---|---|---|---|
| 0 | Walking skeleton | done | yes (external 2026-09-03) |
| 1 | FNOL & claim number | done (2026-09-06) | yes — fresh-context agent review; fixes applied |
| 2 | Assignment & adjuster queue | done (2026-09-06) | yes — fresh-context agent review; S1–S5 applied and re-verified |
| 3 | Adjuster works the claim & the visibility wall | **done** (2026-09-06) | no — fresh-session review outstanding (workflow rule: never self-review) |
| 4 | Decision & the authority gate | not started | no |

Slice 3 delivered, per `docs/plan.md`: V5 (`claim.reserve_amount`, `internal_note`);
`GET /api/claims/{claimNumber}` claimant status (own claim only, else 404); `GET
/api/claims/{claimNumber}/full` + `PUT .../reserve` + `POST .../notes` + `GET
.../attachments/{id}` for the assigned adjuster or SUPERVISOR — the **404-for-non-assignee
rule (deferred from slice 2) now lands on these endpoints**; photos are always served
`Content-Disposition: attachment` with the stored filename scrubbed (closes slice-1
review deferral (a)); SPA `/claim/:claimNumber` (claimant) and `/claims/:claimNumber`
(adjuster) screens, with the FNOL result linking to the status screen and queue rows
opening the claim. Deliberate decisions recorded in `docs/decisions.md` (2026-09-06
entries): `internal_note.author_id` nullable (a supervisor token may have no staff-cache
row) and note adds not audited; reserve set via PUT and audited (`RESERVE_SET`, before +
after), never authority-gated; photos as attachment + scrubbed header name; coverage read
via JDBC (JSONB) because the entity stays unmapped.

Slice 4 preview (from `docs/plan.md`): Flow 4 gate — within-level approval → single
payment recorded + CLOSED; above-level approval blocked and escalated (system re-assign to
L2 or `ESCALATED_SUPERVISOR`); no self-approval; decision email fires in the closure
transaction. Needs V6 (`claim.decision/decision_remarks/indemnity_amount/closed_at`,
`payment`), the decision endpoint with rationale required, and the unit gate matrix —
the product's core rule.

## Test counts

Unit: 18 · Integration: 34 (incl. context smoke) · E2E: 6 · All green: yes (2026-09-06)

- Unit 18: PolicyViewMapper 4 · ClaimClassifier 3 · LoadBalancer 5 · ClaimantClaimView 4 ·
  ClaimNumberFormatter 1 · AuditJson 1.
- Integration 34: FnolApi 11 · AssignmentQueue 10 · ClaimWork 11 (slice 3: claimant status
  wall on the wire, cross-claimant 404, full view for assignee/supervisor incl. coverage,
  non-assignee 404 on full/reserve/notes/photo, reserve set/update + audit + negative
  reject, notes append/order/validation, photo download as attachment) · PolicyApi 1 ·
  context smoke 1.
- E2E 6: skeleton page · journey 1 (register → FNOL w/ photo → claim number) ·
  rejected-FNOL-stays-on-form · **journey 2** (claimant status screen: no reserve/notes on
  screen or wire) · **journey 3** (claim in exactly one L1 queue, never L2) · **journey 4**
  (assigned adjuster sets a reserve, adds a note, downloads the photo).

## Blocked on

- Nothing. Slice 3 review (fresh session) then Slice 4, both awaiting the user's go.
  Slice-2 commits are on `origin`/`main` with a green CI run (2026-09-06). Slice 3 is
  local only.

## Notes for whoever picks this up

- Docs: concept `docs/claims-product-concept.md`; requirements `docs/requirements.md`;
  plan `docs/plan.md`; decisions `docs/decisions.md` (slice-3 entries 2026-09-06 at the
  top of Decisions; Deferred holds slice-2 optionals + earlier deferrals).
- Load-bearing rules now four: the **visibility wall** (slice 3 makes it structural — the
  claimant status response is asserted field-by-field to lack `reserve`/`notes`/internals,
  E2E journey 2 re-checks the network body), the **404-not-403** object-access rule (now
  enforced on claimant status, full view, reserve, notes, and photo endpoints),
  the **authority gate** (slice 4), and the **role-driven queue** (slice 2).
- Authorization is centralized: `ClaimAccess` answers "may this internal reader see this
  claim" (supervisor, or the assigned adjuster matched by subject via the staff cache);
  every internal endpoint funnels through it and treats "no" as a 404.
- Every claim-writing integration class extends `ClaimTableResettingTest`
  (`com.claims.support`) — claim/attachment/audit_log are truncated between tests, so
  assignees are deterministic from the V4 seeds; don't add a claim-writing class that
  skips the base.
- E2E is **not** fully parallel (concurrent Keycloak registration flows are flaky); files
  still parallelize. Journeys 2/4 live in the existing spec files (fnol.spec.ts,
  queue.spec.ts) so no new worker adds registration traffic. Journey 4 finds the holder
  by scanning the two L1 queues, because `claims_e2e` accumulates and the assignee
  alternates with load.
- Frontend (Angular 22): lazy routes `claim/:claimNumber` (claimant, `claimantGuard`) and
  `claims/:claimNumber` (internal, `internalGuard`); queue rows open the claim; the FNOL
  result links to the status screen. Photo downloads are fetch-blob + `<a download>`
  (auth header can't ride a plain link).
- Backend: `ClaimWorkService` owns the internal surface; coverage read as JSON via JDBC
  (`coverage::text`) — the policy entity stays unmapped. `claim.created_at` is now mapped
  (read-only) because the full view shows it.
- `api.http` has working examples for the new endpoints (claimant status, full, reserve,
  notes, attachment).
- Keycloak/staff and slice-1/2 facts from before still hold (fixed adjuster subjects,
  adjuster passwords, dev creds; V5 pending on the dev `claims` DB until next boot).
