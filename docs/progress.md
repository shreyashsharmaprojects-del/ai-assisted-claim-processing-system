# Progress

Last updated: 2026-09-06

This file exists so a new session can pick up cold. Write it for someone who has never
seen this project. Rewrite it, don't append to it.

## Right now

**Slice 7 (compliance & admin) is done and all green — this was the last slice in the
approved plan (slices 0–7).** What landed:

- **Authority config editor.** `GET /api/config/authority` lists every product's row
  (`productCode`, `routeLevel`, `l1LimitAmount`, `l2LimitAmount`, ordered by product
  code); `PUT /api/config/authority/{productCode}` replaces the three editable parameters
  and returns the saved row. Both are SUPERVISOR-only at the URL (adjusters/claimants get a
  403 before any logic runs; an unknown product is a 404). Validation: route level L1|L2,
  amounts positive / ≤2dp / within NUMERIC(14,2), **L1 ≤ L2** (an inverted ladder is
  rejected — never a legal gate state). There is deliberately **no caching**: the classifier
  (FNOL) and the authority gate (decision) re-read `authority_config` every call, so an edit
  feeds the next filed claim and the next decision immediately (integration-tested both
  ways). The SPA gained the `/admin/authority` route (supervisor-guarded) + role-gated nav
  link ("Authority settings") — per-row select/inputs + Save, data-testids `auth-*`.
- **Immutable audit-log view.** `GET /api/claims/{claimNumber}/audit` (SUPERVISOR only)
  returns the claim's audit trail oldest-first (`id`, `action`, `actorSub`, `rationale`,
  `createdAt`, `before`, `after` as JSON) — 404 for an unknown claim. Append-only is now
  **enforced at the data layer**: Flyway V7 adds a BEFORE UPDATE OR DELETE trigger on
  `audit_log` that raises, so raw edits are impossible for any caller (TRUNCATE stays legal —
  it is the integration-test reset path and fires no row triggers).
- **Reassign.** `POST /api/claims/{claimNumber}/reassign` (SUPERVISOR only) takes
  `{level: L1|L2}` and routes the claim to that level's least-loaded adjuster through
  `ClaimAssigner` (the same rule FNOL and escalation use); the claim's routing level follows
  its new holder. The row is locked (`FOR UPDATE`) against concurrent decisions. Decided →
  400 "already been decided"; `ESCALATED_SUPERVISOR` → 400 "awaiting a supervisor decision";
  invalid level → 400; requested level with no provisioned adjuster → 400 (actionable —
  unlike FNOL's silent UNASSIGNED, a supervisor's explicit request never no-ops); unknown
  claim → 404. Every reassign writes a `CLAIM_REASSIGNED` audit row with the supervisor's
  Keycloak subject as actor (no app_user row — the slice-5 pattern), before/after carrying
  assignee + level + status. Response `{claimNumber, status, level, assignedTo}`. Reassign
  and the audit view are API-only — the plan has no route-table row (and no UI page) for
  them.

Slice-7 decisions (reassign = level-based least-loaded, not a named adjuster; config edit
shape + validation; V7 trigger as the immutability enforcement point; E2E journey 9 edits
**AUTO/POL-20002** — the seeded product no other journey touches — and restores it, while
the gate-limit effect stays at the integration layer; no config-edit audit trail, no audit/
reassign UI) are recorded in `docs/decisions.md` (2026-09-06 slice-7 entry).

Backend **139 tests** (54 unit + 85 integration) and **11 E2E journeys** — all green
(2026-09-06, full local run: `mvn test` + Playwright against compose Keycloak/Mailpit).
Slice 7 went through its fresh-context review (2026-09-06, deepseek-v4-pro in a new agent
session, per the workflow): **no blocking and no should-fix findings**; all four optionals
were accepted and applied (O1: the authority editor shows the NUMERIC(14,2) scale via
`.toFixed(2)`; O2: journey-9 wording fixed in the spec + docs; O3: redundant
`configs.save` dropped; O4: the stranded-UNASSIGNED-claim reassign test added — see
`docs/decisions.md`, 2026-09-06 slice-7 review entry). **The approved slice list is now
complete** — the remaining workflow phase is 06 (pre-ship hardening), not another slice.

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
**Track this claim** → the status screen (steps only — no reserve/notes ever; an escalated
claim shows the Escalated step; a **closed** claim shows the decision — approved amount or
denial remarks). Internal: **Adjuster queue** → sign in as a provisioned adjuster
(adjuster.one / adjuster.two = L1, adjuster.three = L2, password `adjuster-Pass-123`) →
**Open claim** → full internal view with coverage, reserve form, notes box, photo downloads,
decision panel. **Supervisor** (username `supervisor`, password `supervisor-Pass-123`):
**Escalations** (nav) → escalation queue → **Review claim** → approve/deny with a rationale;
**Authority settings** (nav, slice 7) → edit each product's route level + L1/L2 limits —
the change applies to the next filed claim and the next decision. (The aging job only fires
at 03:00 and needs claims 3+ days old — exercised at the integration layer with fixed
instants, not in a running dev app. The audit-log view and reassign endpoints are
exercisable from `api.http`; there is no UI page for them.) Ports 8081/8090 exist because
8080 on this machine is taken.

## Tests

```bash
JAVA_HOME=/usr/lib/jvm/jdk-21.0.8-oracle-x64 mvn -o -f backend/pom.xml test  # 138: unit + integration
npm --prefix frontend run build
docker compose up -d db mailpit keycloak            # once
JAVA_HOME=/usr/lib/jvm/jdk-21.0.8-oracle-x64 npm --prefix e2e test
# boots backend on :8082 against claims_e2e + Angular dev server; real Keycloak.
# If Keycloak was recreated/edited (keycloak/realm-export.json), it imports the realm on boot.
```

## Slices

| # | Slice | Status | Reviewed |
|---|---|---|---|
| 0 | Walking skeleton | done | yes (external 2026-09-03) |
| 1 | FNOL & claim number | done (2026-09-06) | yes — fresh-context agent review; fixes applied |
| 2 | Assignment & adjuster queue | done (2026-09-06) | yes — fresh-context agent review; S1–S5 applied and re-verified |
| 3 | Adjuster works the claim & the visibility wall | **done** (2026-09-06) | yes — fresh-context agent review; should-fix S1–S6 applied and re-verified |
| 4 | Decision & the authority gate | **done, reviewed** (2026-09-06) | yes — fresh-context review on deepseek-v4-pro; B1 + S1–S2 + O1–O4 applied and re-verified |
| 5 | Supervisor escalation & aging | **done, reviewed** (2026-09-06) | yes — fresh-context review on deepseek-v4-pro; no blocking; should-fix S1 applied, optionals deferred |
| 6 | Claimant decision & notification | **done, reviewed** (2026-09-06) | yes — fresh-context review on deepseek-v4-pro; no blocking/should-fix; optional O1 applied, O2 deferred |
| 7 | Compliance & admin | **done, reviewed** (2026-09-06) | yes — fresh-context review on deepseek-v4-pro; no blocking/should-fix; optionals O1–O4 applied |

Slice 7 delivered, per `docs/plan.md`: the supervisor's authority-config editor
(GET/PUT `/api/config/authority`, route `/admin/authority`), the claim audit-log view
(GET `/api/claims/{claimNumber}/audit`) backed by a data-layer append-only guarantee (V7
trigger rejecting UPDATE/DELETE), and claim reassignment (POST
`/api/claims/{claimNumber}/reassign`, level-based least-loaded via `ClaimAssigner`, with a
`CLAIM_REASSIGNED` audit row). Config edits feed the gate and the classifier immediately
(no caching). E2E journey 9 covers the config editor re-routing AUTO and the next AUTO FNOL
classifying to the new level. All plan slices (0–7) are now delivered. Deliberate decisions
recorded in `docs/decisions.md` (2026-09-06 slice-7 entry): level-based reassign + its
eligibility/error rules; config edit shape + validation (L1 ≤ L2 etc.); V7 trigger as the
append-only enforcement; journey 9 on AUTO with the gate effect at the integration layer;
audit view/reassign API-only; config edits not audit-logged (deferred).

## Starting the hardening pass (fresh session)

Slice 7 is done and reviewed (fresh-context, deepseek-v4-pro — no blocking or should-fix
findings; optionals O1–O4 applied; see `docs/decisions.md`, 2026-09-06 slice-7 review
entry). The approved plan's slice list is complete — the remaining workflow phase is 06
(pre-ship hardening), not another slice. Docs for the hardening pass: `docs/plan.md`,
`docs/requirements.md` (the non-goals and hardening-relevant rows),
`docs/decisions.md` (the Deferred sections), and `rules/yagni.md` +
`rules/testing-web.md`.

**Slice-7 shape recap:** config surface lives in the `routing` package
(`AuthorityConfigController`/`Service`/`View`, `ConfigNotFoundException`, setters added to
the previously read-only `AuthorityConfig` entity); claim admin lives in `claim`
(`ClaimAdminController`/`Service`, `AuditEntryView`, `ClaimAssigneeView`, `ReassignRequest`
nested); `SecurityConfig` gained four SUPERVISOR URL rules; V7 migration adds the
append-only trigger. The frontend `authority/` component + `/admin/authority` route +
nav link. E2E journey 9 in `queue.spec.ts`. `api.http` documents all four endpoints.

## Test counts

Unit: 54 · Integration: 85 (incl. context smoke) · E2E: 11 · All green: yes (2026-09-06)

- Unit 54 (unchanged since slice 6 — validation/eligibility is integration-tested, like the
  reserve path): PolicyViewMapper 4 · ClaimClassifier 3 · LoadBalancer 5 ·
  ClaimantClaimView 9 · ClaimNumberFormatter 1 · AuditJson 1 · AuthorityGate 16 ·
  AgingPolicy 15.
- Integration 85: FnolApi 11 · AssignmentQueue 10 · ClaimWork 15 · ClaimDecision 13 ·
  EscalationDecision 9 · **AuthorityConfig 7** (slice 7: GET lists seeded rows; SUPERVISOR
  auth matrix on GET+PUT; PUT updates + stores; raising HOME's L1 limit turns a would-be
  escalation into a closure (gate effect, config restored in `finally`); re-routing AUTO to
  L1 makes the next AUTO FNOL classify L1 (restored); PUT validation 400s incl. L1>L2 with
  the row untouched; unknown product 404) · **ClaimAdmin 10** (slice 7: audit read oldest-
  first with actor + rationale + JSON payloads; audit endpoint SUPERVISOR-only + 404;
  append-only — raw UPDATE/DELETE rejected at the data layer, INSERT still works; reassign
  L1 → the other L1 adjuster + CLAIM_REASSIGNED audit + old holder loses access; reassign to
  L2 re-levels onto the L2 adjuster; decided/ESCALATED_SUPERVISOR/invalid-level 400s; no-L2-
  provisioned 400 with `finally` restore; the stranded-UNASSIGNED-claim reassign (review
  optional O4); 401/403 matrix; unknown claim 404) · Aging 8 · PolicyApi 1 · context smoke 1.
- E2E 11: skeleton page · journey 1 · rejected-FNOL-stays-on-form · journey 2 (claimant
  status: no reserve/notes on screen or wire) · journey 3 (claim in exactly one L1 queue,
  never L2) · journey 4 (adjuster works a claim) · journey 5 (within-limit approval closes)
  · journey 6 (above-limit approval blocked + escalated to L2) · journey 7 (supervisor
  approves an escalation with rationale → closes) · journey 8 (claimant sees the decision on
  the closed claim: approved amount and denial remarks) · **journey 9** (slice 7: the
  supervisor re-routes AUTO to L1 in /admin/authority; a fresh AUTO FNOL lands in exactly
  one L1 queue and never L2; AUTO restored to L2).

Slices 6 and 7 are reviewed (fresh-context, deepseek-v4-pro — no blocking or should-fix;
see `docs/decisions.md`, 2026-09-06 review entries). Slices 4–5 likewise (deepseek-v4-pro;
slice-5 no blocking). All plan slices are delivered.

## Blocked on

- Nothing. All plan slices are delivered and reviewed; the remaining phase is 06 (pre-ship
  hardening), on the user's go.

## Notes for whoever picks this up

- Docs: concept `docs/claims-product-concept.md`; requirements `docs/requirements.md`;
  plan `docs/plan.md`; decisions `docs/decisions.md` (2026-09-06 slice-7 entry on top,
  then the slice-6 review + slice-6 decision entries).
- Load-bearing rules now: the **visibility wall** (slices 1–3, holds on CLOSED — only the
  decision fields were added in slice 6), the **404-not-403** rule (re-pinned on closed
  claims in slice 6), the **authority gate** (slice 4 — config thresholds read fresh per
  decision, no cache), the **single-payment/closure atomicity** rule (slice 4), the
  **role-driven queue** (slice 2), the **aging ladder** (slice 5), and the **audit-log
  immutability** rule (slice 7 — now a V7 DB trigger, not just writer discipline; any
  correction is a new row).
- The supervisor identity has **no app_user row**: audit/reassign rows carry the Keycloak
  subject as actor; `Authorities.isSupervisor` derives the flag from the role. Reassign
  reuses `ClaimAssigner` (row-locks the level's adjusters) after the slice-4 `FOR UPDATE`
  claim lock.
- Integration classes each boot their own Testcontainers Postgres and carry their own HTTP
  helpers (the repo convention — duplication over a premature shared base). Newest classes:
  `AuthorityConfigIntegrationTest` (routing) and `ClaimAdminIntegrationTest` (claim) extend
  `ClaimTableResettingTest`. `authority_config` is a seed table, never truncated — any test
  that edits it restores in `finally`; tests that delete the L2 adjuster restore it in
  `finally` too (fresh id, same identity — look staff up by `keycloak_sub`, never assume an
  id).
- Postgres **jsonb normalizes stored numeric scale**: an audit payload recorded as 1500.00
  reads back as 1500.0 (see the ClaimAdmin audit test comment). Don't assert trailing-zero
  scales on jsonb payloads.
- E2E journey 9 owns AUTO (POL-20002) in the shared claims_e2e DB: it re-routes AUTO to L1
  idempotently at its start (a no-op when an earlier run left it there) and restores L2 at
  its end, so an interrupted run cannot cascade (no other journey touches AUTO). Never point
  journey-9-style config edits at HOME — journeys 1–8 assume HOME routes L1 with
  2500/10000 limits.
- Scheduler determinism: `AgingScheduler` is `@ConditionalOnProperty("claims.aging.enabled")`
  (main properties: true, cron daily 03:00, `@EnableScheduling`); `ClaimTableResettingTest`
  disables it in claim-writing tests via an inherited `@DynamicPropertySource`. Do NOT
  reintroduce a `src/test/resources/application.properties` — it shadows the main file
  wholesale (learned slice 5).
- `claim.created_at` is NOT entity-mapped; the aging candidate query reads it via JDBC.
- Frontend: `/admin/authority` is `authority/` (route + `supervisorGuard` + role-gated nav
  link `nav-authority`); the queue-page/escalations pattern was copied (data-testids
  `auth-page`/`auth-row`/`auth-product`/`auth-route`/`auth-l1`/`auth-l2`/`auth-save`/
  `auth-error`/`auth-saved`). Amount inputs are text-bound and converted with `Number()` on
  save, like the claim-detail reserve form.
- Keycloak: four provisioned users (three adjusters + supervisor, no app_user row).
  `keycloak/realm-export.json` imports on Keycloak boot; edits need a container recreate.
  The realm-sync seam test counts exactly the three adjuster-role users.
- `api.http` documents the whole surface (now 8+ endpoints incl. the four slice-7 ones).
- Slice-1..6 facts from before still hold (fixed adjuster subjects, dev creds, E2E on
  claims_e2e/port 8082, no new E2E spec files while registrations share the realm).
