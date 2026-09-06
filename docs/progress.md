# Progress

Last updated: 2026-09-06

This file exists so a new session can pick up cold. Write it for someone who has never
seen this project. Rewrite it, don't append to it.

## Right now

**Slice 5 (supervisor escalation & aging) is done, verified green, and has been through its
fresh-context review; no blocking findings.** A supervisor approves or denies claims in `ESCALATED_SUPERVISOR` on
`POST /api/claims/{claimNumber}/escalation-decision` (SUPERVISOR only; rationale required;
approve → single payment + close, deny → close with remarks; payment/closure/`DECISION`
audit atomic — the slice-4 machinery; decision email best-effort after commit via the same
`DecisionEmailSender`). Supervisor approvals are **not amount-gated** (highest authority)
and no-self-approval is structural (supervisors never create the escalations they decide —
see `docs/decisions.md`). A supervisor has **no app_user row**, so its approval records the
payment with `authorized_by_id = NULL` while the `DECISION` audit carries its Keycloak
subject as `actor_sub`.

The supervisor's escalation surface: `GET /api/escalations` (SUPERVISOR-only) lists claims
in `ESCALATED_SUPERVISOR` (same row shape as the queue, oldest first); the SPA gained the
`/escalations` route (supervisor-only guard + nav link) and the claim-detail decision panel
now opens for a supervisor on an escalated claim (posts to the escalation-decision
endpoint). Claimants now see an "Escalated" process step while their claim waits on the
supervisor (`ClaimantClaimView.stepsFor`); the decision display on closed claims remains
slice 6.

**Aging (Flow 6)** runs as a scheduled background job: `AgingScheduler` (cron daily 03:00,
`@EnableScheduling`) calls `AgingService.ageClaims(Instant)` — the instant parameter is the
injectable clock (tests pass fixed instants; a `Clock` bean was deliberately not added).
A claim undecided **3 days from FNOL** is pushed to the L2 tier (level → L2, least-loaded
L2 adjuster via `ClaimAssigner`; no L2 provisioned → `ESCALATED_SUPERVISOR` fallback with
the level reverted, same rule as slice 4); **5 days** → `ESCALATED_SUPERVISOR`. The 5-day
rung wins over the 3-day rung, so a missed run or a clock jump lands a claim at the top in
one idempotent move; claims already held at L2 are not re-pushed at day 3; `CLOSED` and
already-`ESCALATED_SUPERVISOR` claims are never re-touched. The anchor is `claim.created_at`
(read via JDBC — it is not entity-mapped); each candidate row is locked (`FOR UPDATE`) and
the ladder step recomputed against the freshly locked state, so a concurrent decision or a
second run never double-transitions. Every transition writes a `CLAIM_ESCALATED` audit row
with `actor_sub NULL` (system action) and a rationale naming the rung.

**Supervisor identity:** `keycloak/realm-export.json` now provisions a fixed-subject
supervisor (id `10000000-0000-0000-0000-000000000004`, username `supervisor`, role
`supervisor`, dev password `supervisor-Pass-123`) with **no app_user row** (the realm-sync
seam test still pins exactly the three adjusters). E2E journey 7 logs in as this real user.

Backend **117 tests** (50 unit + 67 integration) and **9 E2E journeys** — all green
(2026-09-06, full local run: `mvn test` + Playwright against compose Keycloak/Mailpit).
Slice 5 went through its fresh-context review (2026-09-06, deepseek-v4-pro in a new agent
session): **no blocking findings**; one should-fix (a stale comment in
`application.properties` pointing at a nonexistent test-properties file) applied, and three
optional items deferred (see `docs/decisions.md` — 2026-09-06 slice-5 review entry).
Next up: **Slice 6 (claimant decision & notification)** — start only with the user's go.

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
claim now shows the Escalated step). Internal: **Adjuster queue** → sign in as a provisioned
adjuster (adjuster.one / adjuster.two = L1, adjuster.three = L2, password
`adjuster-Pass-123`) → **Open claim** on a row → full internal view with coverage, the
reserve form, the notes box, photo downloads, and the decision panel. **Supervisor**
(username `supervisor`, password `supervisor-Pass-123`): sign in → **Escalations** (nav) →
the supervisor escalation queue → **Review claim** → approve/deny with a rationale. (The
aging job only fires at 03:00 and needs claims 3+ days old — it is exercised at the
integration layer with fixed instants, not in a running dev app.) Ports 8081/8090 exist
because 8080 on this machine is taken.

## Tests

```bash
JAVA_HOME=/usr/lib/jvm/jdk-21.0.8-oracle-x64 mvn -f backend/pom.xml test  # 117: unit + integration
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
| 6 | Claimant decision & notification | not started | no |

Slice 5 delivered, per `docs/plan.md`: the supervisor half of Flow 4 (`POST
/api/claims/{claimNumber}/escalation-decision`, SUPERVISOR only; `GET /api/escalations`);
the Flow-6 aging job (3-day → L2 / 5-day → supervisor, claimant-visible escalated step);
and the realm supervisor identity for journey 7. Deliberate decisions recorded in
`docs/decisions.md` (2026-09-06 slice-5 entry): unconditional supervisor approval +
structural no-self-approval; 400-vs-404 eligibility on the escalation endpoint; NULL
`payment.authorized_by` with the subject on the audit row; realm-only supervisor (no
app_user row, seam test intact); aging-ladder semantics under clock jumps (5-day rung
wins, L2-held claims not re-pushed, CLOSED/ESCALATED immune, null-actor `CLAIM_ESCALATED`
audit rows with rung-naming rationale); injectable time via an explicit `Instant` on
`ageClaims` (no Clock bean) with the cron disabled in claim-writing tests through an
inherited `@DynamicPropertySource`; the escalation queue reusing `QueueClaimView`.

## Starting Slice 6 (fresh session)

Read first: `docs/plan.md` (slice 6 + the claimant-view/state-transition sections + the
API-table rows for `GET /api/claims/{claimNumber}` + the route table), `docs/requirements.md`
(Flow 5), `docs/decisions.md` (2026-09-06 slice-4 and slice-5 entries — especially the
claimant-view-unchanged decisions), and `rules/yagni.md` + `rules/testing-web.md`. Slice 5
is reviewed (fresh-context, deepseek-v4-pro, no blocking findings); slice 6 is ready to
start on the user's go.

**Slice-6 scope (from the plan, with the current state of each piece):**

1. **Claimant decision display** — Flow 5's claimant half: the claimant opens their CLOSED
   claim and sees the decision — the approved amount (`indemnity_amount`, when
   `decision = APPROVED`) or denial + remarks (`decision_remarks`). The wall still holds on
   closed claims (no reserve/notes ever). Today `ClaimantClaimView` structurally carries
   only `claimNumber`/`status`/`steps` (a unit test pins the record shape), `stepsFor`
   returns **empty for CLOSED** (default branch; the ESCALATED_SUPERVISOR branch landed in
   slice 5), and the claim-status screen renders only status + steps. Slice 4 and 5
   deliberately deferred decision fields on the claimant view to this slice.
   - Watch-outs: the claim-status screen copy and the wire shape change together; the
     structural-wall test (`ClaimantClaimViewTest.viewStructurallyCarriesOnlyPublicFields`)
     and the journey-2 wire assertions must be extended deliberately, not accidentally; a
     CLOSED claim whose journey passed through escalation shows steps + decision (is the
     escalated step part of the CLOSED timeline? status alone cannot recall history — the
     decision/closure columns don't record escalation; decide what the closed view shows).
2. **Decision email verification** — the decision email already fires on closure (slice 4
   adjuster path; slice 5 supervisor path) and integration tests already assert Mailpit
   delivery on closure. Slice 6 verifies it end to end (journey 8: claimant sees the
   decision; the email is asserted at the integration layer — maybe assert the claimant
   actually receives it in Mailpit as the journey's user).
3. **E2E journey 8** (plan list): claimant sees the decision on the closed claim — approved
   amount, or denial + remarks. Fixture: a claim closed by an adjuster (journey 5 flow) and
   one closed by denial (or by the supervisor — journey 7's flow). Denial remarks =
   rationale (slice-4/5 decision); internal notes stay off the surface.
4. **Emails on closure already exist** — nothing to build for the "sent on closure" half;
   only the claimant-facing rendering and verification land here.

**Files you'll likely touch:** `ClaimantClaimView` (+ new fields and its factory),
`ClaimantStatusController` maybe unchanged (shape lives in the view record), the
claim-status screen (`claim-status.ts/html`), `ClaimantClaimViewTest` (structural test
update + decision mapping tests), the wall tests (`ClaimDecisionIntegrationTest` already
asserts the closed claimant view lacks reserve/notes — extend for the new decision fields),
an integration test for the closed-claim claimant view incl. denial remarks and approval
amount, `queue.spec.ts` or `fnol.spec.ts` for journey 8 (existing file, per the standing
E2E-worker decision), `api.http` if a response shape changes, `docs/progress.md` +
`docs/decisions.md`.

**Tests (from the plan + this slice's pattern):** integration — the closed claim's claimant
view shows decision/indemnity_amount or remarks, never reserve/notes; decision email
received (Mailpit) on both approval and denial closures (adjuster and supervisor actors);
E2E — journey 8. The claimant-view record change is the kind of deliberate DTO change the
repo pins with a structural test first.

**Watch-outs:** the visibility wall on CLOSED claims is load-bearing (slice-2..5 decision
notes: wall holds on closed claims). `decision_remarks` on denials IS claimant-visible by
design; internal notes are not — keep the separation. `indemnity_amount` appears only when
`decision = APPROVED` (the plan API row). The 404-not-403 rule still applies (someone
else's closed claim is a 404). Journey 8's claim can be set up through the UI (journey 5/6
helpers in `queue.spec.ts`) or via API — follow the existing spec conventions. Slice 6 does
not touch the internal adjuster/supervisor screens.

## Test counts

Unit: 50 · Integration: 67 (incl. context smoke) · E2E: 9 · All green: yes (2026-09-06)

- Unit 50: PolicyViewMapper 4 · ClaimClassifier 3 · LoadBalancer 5 · ClaimantClaimView 5
  (slice 5 added the ESCALATED_SUPERVISOR escalated-step case) · ClaimNumberFormatter 1 ·
  AuditJson 1 · AuthorityGate 16 (slice 4) · **AgingPolicy 15** (slice 5: the pure ladder —
  under-threshold no-ops incl. boundary just-under, 3-day push for L1/UNASSIGNED at and
  after the exact boundary, L2-held claims not re-pushed, 5-day rung for every open state
  at the exact boundary and it beating the 3-day rung, the clock-jump catch-up, and the
  CLOSED/ESCALATED_SUPERVISOR immunity).
- Integration 67: FnolApi 11 · AssignmentQueue 10 · ClaimWork 15 · ClaimDecision 12 ·
  **EscalationDecision 9** (slice 5: supervisor approval → single payment with NULL
  authorized_by + CLOSED + DECISION audit carrying the subject + before payload + approval
  email; supervisor denial → CLOSED with remarks, no payment, email; missing rationale 400
  for approve/deny; invalid decision/amount 400s; second decision 400 with a single payment;
  the auth matrix — 401 anonymous, 403 claimant/adjuster_l1/adjuster_l2, 404 unknown claim,
  400 for a non-escalated claim and already-decided; GET /api/escalations supervisor-only
  and lists only ESCALATED claims; a decided escalation leaves the queue; the claimant sees
  the Escalated step while the claim waits on the supervisor) · **Aging 8** (slice 5: L1
  claim at 3 days → L2 assignee + level L2 + null-actor CLAIM_ESCALATED audit + no extra
  CLAIM_ASSIGNED; idempotency under a second run; the no-L2 fallback to the supervisor;
  L1 at 5 days → ESCALATED_SUPERVISOR; the clock jump from day 2 to day 6 skipping the L2
  rung; an L2-routed claim staying put at 3 days then escalating at 5; CLOSED and
  already-ESCALATED claims never aged) · PolicyApi 1 · context smoke 1.
- E2E 9: skeleton page · journey 1 (register → FNOL w/ photo → claim number) ·
  rejected-FNOL-stays-on-form · journey 2 (claimant status screen: no reserve/notes on
  screen or wire) · journey 3 (claim in exactly one L1 queue, never L2) · journey 4
  (assigned adjuster sets a reserve, adds a note, downloads the photo) · journey 5
  (adjuster approves a within-limit amount → claim closes and leaves the queue) · journey 6
  (above-limit approval blocked + escalated to the L2 adjuster) · **journey 7** (slice 5:
  an above-L2 approval escalates to the supervisor; the supervisor signs in through the real
  realm, sees the claim in /escalations, and approves it with a rationale → it closes and
  leaves the escalation queue).

Slice 4 went through its fresh-context review (see `docs/decisions.md`, 2026-09-06 review
entry). Slice 5 went through its fresh-context review (2026-09-06, deepseek-v4-pro in a new
agent session) — no blocking findings; the single should-fix (a stale properties comment)
was applied; three optionals are deferred (see `docs/decisions.md`, 2026-09-06 slice-5
review entry).

## Blocked on

- Nothing. Slice 5 is reviewed; slice 6 awaits the user's go.

## Notes for whoever picks this up

- Docs: concept `docs/claims-product-concept.md`; requirements `docs/requirements.md`;
  plan `docs/plan.md`; decisions `docs/decisions.md` (2026-09-06 slice-5 entry on top,
  then slice-4 review + slice-4 decision entries; Deferred holds the older optionals).
- Load-bearing rules now six: the **visibility wall** (slices 1–3, holds on CLOSED), the
  **404-not-403** object-access rule, the **authority gate** (slice 4), the
  **single-payment/closure atomicity** rule (slice 4), the **role-driven queue** (slice 2),
  and the **aging ladder** (slice 5 — pure `AgingPolicy.stepFor(status, level, created,
  now)`; the 5-day rung wins; CLOSED/ESCALATED immune).
- Slice-5 backend shapes: `EscalationDecisionService`/`Controller` (claim package — reuses
  `ClaimDecisionInput`/`ClaimDecisionView`/`ClaimDecisionOutcome` + `AuthorityGate.validate`
  + the row lock; approve closes unconditionally with `Payment(..., authorizedBy = null)`),
  `EscalationsController` (queue package) + `QueueService.supervisorEscalationQueue()`,
  `AgingPolicy`/`AgingService`/`AgingScheduler` (aging package). No V-migration was needed
  (ESCALATED_SUPERVISOR status, nullable payment.authorized_by_id and audit actor_sub
  already existed).
- Scheduler determinism: `AgingScheduler` is `@ConditionalOnProperty("claims.aging.enabled")`
  (main properties: true, cron daily 03:00, `@EnableScheduling` on the app class);
  `ClaimTableResettingTest` disables it in claim-writing tests via an inherited
  `@DynamicPropertySource`. Do NOT reintroduce a `src/test/resources/application.properties`
  — it shadows the main file wholesale and breaks property resolution (learned this slice).
  There is no `Clock` bean: `AgingService.ageClaims(Instant)` takes the time explicitly.
- `claim.created_at` is NOT entity-mapped (slice-3 review dropped the mapping); the aging
  candidate query reads it via JDBC. Aging locks each candidate row with
  `ClaimRepository.findByClaimNumberForUpdate` (the slice-4 lock) before deciding.
- Frontend: `/escalations` route is supervisor-guarded (`supervisorGuard` in auth.guard.ts)
  with a role-gated nav link; the queue-page component/template/css pattern was copied to
  `escalations/` (data-testids `esc-*`); the claim-detail decision panel opens for a
  supervisor on an `ESCALATED_SUPERVISOR` claim and posts to the escalation-decision
  endpoint; a supervisor closure reloads the (still-visible) claim as CLOSED, unlike an
  adjuster escalation which must not reload (the claim leaves the actor's hands).
- Keycloak: the realm now has a fourth provisioned user — supervisor — who has NO app_user
  row and no V-seed. `keycloak/realm-export.json` imports on Keycloak boot; if you edit it,
  recreate the container (`docker compose rm -sf keycloak && docker compose up -d keycloak`)
  so E2E sees the change. The realm-sync seam test (AssignmentQueueIntegrationTest) counts
  exactly the three adjuster-role users.
- `api.http` has working examples for both new endpoints (and the whole surface).
- Slice-1..4 facts from before still hold (fixed adjuster subjects, dev creds, E2E on
  claims_e2e/port 8082, no new E2E spec files while registrations share the realm).
