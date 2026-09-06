# Progress

Last updated: 2026-09-06

This file exists so a new session can pick up cold. Write it for someone who has never
seen this project. Rewrite it, don't append to it.

## Right now

**Slice 6 (claimant decision & notification — the claimant half of Flow 5) is done and all
green.** A claimant who opens their CLOSED claim now sees the decision: an approval shows
"Your claim has been approved. We will pay £…" with the amount (`claim.indemnity_amount`,
sent only when `decision = APPROVED`); a denial shows "Your claim has not been approved."
plus the rationale as remarks (`claim.decision_remarks`, stored verbatim from the denial
rationale by slices 4–5). The visibility wall still holds on closed claims: reserve, notes,
assignee, and coverage never reach this surface, structurally (`ClaimantClaimView` has no
such components, and the integration/E2E wire assertions pin it on CLOSED claims).

The growth of the claimant view was a deliberate shape change: the record is now
`(claimNumber, status, steps, decision, indemnityAmount, decisionRemarks)`, pinned by the
updated `ClaimantClaimViewTest.viewStructurallyCarriesOnlyPublicFields` and by journey 2's
wire assertions. The mapper guards each field to its decision (amount only when APPROVED,
remarks only when DENIED), and a class-level `@JsonInclude(NON_NULL)` (Jackson-2 compat
annotations, honored by this Boot-4/Jackson-3 mapper — probed empirically) omits the null
fields from the wire: an undecided claim's response is byte-identical to slice 5, an
APPROVED closure adds `decision` + `indemnityAmount`, a DENIED closure adds `decision` +
`decisionRemarks`. No migration: all needed columns have existed on `claim` since slice 4.

Decisions made this slice (recorded in `docs/decisions.md`): the CLOSED process-steps list
shows only the two stages every closure truthfully shared (FNOL received, under review) —
**never** a guessed "Escalated" step, because CLOSED cannot recall escalation history from
status/decision columns and the decision/closure machinery does not record it (reading the
DECISION audit `before.status` to reconstruct it was considered and rejected); the decision
renders as its own block, the terminal "→ decision" step. E2E journey 8 went into the
existing `queue.spec.ts` as one test covering both rendering branches (approved amount on an
adjuster-approval fixture, remarks on an adjuster-denial fixture) with journey-2-style wire
leak capture on the closed claims. The four slice-4/5 closure tests already asserted Mailpit
delivery on every closure actor — slice 6 did **not** duplicate that; it strengthened their
assertions in place for email content correctness (approval body states the amount, denial
body carries the remarks verbatim) and added the closed-claim claimant-view wire assertions
per closure actor to those same tests.

Backend **122 tests** (54 unit + 68 integration) and **10 E2E journeys** — all green
(2026-09-06, full local run: `mvn test` + Playwright against compose Keycloak/Mailpit).
Slice 6 has NOT yet had its fresh-context review (that belongs to a new session on the
strong model, per the workflow). Next up: **Slice 7 (compliance & admin)** — start only with
the user's go.

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
denial remarks — under the Process steps). Internal: **Adjuster queue** → sign in as a
provisioned adjuster (adjuster.one / adjuster.two = L1, adjuster.three = L2, password
`adjuster-Pass-123`) → **Open claim** on a row → full internal view with coverage, the
reserve form, the notes box, photo downloads, and the decision panel. **Supervisor**
(username `supervisor`, password `supervisor-Pass-123`): sign in → **Escalations** (nav) →
the supervisor escalation queue → **Review claim** → approve/deny with a rationale. (The
aging job only fires at 03:00 and needs claims 3+ days old — it is exercised at the
integration layer with fixed instants, not in a running dev app.) Ports 8081/8090 exist
because 8080 on this machine is taken. To see slice 6 live: close a claim (adjuster approve
or deny, or supervisor approve/deny), then open `/claim/<claimNumber>` in the claimant's
session.

## Tests

```bash
JAVA_HOME=/usr/lib/jvm/jdk-21.0.8-oracle-x64 mvn -o -f backend/pom.xml test  # 122: unit + integration
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
| 6 | Claimant decision & notification | **done** (2026-09-06) | no — review belongs to a fresh session |
| 7 | Compliance & admin | not started | no |

Slice 6 delivered, per `docs/plan.md`: the claimant-facing half of Flow 5 — the CLOSED
claim's claimant view now carries the decision (`ClaimantClaimView` grew
`decision`/`indemnityAmount`/`decisionRemarks`, null-omitted on the wire), the claim-status
screen renders the decision block (approved amount on APPROVED; denial + remarks on DENIED),
CLOSED claims show the two shared process steps (never a fabricated escalated step), and E2E
journey 8 pins both rendering branches end to end. The decision email was already sent on
every closure path (slices 4–5); this slice verified content correctness by strengthening
the existing Mailpit assertions in the four closure integration tests and asserted the
closed-claim claimant view on the wire per closure actor (adjuster approve/deny, supervisor
approve/deny), plus the owner-only rule on closed claims (401 anonymous / 404 other
claimant) and that open undecided claims stay byte-identical. Deliberate decisions recorded
in `docs/decisions.md` (2026-09-06 slice-6 entry): closed-steps truthfulness (no recalled
escalation history), the grown view shape with mapper guards + `@JsonInclude(NON_NULL)`,
journey 8 in the existing spec file, email assertions strengthened in place rather than
duplicated, and the closed-claim access rule re-pinned.

## Starting Slice 7 (fresh session)

Read first: `docs/plan.md` (slice 7 + the API-table rows it adds + the route table),
`docs/requirements.md` (Flow 7 / compliance rows), `docs/decisions.md` (2026-09-06 slice-6
and slice-4/5 entries), and `rules/yagni.md` + `rules/testing-web.md`. Slice 6 is done and
green but has NOT been reviewed; per the workflow, review it first in a fresh-context session
on the strong model (deepseek-v4-pro) before or alongside slice 7.

**Slice-7 scope (from the plan, with the current state of each piece):**
1. **Compliance & admin** — supervisor edits authority config, views the audit log, and
   reassigns claims; "100% of decisions have recorded actor + rationale". The plan API rows:
   `GET /api/config/authority` + `PUT /api/config/authority/{productCode}` (SUPERVISOR), the
   `GET /api/claims/{claimNumber}/audit` row (SUPERVISOR), the
   `POST /api/claims/{claimNumber}/reassign` row (SUPERVISOR), the `/admin/authority` route.
   The route table already lists `/admin/authority` (SUPERVISOR).
   - Today `authority_config` is seeded (V6) with uniform 2500/10000 thresholds, read in
     every claim path via `AuthorityConfigRepository.findAll()` (a stream filter on product
     code in `ClaimDecisionService`); `audit_log` is append-only (writer, JSONB payloads,
     actor + rationale) with per-claim reads never exposed — there is no read endpoint; the
     reassign endpoint does not exist (re-assignment happens only inside the slice-4/5/6
     decision & aging paths through `ClaimAssigner`).
   - Watch-outs: config edits must feed the authority gate immediately (the gate reads rows
     per decision — but any in-memory caching would need invalidation); audit-log
     immutability is enforced at the data layer (append-only writer + DB trigger/privileges?
     — the plan says "reject UPDATE/DELETE", decide where that enforcement lives); the
     `reassign` endpoint must respect the 404-not-403 rule and the role-driven-queue facts;
     supervisor identity still has no `app_user` row (an audit GET/reassign by supervisor
     must not require one).
2. **Tests** (from the plan): integration — config endpoints auth + effect on the gate;
   audit-log append-only; reassign endpoint (auth + assignee changes + audit logged);
   E2E — config edit reflected in classification/gate. Journey 9 will need a supervisor
   session (journey 7/8 helpers in `queue.spec.ts` — keep using the existing spec file per
   the standing decision).
3. **api.http** gains working examples for the new endpoints; **docs/decisions.md** records
   any open calls; **docs/progress.md** is rewritten again for cold pickup.

**Test counts to update everywhere:** backend 122 (unit 54 + integration 68) and E2E 10.

## Test counts

Unit: 54 · Integration: 68 (incl. context smoke) · E2E: 10 · All green: yes (2026-09-06)

- Unit 54: PolicyViewMapper 4 · ClaimClassifier 3 · LoadBalancer 5 · ClaimantClaimView 9
  (slice 6: structural-shape update + approved/denied/undecided decision mapping +
  CLOSED-steps case) · ClaimNumberFormatter 1 · AuditJson 1 · AuthorityGate 16 ·
  AgingPolicy 15.
- Integration 68: FnolApi 11 · AssignmentQueue 10 · ClaimWork 15 · ClaimDecision 13 (slice 6
  added the closed-claim owner-only test: 401/404 on a closed claim; the approval and denial
  closure tests now assert the closed claimant view on the wire — decision/amount or
  remarks, wall intact — and the email body content) · EscalationDecision 9 (slice 6:
  supervisor approval/denial closure tests now assert the closed claimant view on the wire
  + email body content; the escalated-open-claim test asserts no decision content on an
  undecided claim) · Aging 8 · PolicyApi 1 · context smoke 1.
- E2E 10: skeleton page · journey 1 (register → FNOL w/ photo → claim number) ·
  rejected-FNOL-stays-on-form · journey 2 (claimant status screen: no reserve/notes on
  screen or wire) · journey 3 (claim in exactly one L1 queue, never L2) · journey 4
  (assigned adjuster sets a reserve, adds a note, downloads the photo) · journey 5
  (adjuster approves a within-limit amount → claim closes and leaves the queue) · journey 6
  (above-limit approval blocked + escalated to the L2 adjuster) · journey 7 (above-L2
  approval escalates to the supervisor; the supervisor signs in through the real realm and
  approves with rationale → closes and leaves the escalation queue) · **journey 8** (slice
  6: the claimant reopens a CLOSED claim and sees the decision — £1500.00 on an
  adjuster-approval fixture, the remarks verbatim on an adjuster-denial fixture — with
  journey-2-style wire-leak capture on the closed claims).

Slice 6 is green but unreviewed. Slices 4–5 went through their fresh-context reviews (see
`docs/decisions.md`, 2026-09-06 review entries; slice-5 no blocking findings).

## Blocked on

- Nothing. Slice 6 is done and green; slice 7 awaits the user's go (and slice 6's
  fresh-context review comes first, in a new session).

## Notes for whoever picks this up

- Docs: concept `docs/claims-product-concept.md`; requirements `docs/requirements.md`;
  plan `docs/plan.md`; decisions `docs/decisions.md` (2026-09-06 slice-6 entry on top,
  then slice-5 review + slice-5 decision entries).
- Load-bearing rules now six plus the wall's slice-6 extension: the **visibility wall**
  (slices 1–3, holds on CLOSED — decision fields are the *only* addition to the claimant
  view; reserve/notes/assignee/coverage still never reach it), the **404-not-403**
  object-access rule (re-pinned on closed claims in slice 6), the **authority gate**
  (slice 4), the **single-payment/closure atomicity** rule (slice 4), the **role-driven
  queue** (slice 2), and the **aging ladder** (slice 5).
- Slice-6 backend shape: `ClaimantClaimView` grew to `(claimNumber, status, steps,
  decision, indemnityAmount, decisionRemarks)` with mapper guards (amount only when
  APPROVED, remarks only when DENIED) and class-level `@JsonInclude(NON_NULL)`. That
  annotation is the **Jackson-2 compat package** (`com.fasterxml.jackson.annotation`), NOT
  `tools.jackson.annotation` (which does not exist on this classpath — verified): Boot 4.1.1
  runs Jackson 3 (`tools.jackson.databind`) but still ships jackson-annotations 2.x and
  honors its annotations. Without the annotation this Boot mapper writes **explicit nulls**
  (probed: `{"decision":"APPROVED","indemnityAmount":null,...}`), which would have grown the
  open-claim wire with `"decision":null` and broken the byte-identical open-claim contract.
- The decision email content (approval "We will pay £X", denial carrying the remarks
  verbatim) is asserted inside the four closure integration tests against Mailpit — do not
  add a fifth delivery test unless a new closure actor appears.
- CLOSED `stepsFor`: two shared steps, never "Escalated" (see decisions.md). If a future
  slice needs the escalated step on closed claims, it needs a recorded
  escalation-history source (column or audit read) — that decision is already recorded.
- The claim-status screen (`frontend/src/app/claim-status/`) renders the decision block with
  data-testids `claim-decision-approved` / `claim-decision-denied` /
  `claim-decision-amount` / `claim-decision-remarks`; journey 8 (queue.spec.ts) asserts
  `£1500.00` and the remarks verbatim.
- Scheduler determinism: `AgingScheduler` is `@ConditionalOnProperty("claims.aging.enabled")`
  (main properties: true, cron daily 03:00, `@EnableScheduling` on the app class);
  `ClaimTableResettingTest` disables it in claim-writing tests via an inherited
  `@DynamicPropertySource`. Do NOT reintroduce a `src/test/resources/application.properties`
  — it shadows the main file wholesale and breaks property resolution (learned slice 5).
- `claim.created_at` is NOT entity-mapped (slice-3 review dropped the mapping); the aging
  candidate query reads it via JDBC. Aging locks each candidate row with
  `ClaimRepository.findByClaimNumberForUpdate` (the slice-4 lock) before deciding.
- Keycloak: the realm has four provisioned users — three adjusters and the supervisor (who
  has NO app_user row and no V-seed). `keycloak/realm-export.json` imports on Keycloak boot;
  if you edit it, recreate the container (`docker compose rm -sf keycloak && docker compose
  up -d keycloak`) so E2E sees the change. The realm-sync seam test
  (AssignmentQueueIntegrationTest) counts exactly the three adjuster-role users.
- `api.http` documents the whole surface, including the slice-6 claimant-status response
  shape (decision fields only once CLOSED and decided) and the four decision endpoints.
- Slice-1..5 facts from before still hold (fixed adjuster subjects, dev creds, E2E on
  claims_e2e/port 8082, no new E2E spec files while registrations share the realm).
