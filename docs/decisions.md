# Decisions

Short records of choices that would otherwise get re-argued, plus the things we decided
not to build. Newest first.

## Decisions

### 2026-09-06 — Slice-6 fresh-context review findings applied (deepseek-v4-pro)

**Context:** A fresh-context review of slice 6 (run on the strong model in a new agent
session, per phase 05) found **no blocking and no should-fix issues**: the slice satisfies
its acceptance criteria, every criterion has a test that fails on regression, and the
scope question ("what was built that the plan didn't ask for?") came back empty beyond the
brief + recorded decisions.
**Applied (optional):** (O1) The `ClaimantClaimView` javadoc's first paragraph listed
"remarks" among the internal fields the view "structurally omits", which reads
ambiguously next to the newly exposed `decisionRemarks` component — it meant the
claimant's FNOL remarks. The list item now says "claimant remarks". Comment-only; no
re-verification needed.
**Deferred (optional, no behavioral risk):** (O2) The journey-8 E2E wire-leak capture
(`watchClaimantWire` in `queue.spec.ts`) reads each claimant-status response in an
un-awaited `page.on('response')` handler and asserts the captured list only at the end —
mirrors the pre-existing journey-2 pattern and cannot produce false *failures*, but it is
a latent false *negative* (a body not yet read would silently miss a leak; the real wall
guarantee lives in the deterministic integration/structural assertions). Build it when
`queue.spec.ts` is next touched: collect the status response via an awaited
`page.waitForResponse`/route capture instead.
**Verified:** reviewer re-ran the targeted slice-6 classes (31 tests: ClaimantClaimView 9,
ClaimDecision 13, EscalationDecision 9) + the frontend build, both green, and
cross-checked the Flow-5 acceptance-criterion table against the tests.

---

### 2026-09-06 — Slice-6 decisions: the closed claim's claimant view (decision display)

**Context:** Slice 4 deferred the claimant-view decision fields here deliberately, and the
slice-6 brief left four shape questions open ("decide and record"): what the process-steps
list shows for a CLOSED claim when `stepsFor` derives from status alone and CLOSED cannot
recall whether the journey passed through `ESCALATED_SUPERVISOR`; the exact grown shape of
`ClaimantClaimView`; whether the decision email needs new end-to-end coverage; and where
E2E journey 8 lives.
**Decision:**
- **Closed process steps: the two stages every closure truthfully shared, never a guessed
  "Escalated" step.** `stepsFor("CLOSED")` returns *FNOL received* + *Under review* only.
  The decision/closure columns do not record whether the claim was supervisor-escalated, so
  no closed view can truthfully show "escalated (when applicable)" from the claim row —
  showing it for every closure would misrepresent never-escalated claims. Reconstructing
  history from the `DECISION` audit row's `before.status` was considered and rejected:
  reading the append-only log into a claimant surface, per GET, for a history nicety, is
  cost without a requirement. The terminal "→ decision" step is the decision itself, which
  the screen renders from the decision fields as a block (amount or remarks) — richer than
  a status-only list line could be, and outcome-specific (which a pure status projection
  cannot produce). *Revisit if* closure ever records escalation (e.g. a `was_escalated`
  flag) or claimants ask for their full timeline.
- **`ClaimantClaimView` shape (slice 6): decision fields always on the record, null when
  not applicable; omitted from the wire when null.** The record grows to
  `(claimNumber, status, steps, decision, indemnityAmount, decisionRemarks)`. The mapper
  guards each field to its decision — `indemnityAmount` only when `decision = APPROVED`,
  `decisionRemarks` only when `decision = DENIED` — so the plan's "approved amount present
  only when APPROVED" rule is structural at the view boundary, not a serialization
  accident. The record carries `@JsonInclude(NON_NULL)` (Jackson-2 compat annotations,
  honored by this Boot-4/Jackson-3 mapper — probed empirically): the wire omits null
  fields, so an undecided claim's response is **byte-identical to slice 5** (journey 2 and
  the open-claim wall tests did not need changing), an APPROVED closure adds
  `decision` + `indemnityAmount`, and a DENIED closure adds `decision` +
  `decisionRemarks`. The structural wall test
  (`viewStructurallyCarriesOnlyPublicFields`) and the journey-2 wire assertions were
  extended deliberately with this shape, per the repo's no-dead-surface and
  no-accidental-growth rules.
- **Decision email: no new delivery test — the four slice-4/5 closure tests already pin
  Mailpit delivery on every closure actor (adjuster approve/deny,
  supervisor approve/deny).** Slice 6 *strengthened those assertions in place* to cover
  the plan's "email content correctness" risk: the approval email body states "We will
  pay" plus the amount, the denial email carries the rationale as remarks verbatim. Those
  same four tests now also fetch the closed claim's claimant view and assert the decision
  fields on the wire per closure actor, plus the wall (no reserve/notes/assignee/coverage)
  and the closed steps — extending, not duplicating, the existing wall assertions.
- **E2E journey 8 lives in `queue.spec.ts`** (existing file, per the standing decision —
  no new spec file adds a parallel worker's Keycloak registration traffic). One test
  exercises **both** rendering branches: an adjuster-approved claim (the claimant sees
  `£1500.00`) and an adjuster-denied claim (the claimant sees the remarks verbatim),
  with journey-2-style wire-leak capture on the closed claims. The supervisor-closed wire
  path stays at the integration layer: the browser rendering is decision-agnostic, so
  driving a third closure through the UI would buy nothing E2E-specific.
- **Closed-claim access is unchanged and re-pinned:** the claimant status endpoint's
  owner-only rule (401 anonymous, 404 another claimant — never 403) is status-independent
  and already pinned on open claims; a closed-claim variant test was added so the rule has
  an explicit regression guard on the surface this slice changed.
**Why:** Each choice keeps the closed view truthful without new columns or audit-log
coupling, keeps the open-claim wire pristine, pins every acceptance criterion to a test
that fails on regression, and lands the only genuinely new user-visible behavior (the
decision block) with its E2E journey — while reusing every existing fixture instead of
duplicating it.
**Deferred:** A recorded escalation flag or audit-derived history for closed claims (see
the steps decision); per-claim "what happens next" mail content beyond the closure email.

---

### 2026-09-06 — Slice-5 fresh-context review findings applied (deepseek-v4-pro)

**Context:** A fresh-context review of slice 5 (run on the strong model in a new agent
session, per phase 05) found **no blocking issues**: the slice satisfies its acceptance
criteria and every criterion has a test that fails on regression. One should-fix and three
optionals were reported.
**Fixed:** (S1) The aging comment in `application.properties` pointed at a nonexistent
`src/test/resources/application.properties` — the exact file this slice learned must not
exist (it shadows the main properties wholesale). The comment now names the real mechanism
(the inherited `@DynamicPropertySource` on `ClaimTableResettingTest`) and warns against
reintroducing the file. Comment-only; no re-verification needed.
**Deferred (optional, no behavioral risk):**
- (O1) `AgingService.reassignToL2` stamps `assigned_at` with the wall clock (inside
  `ClaimAssigner.assign`) rather than the injected aging instant. Harmless — production
  `now` equals the wall clock and tests only assert non-null — but it is the one
  non-injected time in an otherwise deterministic pass. Build it when aging timestamps ever
  need to be deterministic.
- (O2) The escalation-decision and aging paths reuse slice 4's `FOR UPDATE` read but carry
  no deterministic held-lock test of their own (their second-operation tests are
  sequential). Not a real gap: slice 4 pins the identical lock deterministically
  (`decisionBlocksWhileAnotherTransactionHoldsTheClaimRowLock`). Build it when those paths
  diverge from the shared lock.
- (O3) A supervisor opening a non-escalated team claim sees an empty "Decision" panel
  heading (no `canDecide()`, not CLOSED). Correct behavior; only cosmetic. Build it when
  the claim-detail screen copy is touched again.
**Verified:** reviewer ran the targeted slice-5 classes (49 tests) + the frontend build,
both green, and cross-checked the acceptance-criterion table against the tests.

---

### 2026-09-06 — Slice-5 decisions: supervisor escalation-decision, aging, and supervisor identity

**Context:** docs/plan.md and the slice-5 brief left several shape questions open ("decide and
record"): what the supervisor's no-self-approval obligation means when supervisors never
create escalations; the eligibility/error rules for the escalation-decision endpoint; how a
supervisor payment/audit records identity with no app_user row; how the supervisor is
provisioned for E2E; and the exact aging-ladder semantics under clock jumps.
**Decision:**
- **A supervisor approval is not amount-gated, and no-self-approval is structural.**
  `ESCALATED_SUPERVISOR` claims are produced only by an adjuster's above-L2 approval attempt
  or the no-L2 fallback (slice 4) and by the aging job (slice 5). A supervisor can never be
  an assigned adjuster (`app_user.level` is L1/L2-only) and never acts on the slice-4
  decision endpoint, so no supervisor can be the actor on any path that escalates to them —
  nobody decides an escalation they caused, the same structural reasoning as slice 4. No
  extra check was written. *Revisit if* a Keycloak user could ever carry both an adjuster
  role and supervisor, or the supervisor gains an adjuster-level acting surface.
- **Eligibility and errors on `escalation-decision`.** Only claims actually in
  `ESCALATED_SUPERVISOR` are decidable there: unknown claim → 404 (never reveals
  existence); a claim in another open state → 400 "This claim is not awaiting a supervisor
  decision" (a supervisor can see team claims, so the state — not existence — is the
  problem, and the message is actionable); a decided claim → 400 "already been decided"
  (mirrors slice 4). The request body reuses the slice-4 shape (`decision`/`rationale`/
  `indemnityAmount`, rationale required for approve and deny, amount rules via
  `AuthorityGate.validate`), and the response reuses `ClaimDecisionView` (`escalatedTo` is
  always null — a supervisor decision closes).
- **Supervisor payment + audit identity: NULL `authorized_by`, subject as audit actor.**
  A supervisor token has no app_user row (the cache is L1/L2 adjusters only and the level
  CHECK forbids a supervisor row), so a supervisor approval records the single payment with
  `payment.authorized_by_id = NULL` while the `DECISION` audit row carries their Keycloak
  subject as `actor_sub` (before = `{status: ESCALATED_SUPERVISOR, level}`). Same
  nullable-author pattern as `internal_note.author_id` (slice-3 note decision) and the
  actor-less system rows.
- **Supervisor provisioned in Keycloak only** (the deferred slice-3/4 item, now triggered
  by journey 7 needing a real supervisor login): `keycloak/realm-export.json` gains a
  fixed-subject supervisor — id `10000000-0000-0000-0000-000000000004`, username
  `supervisor`, role `supervisor`, dev password `supervisor-Pass-123` — with **no app_user
  row** and no V-seed change. The realm-sync seam test still counts exactly the three
  adjusters (it filters realm users by adjuster roles), and `app_user` still holds exactly
  the provisioned adjusters.
- **Aging-ladder semantics** (Flow 6, anchor = `claim.created_at`, i.e. FNOL):
  - *3-day rung* — applies to claims still below the L2 tier (level L1, or `UNASSIGNED`):
    the same funnel as the slice-4 L2 escalation — level becomes L2, the least-loaded L2
    adjuster takes it via `ClaimAssigner`, and with no L2 adjuster provisioned it falls
    back to `ESCALATED_SUPERVISOR` (level reverted) exactly like the slice-4 gap rule. A
    claim already held at L2 is NOT re-pushed at day 3: it already satisfies the rung.
  - *5-day rung* — applies to any open claim and **wins over the 3-day rung**: a single run
    after a missed schedule or a clock jump lands the claim at the top of the ladder in one
    idempotent move (never "L2 first, supervisor later").
  - `CLOSED` and `ESCALATED_SUPERVISOR` claims are never re-touched. Every transition
    writes a `CLAIM_ESCALATED` audit row with `actor_sub NULL` (a system action, like
    `CLAIM_ASSIGNED`), before/after payloads matching the slice-4 escalation rows
    (escalatedTo L2/SUPERVISOR + assignee), and a rationale naming the rung ("Aged at
    least 3/5 days without a decision") so the trigger is self-describing in the immutable
    log.
- **Injecting the clock / keeping the cron out of tests.** The scheduled shell
  (`AgingScheduler`, cron daily 03:00, `@EnableScheduling`,
  `@ConditionalOnProperty("claims.aging.enabled")`) calls
  `AgingService.ageClaims(Instant)`; the public method takes an explicit instant, which IS
  the injection seam — tests pass fixed instants and never wait on a cron (no Clock bean:
  one caller). Claim-writing integration classes disable the scheduler through an
  inherited `@DynamicPropertySource` on `ClaimTableResettingTest`
  (`claims.aging.enabled=false`), so a real scheduled run can never fire mid-suite against
  backdated claims. `claim.created_at` is read via JDBC (it is not entity-mapped — the
  slice-3 review removed the mapping); each candidate's row is then locked with the
  slice-4 `FOR UPDATE` read and the step recomputed against the freshly locked state, so a
  concurrent decision or a second job run never double-transitions a claim.
- **Escalation queue shape.** `GET /api/escalations` (SUPERVISOR-only URL rule) reuses the
  queue row shape via `QueueService.supervisorEscalationQueue()` (status filter, oldest
  first, assignee always null) — no new escalation DTO.
- **E2E journey 7 was added to `queue.spec.ts`** (existing file, per plan): a new spec
  file would add a parallel worker's Keycloak traffic. The aging ladder itself stays at
  the integration layer with the injected clock (plan: E2E for aging only if time
  injection is cheap — it is not a user-visible journey here).
**Why:** Each choice resolves an ambiguity the brief flagged, keeps the supervisor surface
small by reusing the slice-4 machinery and shapes, and records identity the way the schema
allows (no supervisor app_user row exists or may exist).
**Deferred:** The aging E2E journey (clock injection in the browser is not cheap; the
claimant-visible escalated step is asserted end to end only when a claim reaches
`ESCALATED_SUPERVISOR`, which journey 7's fixture does produce). Decision *display* on
closed claims remains slice 6 (`stepsFor` for CLOSED is unchanged; the escalated step for
`ESCALATED_SUPERVISOR` lands now because Flow 6 makes aging claimant-visible).

---

### 2026-09-06 — Slice-4 fresh-context review findings applied (deepseek-v4-pro)

**Context:** A fresh-context review of slice 4 (run on the strong model in a new agent
session, per phase 05) found one blocking defect, two should-fix coverage gaps, and four
optional cleanups. All were accepted and fixed in one round.
**Fixed:** (B1) The no-L2-adjuster escalation fallback built its audit `after` payload with
`Map.of`, which forbids null values — when `ClaimAssigner` returned null (no L2 adjuster
provisioned) the documented fallback to `ESCALATED_SUPERVISOR` threw NPE and rolled the
whole decision back, leaving the claim with the actor whose authority was rejected. The
payload is now built with a null-tolerant map. (S1) The fallback path is now covered by an
integration test that deletes the L2 adjuster, escalates an above-L1/within-L2 approval,
and asserts `ESCALATED_SUPERVISOR` + no assignee + a `CLAIM_ESCALATED` audit row
(`escalationAboveL1WithNoL2AdjusterProvisionedGoesToTheSupervisor`), restoring the L2
adjuster in `finally` (the slice-2 gap-test pattern). (S2) The decision row lock's
serialization claim is now pinned by a deterministic held-lock test: a raw connection holds
`SELECT … FOR UPDATE` on the claim row and a concurrent decision must block until the lock
is released (`decisionBlocksWhileAnotherTransactionHoldsTheClaimRowLock`) — it fails if the
`PESSIMISTIC_WRITE` read were removed. (O1) `ClaimDecisionView.status` had no consumer
(dropped; the wire result still carries decision/escalatedTo/indemnityAmount/remarks).
(O2) The duplicate `DecisionRequest`/`ClaimDecisionInput` records collapsed into
`ClaimDecisionInput` as the `@RequestBody` type. (O3) `CLAIM_ESCALATED` audit `before`
payloads now record `assignedAdjusterId` on both the L2 and supervisor escalation paths.
(O4) The no-L2 fallback now reverts the claim level before `escalateToSupervisor()`, so
both routes to `ESCALATED_SUPERVISOR` leave the claim's routing level unchanged.
**Verified:** decision integration class 12 tests green; full backend suite re-run green.

---

### 2026-09-06 — Slice-4 decisions on the decision endpoint's open choices

**Context:** docs/plan.md and the slice-4 brief left several shape questions open ("decide
and record"): where denial remarks come from; what an escalation to L2 does when no L2
adjuster is provisioned; who is the actor on the escalation audit row; whether the
claimant view grows decision fields in slice 4; and which E2E file carries journeys 5–6.
**Decision:**
- **DENIED remarks = the rationale.** The request body has one text field (`rationale`,
  required for approve and deny alike); on a denial it is stored verbatim as the
  claimant-visible `decision_remarks`. No separate remarks field. (A claimant-facing
  remarks *rendering* is slice 6; the wall still holds — the field rides the claim row,
  not any slice-4 claimant response.)
- **Escalation to L2 with no L2 adjuster provisioned falls back to `ESCALATED_SUPERVISOR`.**
  If the claim is above the actor's level but within the L2 limit, the system re-assigns to
  the least-loaded L2 adjuster via `ClaimAssigner` (same row-lock as FNOL). If none exists
  (the provisioning gap slice 2 pins for FNOL), the claim must not stay with an actor whose
  authority was just rejected — it escalates to the supervisor with a warning instead.
- **`CLAIM_ESCALATED` audit rows are attributed to the adjuster whose above-limit attempt
  triggered them** (actor + rationale), unlike `CLAIM_ASSIGNED` (actor-less system action).
  Rationale: an escalation is caused by a specific internal user deliberately requesting an
  out-of-authority approval; recording who and why is the compliance-relevant fact. No
  `DECISION` row is written for an escalation — nothing was decided.
- **The claimant view stays unchanged in slice 4** (number/status/steps only). The plan's
  claimant-view `decision`/`indemnity_amount`/`remarks` fields land in slice 6, which
  renders them; shipping them now would be dead DTO surface with no consumer, and the
  structural wall test pins the record's exact shape.
- **E2E journeys 5 and 6 were added to `queue.spec.ts`**, not a new spec file. E2E is not
  fully parallel because concurrent Keycloak registration flows are flaky; a new file would
  add a new parallel worker's registration traffic. The helper duplication stays two copies
  (fnol.spec + queue.spec), not three, so no extraction yet.
- **Decision email is sent only on closure** (approve or deny), never on escalation — an
  escalation is not a decision. Best-effort after commit, to the verified policy-holder
  address, like the FNOL/assignment emails.
- **Seed thresholds (V6): 2500.00 / 10000.00** for both seeded product codes (HOME, AUTO).
  Uniform values keep the gate matrix and E2E amounts legible; per-product differentiation
  is a config concern for slice 7, not seed data.
**Why:** Each choice resolves an ambiguity the plan flagged, keeping the API body to one
text field, the claimant surface clean until slice 6, and the audit trail attributable.

---

### 2026-09-06 — Slice-3 review fixes (fresh-context agent review)

**Context:** A fresh-context review of slice 3 (commit 82cb34e) found no blocking issues
but six should-fix findings. All six were accepted and fixed.
**Fixed:** (S1) The reserve is now validated against its `NUMERIC(14,2)` column before
write — >2 decimal places and amounts over 999999999999.99 are rejected with a 400
instead of the DB rounding silently (response/storage divergence) or the commit failing
with a 500. (S2) The first `@RequestBody` endpoints gained client-error mapping:
malformed/unreadable bodies and wrong-typed path values map to 400 and unsupported
methods to 405 instead of the generic 500 catch-all. (S3) The supervisor-writes-note
path — the case that justifies `internal_note.author_id` being nullable — is now covered
by an integration test asserting a 200 with `author: null` and a NULL stored author.
(S4) Per-endpoint negative coverage was added across status/reserve/notes/attachments:
401 anonymous, 403 wrong role, and 404 unknown-claim on every endpoint, plus zero-reserve
acceptance. (S5) The `RESERVE_SET` audit rows' before/after payloads are now asserted, not
just counted. (S6) Dead DTO surface removed: `InternalClaimView.createdAt`,
`AttachmentView.contentType`, and `NoteView.createdAt` had no consumer and were dropped
along with the read-only `claim.created_at` entity mapping they existed for.
**Open:** the review's six optional items were not applied (user scope) and are recorded
in the Deferred section below; a strong-model pass remains optional.

---

### 2026-09-06 — Slice-3 notes: author_id nullable; notes not audited

**Context:** Flow 3 lets the assigned adjuster write internal notes on a claim. The plan
model shows `internal_note.author_id` FK → `app_user`, and audit C1 covers status changes
and decisions.
**Decision:** `internal_note.author_id` is nullable: a SUPERVISOR token may have no
`app_user` (staff-cache) row yet, and forcing one per supervisor would either fabricate
cache entries or require provisioning supervisors before slice 5. Their identity still
rides the audit trail where the plan requires it. Note *adds* are not written to the audit
log — the note rows themselves are the record; financial/state changes (claim created,
assigned, reserve set, later decisions) are what get audited.
**Why:** Notes are content, not state; auditing each one would duplicate the table it
lives in.

---

### 2026-09-06 — Reserve is set/updated via PUT and audited; not authority-gated

**Context:** Flow 3: "set/update a reserve". Plan: reserve is internal, never
claimant-visible, and NOT authority-gated (only the indemnity/payment is).
**Decision:** `PUT /api/claims/{claimNumber}/reserve` takes `{amount}` (≥ 0, else 400)
and returns the updated internal view; every change writes a `RESERVE_SET` audit row
(before/after amounts, actor). The assigned adjuster or a supervisor may set it — no
authority gate on purpose.
**Why:** A reserve is an internal estimate; gating it would be building the authority
mechanism for the wrong field.

---

### 2026-09-06 — Photos are served as attachments; the 404 rule now has real endpoints

**Context:** Slice-1 review deferral (a): serve photos with `Content-Disposition:
attachment` and don't trust client content types for rendering. Slice-2 deferred the
"404 for non-assignee" integration case because no per-claim internal endpoint existed.
**Decision:** Photo downloads are always `attachment` (never inline — untrusted bytes must
not render in the adjuster's browser), with the stored filename scrubbed of CR/LF/quotes
so a hostile original name cannot smuggle header content; the stored content type is kept
as the entity's media type (informational — the attachment disposition is the control).
The 404-for-non-assignee rule now lands on the slice-3 per-claim endpoints (claimant
status, full view, reserve, notes, attachments): a real claim someone else owns is 404,
never 403.
**Why:** Serving inline would create a stored-XSS vector; the plan's 404-not-403
authorization rule needed an object-level endpoint to apply to.

---

### 2026-09-06 — Policy coverage stays unmapped; read as JSON for the internal view

**Context:** Slice-0 deliberately left `policy.coverage` (JSONB) unmapped. Slice 3's full
view must show coverage to the adjuster.
**Decision:** Coverage is read with a parameterized JDBC query (`coverage::text`) and
parsed to JSON for the internal view — the entity stays unmapped (nothing writes it), and
the internal view is the only consumer.
**Why:** Mapping a read-only JSONB column on the entity just to serialize it back adds
mapping surface for no write path.

---

### 2026-09-06 — Slice-2 review fixes (fresh-context agent review)

**Context:** A fresh-context review of slice 2 (commit 27561e7) found no blocking issues
but five should-fix findings. All five were accepted and fixed in a follow-up commit.
**Fixed:** (S1) The two-thread HTTP concurrency test could pass even with the
`FOR UPDATE` lock removed (no barrier forced the transactions to overlap); it was
replaced with a deterministic test that holds the level's candidate rows locked on a raw
connection and asserts the FNOL's assignment blocks until the lock is released — it fails
if the lock disappears. (S2/S4) The hand-synced seam between `app_user` seeds (V4) and the
provisioned Keycloak users (`keycloak/realm-export.json`) is now pinned by an integration
test that cross-checks every staff user (subject, level, email, display name) against the
realm export; and an adjuster token whose subject has no `app_user` row now logs a warning
instead of silently returning an empty queue. (S3) The no-adjuster-of-level provisioning
gap (claim stays UNASSIGNED, no CLAIM_ASSIGNED row, no assignment email) is now covered by
an integration test that deletes the L2 adjuster and restores it in `finally`. (S5) The
per-test truncation of claim/attachment/audit_log moved from a per-class convention into a
shared base (`ClaimTableResettingTest`) that every claim-writing integration class extends.
**Optional items from the review were deliberately not applied** (user scope): tie-break
comparator style, the phantom UNASSIGNED status in the CLAIM_CREATED audit payload, the
public queue nav link, `QueueClaimView.createdAt`, step copy, E2E spec duplication, and
dev-credential hardening — see the Deferred section below.
**Open:** a strong-model pass remains optional (this review used the in-harness
fresh-context agent, like slice 1).

---

### 2026-09-06 — Slice-2 assignment runs inside the FNOL transaction

**Context:** Plan slice 2: new claims move to UNDER_REVIEW and are load-balanced to the
least-loaded adjuster of their level (fewest open claims, lowest `app_user.id` on ties),
atomically. There is no separate "assign later" endpoint in the slice.
**Decision:** Assignment happens in the creating transaction (the claim is never
observable as UNASSIGNED when adjusters of its level exist). Atomicity under simultaneous
FNOLs: the candidate adjusters' rows are locked (`SELECT ... FOR UPDATE`) before counts
are read, so concurrent FNOLs to one level serialize and the second recounts after the
first commits. Selection itself is a pure function (`LoadBalancer`), unit-tested; the
serialization is exercised by a two-thread integration test asserting distinct assignees.
**Why:** The plan's risk list names this exact race ("make assignment atomic"); row
locking is the smallest mechanism that is actually atomic.
**Rejected:** A global advisory lock (serializes every FNOL across both levels); an
asynchronous assignment worker (no queue infra, and the claimant email must carry the
assignee immediately).

---

### 2026-09-06 — app_user carries the routing level

**Context:** The plan model lists `app_user` with identity fields only ("role comes from
Keycloak, not stored here"), but slice-2 assignment must pick L1 vs L2 adjusters at claim
time, and the backend is a resource server — it never calls Keycloak's admin API.
**Decision:** `app_user` gains a `level` column (L1/L2), seeded in V4 to mirror the
provisioned Keycloak role of each staff user. It is a cache projection, exactly like
`display_name`/`email`, kept in step with the Keycloak role.
**Why:** Without it, load-balancing within a level is impossible without an admin-API call
per claim.
**Rejected / deferred:** Auto-populating `app_user` rows at login ("on login" per plan) —
adjusters are provisioned and seeded, so no first-login write is needed yet; if staff
profiles ever drift, the login hook is the sync point (see Deferred).

---

### 2026-09-06 — Assignment email to the claimant; assignee identity stays off the screen

**Context:** Flow 2: "an assignment email is sent with who to contact" and the claimant's
process-steps screen updates. Plan: process steps "never exposes the … internal assignee".
**Decision:** The assignment email goes to the verified policy-holder address and names the
adjuster (display name + email) — that is the "who to contact". The claimant-view steps
for UNDER_REVIEW say an adjuster is assigned but name no one. Like the FNOL email, sending
is best-effort after commit (a mail outage must not lose the assignment).
**Why:** Requirement text puts contact in the email; the plan's visibility rules keep
identity off claimant surfaces (email is one-way and not a claimant-facing screen).

---

### 2026-09-06 — Keycloak staff users provisioned with fixed subjects

**Context:** Adjusters cannot self-register; E2E logs them in as provisioned users, and the
backend's "own queue" join keys on `app_user.keycloak_sub = JWT subject`.
**Decision:** `keycloak/realm-export.json` now imports three staff users (two L1, one L2)
each carrying an explicit fixed `id`, and V4 seeds `app_user.keycloak_sub` with those same
values. Verified empirically that realm import honors explicit user ids, so subjects are
deterministic in dev, E2E (`claims_e2e`), and CI alike.
**Why:** A random subject per import would make "own queue" untestable end to end.

---

### 2026-09-06 — "404 for non-assignee" integration case deferred to slice 3

**Context:** Plan slice 2 says integration tests cover "queue + assignment auth rules
(incl. 404 for non-assignee)". Slice 2 exposes no per-claim internal endpoint — the full
claim view that a non-assignee could request arrives in slice 3 — so the 404 case has
nothing to attach to.
**Decision:** Slice 2 integration pins the queue's real rules: role gating (401 anonymous,
403 claimant), own-vs-team visibility, ordering, and empty queue. The cross-tenant
404-for-non-assignee assertion ships with the slice-3 full-view endpoints, where it is
testable.
**Why:** Building a per-claim endpoint early just to carry one 404 test would pull slice 3
forward and violate the slice boundary.

---

### 2026-09-06 — CLAIM_ASSIGNED audit rows are actor-less system actions

**Context:** C1: "every status change and decision is logged with actor and timestamp."
Assignment is triggered by a claimant's FNOL but performed by the system.
**Decision:** The audit writer records `CLAIM_ASSIGNED` with `actor_sub` NULL (system),
`after` holding claim number, assignee id + display name, and the new status; `CLAIM_CREATED`
keeps the claimant as actor. The schema already allows a NULL actor.
**Why:** Crediting the claimant with an internal assignment (or fabricating an adjuster as
the actor) would misattribute the action.

---

### 2026-09-06 — Slice-1 review fixes (fresh-context agent review)

**Context:** A fresh-context review of slice 1 found one blocking CI issue and eight
should-fix items. All were accepted and fixed; this entry records the consequential ones
and the optional items deliberately left open.
**Fixed:** CI E2E job now starts Keycloak (`docker compose up -d db mailpit keycloak` +
realm poll — previously Keycloak never started, first run would be red). E2E backend runs
on a **dedicated port 8082** with `claims_e2e` env and `reuseExistingServer: false`, so a
developer's dev-claims backend on 8081 can never absorb E2E writes (frontend uses
`proxy.e2e.conf.json`). Policy numbers are matched leniently (trim + uppercase) with
holder details case-insensitive. Photo validation happens before any file write, and the
claim upload directory is deleted when the transaction rolls back. Multipart limits sit
above the app-level photo caps so the app (not the multipart layer) returns readable
errors; `MaxUploadSizeExceededException` maps to a 400. Audit payloads are built with
Jackson (Boot 4 = Jackson 3, `tools.jackson`) instead of string concatenation.
`/api/policies` permitAll narrowed to GET. The integration test now asserts internal
fields (`claimantSub`, `policyId`, `lossDescription`, `level`) are absent from the wire
body, and a wrong-role (adjuster) 403 test pins the CLAIMANT mapping. E2E gained the
rejected-FNOL-stays-on-form journey (E2E is no longer fully parallel — concurrent Keycloak
registration flows are flaky). Dead code removed: unused `mockito-core` dependency,
`ClaimRepository.findByClaimNumber` (no caller), `logout()` in the SPA auth service.
**Deferred / open:** (a) attachment serving guidance for slice 3 — serve with
`Content-Disposition: attachment` and consider magic-byte sniffing rather than trusting
the client content type (stored-XSS vector when photos can be rendered); (b) Keycloak
compose healthcheck — CI polls the realm endpoint instead; add a healthcheck if `--wait`
should cover Keycloak; (c) `api.http` FNOL template is unexecuted until a token is
pasted — verify once against a running app.
**Revisit if:** any listed item becomes load-bearing in its slice.

---

### 2026-09-06 — Slice-1 schema: vertical, with two plan additions

**Context:** FNOL needed a `claim` table, an `attachment` table, and `authority_config`
(route level per product code). The approved plan model lists far more claim/authority
columns than slice 1 writes.
**Decision:** Each table carries only the columns the slice writes; columns the plan
specifies for later slices arrive through later ALTER migrations. Two additions beyond
the plan model, both approved: `claim.claimant_remarks` TEXT (Flow 1 says the claimant
"adds remarks"; the plan model had no home for them — internal, never on claimant-facing
surfaces) and a `claim_number_seq` used for `CLM-%06d` claim numbers.
**Why:** Vertical slicing keeps schema churn matched to code; remarks need a home today.
**Rejected:** Full plan schema now (dead columns); remarks folded into loss_description
(loses the distinction the form implies).
**Revisit if:** a later slice wants a different claim-number format (migration would add a
new sequence/column, never edit existing rows).

---

### 2026-09-06 — FNOL email is best-effort, after commit

**Context:** Flow 1 requires the FNOL email; claims must never be lost to a mail outage.
**Decision:** The email sends after the claim transaction commits, in the controller; any
send failure is logged and the claim stands. Tests assert delivery against Mailpit
(Testcontainers in the integration suite — the plan's captured mailbox; Mailpit also runs
in compose for dev).
**Why:** Claim creation is the regulated event; a notification failure must not roll it back.
**Rejected:** Sending inside the claim transaction (email failure rolls back a valid claim).
**Revisit if:** email delivery guarantees are required (then a queue/retry would be the answer).

---

### 2026-09-06 — Keycloak wiring lands in slice 1 (resolves C2 deferral)

**Context:** C2 deferred the Keycloak dev container to the slice that wires auth.
**Decision:** Slice 1 wires it: compose runs Keycloak 26.3 (host :8090; host :8080 is taken
by an unrelated process) importing the `claims` realm from `keycloak/realm-export.json`
(claimant self-registration on; realm roles claimant/adjuster_l1/adjuster_l2/supervisor;
self-registered users default to `claimant`). Spring Boot is an OIDC resource server
(issuer `http://localhost:8090/realms/claims`) mapping `realm_access.roles` to `ROLE_*`;
Angular uses the public SPA client `claims-frontend` (authorization-code + PKCE).
`POST /api/claims` requires `ROLE_CLAIMANT`. Integration tests mint HS256-signed test
JWTs (no Keycloak needed in unit/integration runs); E2E registers through the real realm.
**Why:** Retires the stack's riskiest unknown with real verification, per plan sequencing.
**Rejected:** Mock-only auth in tests (would not exercise the realm import or SPA flow).
**Revisit if:** token lifetimes, refresh handling, or internal-user provisioning need work
(slice 2+ provisions adjusters).

---

### 2026-09-06 — E2E runs on a dedicated database (implements S1 deferral)

**Context:** The S1 review deferral said to give E2E its own database once journeys write.
**Decision:** Slice 1 journeys write, so this is now built: compose bootstraps a
`claims_e2e` database (`docker/db-init`), and Playwright boots the backend with
`SPRING_DATASOURCE_URL` pointing at `claims_e2e` (Flyway migrates it on boot). Dev
`claims` data is never touched by E2E. CI starts the full compose stack (db + Mailpit +
Keycloak) for the E2E job.
**Why:** E2E creates claims; the old pattern (shared dev DB) was the exact bug S1 flagged.
**Rejected:** Continuing against the dev DB now that journeys write.
**Revisit if:** nothing — this is the standing arrangement.

---

### 2026-09-03 — Slice-0 audit-log writer timing: slice 1, not slice 4 (C1)

**Context:** The plan says the audit-log mechanism is "laid down in the skeleton, not a bolted-on slice," and requirements say every status change and every decision is logged. Status changes begin at FNOL (slice 1: claim created) and assignment (slice 2), before any decision exists.
**Decision:** Slice 0 ships the `audit_log` table DDL only. The append-only writer service lands in **slice 1** with the first write — emitting the "claim created" entry — and is used by every later state change. It must not wait for slice 4's first decision, or slices 1–3 ship unlogged and need a retrofit.
**Why:** The skeleton has no writes, so a writer would be dead code now; but it must exist before slice 1's first write, not before slice 4's first decision.
**Rejected:** A slice-0 writer with no caller (speculative); a writer deferred to slice 4 (unlogged slices 1–3).
**Revisit if:** nothing — timing is fixed by the slice order in `docs/plan.md`.

---

### 2026-09-03 — Integration test database: Testcontainers, with a dedicated fallback (C3)

**Context:** This sandbox has no usable Docker daemon, so Testcontainers cannot run here. Integration tests still must run against a real PostgreSQL (per the existing Testcontainers decision — never H2).
**Decision:** Integration tests use a Testcontainers `postgres:16-alpine` container whenever Docker is available (CI, normal dev machines). When it is not, `TestcontainersConfiguration` falls back to a **dedicated** test database from `TEST_DB_URL` (default `jdbc:postgresql://localhost:5432/claims_test`, user `claims`), never the dev `claims` database — integration tests truncate between tests and must not touch dev data. Local verification here ran PostgreSQL 16.15 from downloaded binaries (`/tmp/pg16`) serving both `claims` and `claims_test`.
**Why:** One test code path, real Postgres in both modes, zero risk to dev data.
**Rejected:** Pointing the fallback at the dev database (tests truncate it); H2.
**Revisit if:** Docker becomes available in this environment (then the fallback is simply never taken).

---

### 2026-09-03 — Authority is per-claim; no aggregate exposure cap

**Context:** The gate needed an unambiguous operand (indemnity vs payment, per-claim vs per-adjuster).
**Decision:** The gate compares the claim's single `indemnity_amount` against the acting level's limit; `payment.amount == indemnity_amount` (one payment per claim). No per-adjuster aggregate exposure cap in v1.
**Why:** One payment per claim makes per-claim and per-payment identical; no aggregate cap was in requirements.
**Rejected:** A per-adjuster running-total cap (not requested).
**Revisit if:** partial payments or aggregate limits become requirements.

---

### 2026-09-03 — Classification via per-product-code route_level

**Context:** Requirements said classification uses "estimated amount and complexity" keyed on product code, but the claimant supplies neither at FNOL.
**Decision:** `authority_config` holds `route_level` (L1|L2) per product code — the "complexity" routing parameter — plus `l1_limit_amount`/`l2_limit_amount` as the monetary thresholds evaluated at decision time.
**Why:** No amount is available at FNOL; the product code's routing level is the only honest classification input.
**Rejected:** Claimant-entered estimated amount at FNOL (not in the flow); free-text complexity field.
**Revisit if:** a real estimate/complexity input is introduced at intake.

---

### 2026-09-03 — Escalation-to-L2 is system re-assignment, not a new status

**Context:** `ESCALATED_L2` had no API surface or assignment rule.
**Decision:** An above-level (but ≤ L2-limit) decision re-assigns the claim to the least-loaded L2 adjuster; level → L2; status returns to `UNDER_REVIEW`. Only above-L2 becomes `ESCALATED_SUPERVISOR`.
**Why:** Reuses the existing assignment rule; the original adjuster structurally can't self-approve (level changes, assignee changes).
**Rejected:** A separate ESCALATED_L2 status + L2 escalation queue (more states, more surface).
**Revisit if:** L2 pickup needs its own queue distinct from normal caseload.

---

### 2026-09-03 — Unauthorized access returns 404

**Context:** Cross-tenant and non-assignee access needed a defined response.
**Decision:** 404 (not 403) for any claim the caller has no right to see.
**Why:** 403 reveals that a claim number exists — a leak through the visibility wall.
**Rejected:** 403 (leaks existence).
**Revisit if:** a requirement emerges to disclose existence (unlikely).

---

### 2026-09-03 — Load-balance tie-break: lowest user id

**Context:** "Fewest open claims" is under-determined when adjusters tie.
**Decision:** Assign to the level-appropriate adjuster with the fewest open claims; ties broken by lowest `app_user.id`.
**Why:** Deterministic, testable, no extra state.
**Rejected:** Random, oldest assignment (nondeterministic/hard to assert).
**Revisit if:** a business preference for tie-breaking emerges.

---

### 2026-09-03 — Roles live in Keycloak only; no in-app user provisioning

**Context:** Needed three roles plus a per-adjuster level for routing and the authority gate. The plan initially had a local `app_user.level` column and a supervisor provisioning UI.
**Decision:** Keycloak is the single source of truth for identity and roles (`CLAIMANT`, `ADJUSTER_L1`, `ADJUSTER_L2`, `SUPERVISOR`). `app_user` is a thin cache (subject → display name/email) auto-populated on login, only so claims can hold an assignee FK.
**Why:** Avoids two sources of truth that drift; cuts a whole screen and endpoint surface from v1.
**Rejected:** Local level column + `/admin/users` provisioning UI.
**Revisit if:** a non-Keycloak internal identity source appears, or claims must be assigned to staff before they've logged in once.

---

### 2026-09-03 — FNOL policy identification: match number + holder details

**Context:** Claimant must file against a seeded policy, but there's no policy-admin integration to verify ownership.
**Decision:** Claimant enters policy number + policyholder name/email; the system matches against the seeded policy row and rejects on mismatch.
**Why:** One match field keeps it honest without building an integration.
**Rejected:** No verification (pick any policy — pure demo); supervisor pre-links identities (manual).
**Revisit if:** a policy-admin integration arrives.

---

### 2026-09-03 — Photo storage: filesystem

**Context:** Claimant uploads evidence photos at FNOL; hosting unspecified.
**Decision:** Files on disk (configurable upload dir), path stored in DB.
**Why:** Small book; photos are evidence, not a CDN; keeps Postgres lean and streaming simple.
**Rejected:** Postgres bytea/LO (self-contained but bloats DB and streams poorly); S3/MinIO (dependency we don't need yet).
**Revisit if:** hosting moves somewhere ephemeral or photo volume spikes.

---

### 2026-09-03 — SPA↔backend auth: public SPA with PKCE + resource server

**Context:** Angular SPA + Spring Boot + Keycloak.
**Decision:** Angular holds a Keycloak access token (public client, PKCE) and calls Spring Boot as a resource server that validates the JWT and maps roles.
**Why:** Standard, fewer moving parts; nothing worth protecting beyond PKCE here.
**Rejected:** BFF with server sessions (extra hop + state).
**Revisit if:** a client secret becomes necessary or a second non-browser client appears.

---

### 2026-09-03 — Integration test DB: Testcontainers Postgres

**Context:** Integration tests need a real database per the test strategy.
**Decision:** Testcontainers spins a real Postgres per test class; schema from Flyway; reset by truncate.
**Why:** Real Postgres behavior (constraints, JSONB, uniqueness) instead of H2's approximation.
**Rejected:** H2 in Postgres-compat mode (lies about Postgres).
**Revisit if:** container runtime unavailable in CI.

---

## Deferred

### 2026-09-06 — Slice-3 review optional items

**Considered:** Six optional cleanups from the slice-3 review: the unused `loaded()`
signals in the two new components; simplifying a redundant second `app_user` lookup in
`addNote` (already folded into the S6 fix); empty/NaN reserve submissions from the SPA
(`Number()` of a blank box submits 0, NaN submits null); two stale javadocs (SecurityConfig
and the claimant guard say "slice 1"/"the queue" only); the `£` + raw-number reserve
display (currency formatting); extracting the E2E FNOL-form helper, then duplicated in two
specs.
**Why not now:** Cosmetic or low-risk; the user scoped the fix round to the should-fix
findings.
**Build it when:** slice 4 kept the FNOL/register helpers at two copies on purpose
(journeys 5–6 joined `queue.spec.ts` rather than a new file, so no third copy appeared and
the E2E worker count stayed flat — see the slice-4 decision entry). Extract the helpers only
when a real third spec file needs them (e.g. a supervisor spec in slice 5), a hardening
pass touches the same files, or the duplication otherwise grows past two copies.

---

### 2026-09-06 — Slice-2 review optional items

**Considered:** Seven optional cleanups from the slice-2 review: replacing
`LoadBalancer`'s load-bearing `.sorted()` with an explicit id `thenComparing`; recording
the CLAIM_CREATED audit payload with the final (UNDER_REVIEW) status instead of the
momentary UNASSIGNED state; hiding the "Adjuster queue" nav link from the public;
dropping `QueueClaimView.createdAt` (no consumer yet); fixing UNDER_REVIEW step copy that
contradicts the received step; extracting the duplicated E2E `registerClaimant` helper;
hardening the dev-only plaintext credentials (Keycloak admin/admin, adjuster passwords)
in realm-export/docker-compose.
**Why not now:** All are cosmetic or cross-slice concerns with no behavioral risk; the
user scoped the fix round to the should-fix findings. Credential hardening is a
pre-ship/hardening-phase checklist item, not a slice item.
**Build it when:** a later slice (or a hardening pass) touches the relevant file anyway —
none of these has become load-bearing through slice 4.

---

### 2026-09-06 — app_user auto-population on login; supervisor Keycloak user

**Considered:** A login hook that upserts `app_user` rows from the JWT, and provisioning a
supervisor user in Keycloak now.
**Why not now:** Adjusters are seeded with fixed subjects, so no first-login upsert is
needed; the supervisor role token already unlocks the team queue in tests, and no journey
logs in as a supervisor until the supervisor slices (5+).
**Build it when:** staff display data can drift from Keycloak, or slice 5's supervisor
journey needs a real supervisor login.

---

### 2026-09-03 — E2E database isolation (review finding S1, follow-up)

**Considered:** Giving the Playwright-booted backend its own isolated database (separate
Spring profile or `claims_e2e`) so E2E never touches dev data, per review finding S1.
**Why not now:** Slice-0 E2E is read-only (asserts the seeded policy row is visible) and
the CI E2E job already runs against an ephemeral per-run Postgres service container. The
spec no longer asserts an exact row count, so future seed additions won't break it. Real
pollution risk starts when E2E journeys first *write* (slice 1 FNOL creates claims).
**Build it when:** slice 1 adds E2E journeys that create data — give E2E its own database
then, not before.

---

### 2026-09-03 — Hardcoded dev credentials (review finding O7)

**Considered:** Moving `claims`/`claims` out of `application.properties` and
`docker-compose.yml` into environment variables.
**Why not now:** Local-dev-only skeleton; both files agree on the same value and nothing
non-local deploys yet. Secrets-in-env is enforced later by the hardening phase checklist.
**Build it when:** any non-local deployment or shared environment appears.

---

### 2026-09-03 — Keycloak dev container (C2)

**Considered:** Adding a Keycloak service with a realm import to `docker-compose.yml` during slice 0 ("Keycloak wired" in the plan's slice-0 line).
**Why not now:** `03-skeleton.md` says don't build auth, and the slice-0 read path needs only Postgres. A Keycloak container nothing connects to is ahead of schedule.
**Build it when:** slice 1 wires real auth (claimant registration/login, Spring Security resource server). Keep `docker-compose.yml` Postgres-only until then.

---

### 2026-09-03 — Reopening / appeals

**Considered:** Allow a decided claim to be reopened (appeals/reconsideration).
**Why not now:** One-way flow to closure is much simpler; no appeal process defined yet.
**Build it when:** an appeal/reconsideration process is actually specified.

---

### 2026-09-03 — Multiple / partial payments per claim

**Considered:** Partial payments (initial + supplement) against one claim.
**Why not now:** Complicates the authority gate (cumulative totals) and closure; v1 is one payment per claim.
**Build it when:** supplements/partial payments become a real business need.

---

### 2026-09-03 — SMS notifications, mobile app, real-time updates

**Considered:** SMS delivery, a native mobile app, websocket live updates.
**Why not now:** Email-only + responsive web + pull-to-refresh covers v1; each adds surface and flake.
**Build it when:** claimants demonstrably need push or live updates, or field adjusters need offline/native.

---

### 2026-09-03 — Object storage (S3/MinIO)

**Considered:** Storing evidence photos in object storage.
**Why not now:** One storage need (claimant photos), no scale problem yet.
**Build it when:** hosting is ephemeral or photo volume grows.
