# Plan — Claims Processing System

Status: Approved
Last updated: 2026-09-03
Based on: `requirements.md` as of 2026-09-03

## Stack

| Layer | Choice | Why |
|---|---|---|
| Frontend | Angular (SPA) | Constraint. Typed SPA; fits both the adjuster's all-day tool and the claimant forms. |
| Backend | Spring Boot | Constraint. Spring Security + Spring Data JPA integrate cleanly with Keycloak and Postgres. |
| Database | PostgreSQL | Constraint. Relational, supports the uniqueness constraints and JSONB audit payloads we need day one. |
| Migrations | Flyway | Boring, versioned, first-class in Spring Boot. |
| Auth | Keycloak (OIDC) | Constraint. One IdP, three roles, claimant self-registration. |
| Hosting | None specified | Local dev via docker-compose; deploy anywhere that runs a container/JVM. |
| Email | Spring Mail + SMTP; Mailpit in dev | Required for the three email triggers (FNOL, assignment, decision); Mailpit gives a captured mailbox for tests. |
| Unit tests | JUnit 5 + Mockito | Constraint (Java). Branching logic: authority gate, classification, aging, state machine, DTO shaping. |
| Integration tests | Spring Boot Test + Testcontainers | Real Postgres per test; catches serialization/auth/constraint bugs. |
| E2E tests | Playwright | Constraint. Auto-waiting, parallel, trace viewer. |
| CI | GitHub Actions | Assumed default; no provider was pinned. |

## Resolved forks

Recorded in `docs/decisions.md`. Kept here because a literal reader needs to know these
are decided, not open.

### SPA ↔ backend auth pattern

- **Decision:** public SPA with PKCE; Spring Boot as resource server. Angular holds a Keycloak access token, sends it as a Bearer JWT; Spring Boot validates it and maps roles.

### Photo storage

- **Decision:** filesystem. Files on disk (configurable upload dir), path in DB. *Revisit if* hosting moves somewhere ephemeral or photo volume spikes.

### FNOL policy identification

- **Decision:** claimant enters policy number + policyholder name/email; the system matches the seeded policy row and rejects on mismatch. No verification (pick any policy) and admin pre-linking are rejected.

## Data model

```
policy                (seeded, read-only)
  id PK, policy_number (unique), product_code,
  holder_name, holder_email, coverage (jsonb/text)

app_user              (internal staff cache; identity + roles live in Keycloak)
  id PK, keycloak_sub (unique), display_name, email
  (auto-populated on login; role comes from Keycloak, not stored here)

authority_config      (one row per product code)
  id PK, product_code (unique),
  l1_limit_amount, l2_limit_amount, route_level enum(L1 | L2)

claim
  id PK, claim_number (unique), policy_id FK → policy,
  claimant_sub (Keycloak subject), level enum(L1 | L2),
  status enum(UNASSIGNED | UNDER_REVIEW | ESCALATED_SUPERVISOR | CLOSED),
  decision enum(APPROVED | DENIED) (nullable until closure),
  loss_description, loss_date, loss_location,
  assigned_adjuster_id FK → app_user (nullable),
  reserve_amount (nullable), indemnity_amount (nullable),
  decision_remarks (claimant-visible, nullable),
  created_at, assigned_at, closed_at

internal_note
  id PK, claim_id FK → claim, author_id FK → app_user,
  body, created_at

attachment            (claimant evidence photos)
  id PK, claim_id FK → claim, storage_path, content_type,
  original_name, created_at

payment               (one per claim; amount always == claim.indemnity_amount)
  id PK, claim_id FK → claim (unique), amount,
  authorized_by_id FK → app_user, authorized_at

audit_log             (append-only)
  id PK, actor_sub, action, entity_type, entity_id,
  before (jsonb), after (jsonb), rationale (required on decision rows), created_at
```

Relationships: `policy 1—N claim`; `claim 1—N internal_note`, `1—N attachment`,
`1—1 payment` (exists iff decision = APPROVED); `claim N—1 app_user` (assignee);
`claim 1—1 claimant` (Keycloak subject).

Authority semantics (the gate, stated unambiguously):
- Authority is **per-claim**: the gate compares the claim's single `indemnity_amount`
  against the acting level's limit (`l1_limit_amount` / `l2_limit_amount`).
- There is exactly one payment per claim and `payment.amount == indemnity_amount`, so
  per-claim and per-payment are the same thing here. No field ever diverges from the other.
- **No per-adjuster aggregate exposure cap in v1.** A level limit is a per-claim amount,
  not a running total across an adjuster's caseload.
- Only the payment/indemnity is gated. The **reserve is not authority-gated** — it is an
  internal estimate and may be set freely by the assigned adjuster.

Classification:
- At FNOL, the claim's `level` is set from the product code's `route_level` (the
  "complexity" parameter). New claims route to that level, then load-balance within it.
- `l1_limit_amount` / `l2_limit_amount` are the monetary authority thresholds (the
  "amount" parameters), evaluated when the indemnity figure is known.
- Escalation to L2 is **system re-assignment**: the claim's `level` becomes L2 and it is
  assigned to the least-loaded L2 adjuster (same rule as initial assignment). It returns
  to `UNDER_REVIEW` under that assignee — there is no separate "esc L2" status.

State transitions:
- `UNASSIGNED` → (assignment) → `UNDER_REVIEW`.
- `UNDER_REVIEW` → decision:
  - approve within level → record payment, `decision = APPROVED`, → `CLOSED`.
  - deny → `decision = DENIED` + remarks → `CLOSED`.
  - amount above the actor's level → re-assign to L2 (if ≤ `l2_limit`) or → `ESCALATED_SUPERVISOR` (if > `l2_limit`).
- `ESCALATED_SUPERVISOR` → supervisor approves (record payment, `APPROVED`, `CLOSED`) or denies (`DENIED`, `CLOSED`).
- `CLOSED` is terminal; a closed claim drops out of every active queue.
- Decision, payment, and closure are **atomic** (one transaction). `closed_at` == decision time.
- Claimant-facing "process steps" (`steps` in the claimant-view) is derived from status +
  timestamps: FNOL received → under review → escalated (when applicable) → decision. It
  never exposes the reserve, internal notes, or the internal assignee.

Constraints and indexes needed from day one:
- Unique: `claim.claim_number`, `policy.policy_number`, `authority_config.product_code`, `payment.claim_id`, `app_user.keycloak_sub`.
- Indexes: `claim.assigned_adjuster_id`, `claim.status`, `claim.created_at` (aging/queue), `claim.policy_id`, `audit_log(entity_type, entity_id)`, `audit_log.created_at`.
- `audit_log` is append-only — the application never issues UPDATE/DELETE on it; any correction is a new row.
- Decision rows in `audit_log` require a non-null `rationale`; both decision endpoints reject a missing rationale (approve and deny alike).

## Routes

| Path | Renders | Who can see it |
|---|---|---|
| `/login` | Keycloak redirect | anyone |
| `/claim/new` | FNOL form | CLAIMANT |
| `/claim/:claimNumber` | claimant status / process-steps screen | CLAIMANT (own claim only) |
| `/` | queue (adjuster home) | ADJUSTER_L1, ADJUSTER_L2, SUPERVISOR |
| `/claims/:claimNumber` | internal claim detail | assigned adjuster, SUPERVISOR |
| `/escalations` | supervisor escalation queue | SUPERVISOR |
| `/admin/authority` | authority config editor | SUPERVISOR |

Claimant and internal surfaces are separate route trees with distinct prefixes and role-guarded routers: claimant `/claim/*`, internal `/claims/*`, `/escalations`, `/admin/*`.

## API

| Method | Path | Auth rule | Returns |
|---|---|---|---|
| POST | `/api/claims` | CLAIMANT | created claim, claimant-view (number, status, steps) |
| GET | `/api/claims` | CLAIMANT (own only) | list of caller's claims, claimant-view |
| GET | `/api/claims/{claimNumber}` | CLAIMANT, own claim | claimant-view: status, steps, decision, approved amount (`indemnity_amount`, when `decision = APPROVED`), remarks — **never reserve/notes** |
| GET | `/api/queue` | ADJUSTER_L1/L2 (own), SUPERVISOR (team) | internal claim summaries, ordered |
| GET | `/api/claims/{claimNumber}/full` | assigned adjuster, SUPERVISOR | full internal view incl. policy/coverage + reserve + notes + photos |
| GET | `/api/claims/{claimNumber}/attachments/{id}` | assigned adjuster, SUPERVISOR | photo binary |
| PUT | `/api/claims/{claimNumber}/reserve` | assigned adjuster, SUPERVISOR | updated internal view |
| POST | `/api/claims/{claimNumber}/notes` | assigned adjuster, SUPERVISOR | created note |
| POST | `/api/claims/{claimNumber}/decision` | assigned adjuster only (gate enforced) | approve (within level → record payment + close) or deny (close with remarks); **rationale required**; above level → system re-assigns to L2 or moves to `ESCALATED_SUPERVISOR` |
| GET | `/api/escalations` | SUPERVISOR | claims in `ESCALATED_SUPERVISOR` |
| POST | `/api/claims/{claimNumber}/escalation-decision` | SUPERVISOR | approved (record payment + close) or denied (close with remarks); **rationale required** |
| POST | `/api/claims/{claimNumber}/reassign` | SUPERVISOR | updated assignee |
| GET | `/api/claims/{claimNumber}/audit` | SUPERVISOR | audit log for the claim |
| GET | `/api/config/authority` | SUPERVISOR | config rows by product code |
| PUT | `/api/config/authority/{productCode}` | SUPERVISOR | updated config |

Authorization notes:
- The **visibility wall is enforced at the API layer**: claimant endpoints return a
  claimant-view DTO that structurally omits `reserve` and `internal_note`. It is not a
  UI-only hide — the fields never serialize toward a claimant. The claimant-view includes
  status, process steps, decision, remarks, and the approved amount (`indemnity_amount`,
  present only when `decision = APPROVED`).
- The **authority gate is enforced server-side** in a single service: the decision
  endpoint rejects an above-level approval by the actor, and no actor can approve their
  own escalation.
- Roles (`CLAIMANT`, `ADJUSTER_L1`, `ADJUSTER_L2`, `SUPERVISOR`) come from Keycloak and
  map to Spring Security authorities; internal users are provisioned there, not in the app.
- **Unauthorized access returns 404, never 403.** A claimant requesting another's claim,
  or an adjuster requesting a claim that isn't assigned to them, gets 404 — so the
  response never reveals that a claim number exists.
- Escalation to L2 is a **system action**, not an actor endpoint: on an above-level
  decision the service re-assigns the claim to the least-loaded adjuster of the sufficient
  level. Only the supervisor escalation is surfaced as a queue (`/escalations`).
- Aging escalation (3 days → L2, 5 days → supervisor) runs as a scheduled background job,
  not as a user action.

## Test strategy

- **Unit (JUnit 5 + Mockito):** the branching logic — authority gate (amount vs level,
  escalation skip-levels routing, no-self-approval), classification (product code →
  `route_level`), load-balance selection including the tie-break (fewest open claims,
  then lowest `app_user.id`), aging ladder (3/5-day thresholds), claim state machine
  (legal transitions), and claimant-view DTO mapping (reserve/notes are absent). No DB,
  no network.
- **Integration (Spring Boot Test + Testcontainers Postgres):** one container per test
  class, schema from Flyway, reset between tests (truncate). Per endpoint: success,
  invalid input, unauthenticated, authenticated-but-forbidden, not-found — where
  "forbidden/not-found" means the specific pinned case: **cross-tenant or non-assignee
  access returns 404**, not 403. The visibility wall and authority gate get their sharpest
  tests here (assert the claimant response body truly lacks reserve/notes; assert the
  single-payment-per-claim uniqueness constraint; a decision with a missing rationale is
  rejected). Email is asserted at this layer against
  the captured mailbox (Mailpit), not in E2E.
- **E2E (Playwright):** `data-testid` selectors, no fixed sleeps, setup via API/fixtures,
  test through the UI. Journeys covered:
  1. Claimant signs up/logs in and files an FNOL with a photo; sees the claim number.
  2. Claimant opens their status screen; reserve/notes are absent from screen *and* response.
  3. Adjuster opens their queue; a new claim is assigned to the least-loaded adjuster of the right level.
  4. Adjuster opens a claim, sets a reserve, adds an internal note, views the photos.
  5. Adjuster approves a within-limit amount → payment recorded → claim closes.
  6. Adjuster tries an above-limit approval → blocked and escalated; cannot self-approve.
  7. Supervisor approves the escalation with rationale → claim proceeds.
  8. Claimant sees the decision on the closed claim (approved amount, or denial + remarks).
  - Aging (3/5 days) is asserted at the integration layer with a controllable clock; an
    E2E for it is optional and only if time injection is cheap.
- **API collection:** `api.http` (REST Client) maintained — one working example per
  endpoint with real auth token, updated in the same slice that adds the endpoint.
- **CI gates (GitHub Actions):** every push — lint/format, compile, unit, integration
  (Testcontainers). E2E on PR/main. Nothing merges red.

## Slices

Each slice is vertical: database + backend + UI, ending in something a user can do.
The audit-log mechanism (append-only, actor + timestamp + rationale) is laid down in the
skeleton and used by every decision from slice 1 onward; it is not a bolted-on slice.

### Slice 0 — Walking skeleton
See `03-skeleton.md`. Done when Spring Boot + Angular + Postgres + Keycloak are wired and
all three test layers run green in CI.

### Slice 1 — FNOL & claim number
- Satisfies: Flow 1 (FNOL returns a number immediately, stores photos/remarks, classifies
  L1/L2 from the product code's `route_level`, rejects missing details, sends FNOL email).
- Tests: unit — classification; integration — POST `/api/claims` (success, invalid, 401, policy-not-found); E2E — journey 1.
- Risk / unknowns: Keycloak claimant auth, multipart photo upload, first claimant-view DTO (wall begins here).

### Slice 2 — Assignment & the adjuster queue
- Satisfies: Flow 2 (level routing, load-balancing to fewest open claims with the tie-break, assignment email, status → under review).
- Tests: unit — routing/load-balance selection incl. tie-break; integration — queue + assignment auth rules (incl. 404 for non-assignee); E2E — journey 3.
- Risk / unknowns: least-loaded query correctness, concurrency on simultaneous assignment (make assignment atomic).

### Slice 3 — Adjuster works the claim & the visibility wall
- Satisfies: Flow 3 (verify coverage against the seeded policy, set reserve, add internal notes, read description/photos) and the wall (reserve/notes absent from claimant surfaces).
- Tests: unit — claimant-view DTO mapping (omits reserve/notes; includes approved amount when APPROVED); integration — internal full (incl. policy/coverage) + attachment endpoints auth, claimant endpoint asserts no reserve/notes in body and includes the approved amount; E2E — journeys 2 and 4.
- Risk / unknowns: this is where the wall becomes load-bearing; the negative assertions matter more than the happy path.

### Slice 4 — Decision & the authority gate
- Satisfies: Flow 4 gate criteria (within-level approve → single payment recorded + closed; above-level blocked + escalated; no self-approval; skip-level routing re-assigns to L2 or moves to supervisor); the decision email fires in the same transaction as closure.
- Tests: unit — full gate matrix; integration — decision endpoint (success, above-level forbidden + escalation/re-assignment, self-approval rejection, single-payment uniqueness); E2E — journeys 5 and 6.
- Risk / unknowns: the product's core rule — the unit matrix is the safety net; get it exhaustive.

### Slice 5 — Supervisor escalation & aging
- Satisfies: Flow 6 (3-day → re-assign to L2, 5-day → supervisor, claimant-visible) and the supervisor half of Flow 4 (see file, reserve, notes, proposed figure; approve/deny with rationale); the decision email fires on supervisor closure.
- Tests: unit — aging ladder with controllable clock; integration — escalation-decision endpoint, scheduled job transitions; E2E — journey 7.
- Risk / unknowns: scheduled job determinism in tests (inject the clock).

### Slice 6 — Claimant decision & notification
- Satisfies: the claimant-facing half of Flow 5 — claimant sees the decision (approved amount, or denial + remarks) on the closed claim; the wall still holds on closed claims. The decision email itself fires in the closure transaction (slices 4–5); this slice renders the claimant display and verifies the email is received.
- Tests: integration — decision email received (Mailpit, sent on closure in slices 4–5), wall holds on the closed claim's claimant view; E2E — journey 8.
- Risk / unknowns: denial remarks vs internal notes separation; email content correctness.

### Slice 7 — Compliance & admin
- Satisfies: supervisor edits authority config, views the audit log, and reassigns claims; "100% of decisions have recorded actor + rationale." Internal users are provisioned in Keycloak (roles are the single source of truth).
- Tests: integration — config endpoints auth + effect on gate; audit-log append-only (reject UPDATE/DELETE); reassign endpoint (auth + assignee changes + audit logged); E2E — config edit reflected in classification/gate.
- Risk / unknowns: config changes must feed the gate immediately; audit-log immutability enforced at the data layer.

Ordering note: the two load-bearing rules — the **authority gate** (slice 4) and the
**visibility wall** (slices 1→3) — land before any polish, and the wall exists from the
very first claimant feature rather than being retrofitted.

## Out of scope for this plan

- Reopening/appeals (one-way to closure).
- Partial or multiple payments per claim.
- Per-adjuster aggregate exposure caps (limits are per-claim amounts only).
- Policy admin / underwriting / rating — policies are seeded.
- Automated adjudication, fraud scoring, document reading.
- External integrations (no policy-admin, no payment rails).
- SMS (email only); mobile app (responsive web only); real-time websockets.
- Multi-tenancy, i18n, offline, SSO beyond Keycloak.
