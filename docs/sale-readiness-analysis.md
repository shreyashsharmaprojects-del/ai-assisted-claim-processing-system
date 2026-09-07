# Sale-readiness analysis — Claims Processing System

Date: 2026-09-07. Author: autonomous sale-readiness pass.
Basis: cold read of `docs/progress.md`, `docs/plan.md`, `docs/requirements.md`,
`docs/decisions.md`, `docs/operations.md`, `README.md`, `api.http`, the migration chain
V1–V8, `backend/src/main`, `frontend/src/app`, `e2e/tests`.
Verified baseline this session: `npm run db:up` green; backend **152 tests, 0
failures** (progress.md says 151 — the suite grew by one since that entry; see §6);
frontend production build green (one pre-existing `app.css` budget warning, +219 bytes);
ports 4200/8081 show as LISTEN in this sandbox but no identifiable process owns them
(`fuser`/`lsof` unavailable, no `/proc` match for ng/spring) — E2E is hermetic
(`reuseExistingServer: false` both servers) so it boots its own stack regardless.

## 1. What the system does today

A single-carrier personal-lines claims pipeline from FNOL to closure, three roles,
strictly separated surfaces.

**Flows (all live):**
1. **FNOL** — claimant (Keycloak self-registration) files policy number + holder
   name/email + loss date/location/description + remarks + up to 5 photos (10 MB each,
   image/*). System validates, rate-limits (20/day/claimant, 429 + Retry-After, V8
   `fnol_submission` ledger), matches the seeded policy row, classifies L1/L2 from the
   product's `route_level`, assigns to the least-loaded adjuster of that level, returns
   the claim number immediately, sends the FNOL email. All in one transaction.
2. **Assignment & adjuster queue** — role-driven `GET /api/queue` (own queue for
   adjusters, team queue for supervisors), oldest-first. Assignment email sent.
   Claimant process-steps screen updates (FNOL received → under review → escalated
   when applicable → decision).
3. **Adjuster works the claim** — internal full view (policy + coverage JSON, reserve
   form, internal notes, photo downloads), reserve set/update (free estimate, not
   gated), internal notes appended.
4. **Decision & authority gate** — adjuster approves within level (single payment
   recorded + claim closed atomically) or denies (closes with rationale as remarks);
   above-level approval is never granted — system re-assigns to least-loaded L2
   (or supervisor if no L2 provisioned / above L2 limit). No self-approval is
   structural (supervisors never hold claims, never touch the adjuster endpoint).
5. **Supervisor escalation & aging** — escalation queue, approve/deny with rationale
   (payment records NULL authorizer, audit carries subject), reassign to target level
   (least-loaded), audit view, authority-config editor (route level + L1/L2 limits,
   read fresh per decision/classification, no cache). Aging job daily 03:00: 3-day
   rung → L2, 5-day rung → supervisor, wins-over/idempotent, claimant-visible.
6. **Closure & notification** — single payment closes the claim; claimant sees approved
   amount or denial + remarks; decision email fires on every closure path.
7. **Claimant history** (`GET /api/claims/mine`, newest first) and **supervisor
   dashboard** (`GET /api/dashboard`, 8 aggregates) added in the production push.

**Roles:** CLAIMANT (self-registers), ADJUSTER_L1/L2, SUPERVISOR (both provisioned in
Keycloak + `app_user` seed for adjusters; supervisor has no `app_user` row).

**Endpoints (21):** `GET /api/health`, `GET /api/ready`, `GET /api/policies`
(authenticated-only), `POST /api/claims` (FNOL), `GET /api/claims/mine`, `GET
/api/claims/{n}` (claimant status), `GET /api/queue`, `GET /api/claims/{n}/full`, `GET
…/attachments/{id}`, `PUT …/reserve`, `POST …/notes`, `POST …/decision`, `GET
/api/escalations`, `POST …/escalation-decision`, `POST …/reassign`, `GET …/audit`,
`GET/PUT /api/config/authority…`, `GET /api/dashboard`. Full examples in `api.http`.

**Key tables:** `policy` (seeded, read-only), `claim`, `app_user` (staff cache),
`authority_config` (seed, supervisor-edited), `internal_note`, `attachment` (path in
DB, file on disk), `payment` (1–1 per claim), `audit_log` (append-only, V7 trigger),
`fnol_submission` (rate-limit ledger, V8). Indexes: claim status/assignee/created,
audit entity+created, fnol claimant+created, V8 composite queue indexes.

**Frontend routes (9):** `/` home, `/claim/new` (2-step FNOL wizard), `/claims`
(my-claims), `/claim/:n` (status), `/queue`, `/claims/:n` (detail), `/overview`,
`/escalations`, `/admin/authority`. Modern SaaS skin (phase 07b/07c): floating chrome,
banner cards, icon-tile sections, single shadow recipe, Inter, `#0056B3`.

**Tests:** 152 backend (unit gate matrix/classifier/load-balancer/aging/state
machine/DTO wall + integration per-endpoint incl. Mailpit assertions), frontend prod
build only (no component tests), E2E 13/13 journeys on the hermetic stack (backend
:8082/`claims_e2e` + own ng serve :4200). Prod pack: Dockerfiles, nginx (SPA fallback
+ /api proxy + cache-safe headers), `docker-compose.prod.yml`, `docs/operations.md`
runbook, `deploy/config.json`.

## 2. Load-bearing rules — MUST NOT break

These are regression-pinned and sale-critical. Every build slice re-verifies them.

1. **Visibility wall (DTO-layer).** Claimant endpoints return claimant-view DTOs that
   structurally omit reserve / internal notes / assignee / coverage — including on
   CLOSED claims and per-row in `/mine`. Not a UI hide: fields never serialize.
   Pinned by structural record-shape tests + wire-leak capture in E2E journeys 2/8.
2. **Authority gate (L1 < L2 < supervisor, no self-approval).** Per-claim indemnity vs
   the actor's level limit, config re-read per call (no cache), `l1 ≤ l2` validated.
   Above-level approvals escalate (skip-level to L2 or supervisor), never grant. No
   actor approves their own escalation (structural: supervisors never hold claims).
   Pinned by the exhaustive unit matrix + integration escalation tests + E2E 5/6/7/9.
3. **Audit log append-only + rationale required.** V7 trigger rejects UPDATE/DELETE;
   decision rows require non-null rationale (approve and deny alike); corrections are
   new rows. Pinned by the trigger test + decision audit assertions.
4. **404-not-403 for cross-tenant/non-assignee.** Claimant requesting another's claim,
   or an adjuster requesting a claim they don't hold, gets 404 — never reveals
   existence. Pinned per endpoint at integration + E2E.
5. **Single-payment atomicity.** Decision + payment + closure in one transaction;
   `payment.amount == indemnity_amount` always; `FOR UPDATE` row lock serializes
   concurrent decisions (deterministic held-lock test). Closed is terminal.
6. **Aging ladder.** 3-day → L2, 5-day → supervisor, anchored at FNOL creation; 5-day
   wins over 3-day in one idempotent move; closed/escalated claims untouched; every
   move is a NULL-actor `CLAIM_ESCALATED` row with a self-describing rationale.
   Pinned with an injected clock (never the real cron).
7. **Flyway immutability.** V1–V8 checksums are load-bearing — never edit an applied
   migration. New schema only via new V9+ files. (The V4 comment scar stays.)
8. **Contract preservation.** All E2E `data-testid`s and exact-text contracts (status
   tokens, `£1500.00` money format, claim numbers) are additive-only changes. The 13
   journeys must stay green throughout.

## 3. Biggest strengths — preserve and demo

- **The gate + wall + audit trio actually works and is tested like it matters.**
  Exhaustive gate matrix, structural wall tests, DB-trigger immutability — this is the
  diligence story. Demo journey 6 (blocked above-limit → escalated) and journey 2
  (claimant sees steps, never money-in-progress) back to back.
- **Docs discipline is a sales asset.** Decisions log, operations runbook, `api.http`
  for every endpoint — buyers believe roadmaps from teams that write like this.
- **Hermetic E2E.** `reuseExistingServer: false` both servers; the suite boots its own
  world. A buyer can watch 13/13 pass on a laptop.
- **Supervisor dashboard + claimant history + session auth** (production push) make it
  feel like a product, not a prototype. The overview screen is the 30-second demo hook.
- **Modern enterprise UI** (07b/07c) — banner cards, icon tiles, dense tables, status
  pills. Looks like a vendor, not a tutorial.
- **Operational honesty:** readiness vs liveness split, request-id tracing with
  user-safe reference codes, rate-limit with Retry-After, actionable 400s.

## 4. Biggest gaps vs a sellable product

**Functionality — sale blockers:**
- **No policy onboarding.** Two seeded rows; no create/import/retire except SQL. The
  first question in any sales call ("load our book") is answered "by hand." (Roadmap
  P0-1; confirmed — the true #1 blocker.)
- **Emails are best-effort, no retry.** Decision email lost silently on SMTP outage =
  compliance exposure (can't prove notification). No outbox, no redelivery, no
  dead-letter visibility. (Roadmap P0-2; confirmed.)
- **No claim reopen/appeal.** Closure is terminal; real carriers reopen constantly.
  Workaround ("file a new claim") corrupts cycle-time metrics and breaks the audit
  link. Roadmap files this as P1-9 — **under-prioritized; it is a pilot blocker** and
  should be P0-adjacent (see requirements §5 non-decision: scoped as P1 only because
  P0 must stay buildable in this pass).
- **Staff management invisible to supervisors.** Provisioning = Keycloak console +
  manual row; no roster, no capacity view, no deactivation path. Offboarding gap.
  (Roadmap P1-11; confirmed, pilot-grade.)

**Operability:**
- **No metrics/alerting.** Health + ready + logs only. Nothing to dashboard or page
  on (FNOL rate, queue depth, email failures, decision latency). Enterprise diligence
  asks explicitly. (Roadmap P0-3; confirmed.)
- **No pagination.** Queue/escalations/mine return full lists; search is client-side
  `filter()`. First real backlog = slow page + slow DB. Paginated lists with
  page-local search would be a UX lie — server search must land with it. (Roadmap
  P0-4 + P2-13; confirmed, and they must ship together.)
- **Photos on local disk.** Single-host named volume; silent orphaning on multi-host
  / re-image / DB-without-volume restore. Lost evidence = lost disputes. Needs at
  minimum an abstracted seam with an S3-compatible path documented; full MinIO wiring
  is the medium slice. (Roadmap P0-5; confirmed with a scoping caveat — see §6.)

**Security:**
- **IP-level flood protection missing.** Per-claimant 20/day stops one account, not a
  botnet of throwaway claimants against multipart upload. nginx `limit_req` is a
  half-day config + docs fix with no code risk. (Roadmap P1-7; confirmed — cheap,
  do it.)
- **Single-tenant packaging.** One realm = one customer per full stack. Realm-per-
  tenant templating unblocks sale #1 without touching queries; row-level multi-
  tenancy is the Series-A answer. The missing piece is the decision + the template,
  not the full rebuild. (Roadmap P1-8; confirmed, scoped to (a).)

**UX:**
- **No frontend component tests.** The `.trim()`-on-number reserve bug shipped past
  E2E; E2E stays blind to states its journeys don't visit. Vitest + Testing Library
  on branching logic only (wizard validity, button-enable rules, serverMessage
  branches, guard redirects). (Roadmap P1-6; confirmed.)
- **Stale-data handling.** No refresh/polling, no optimistic concurrency on
  reserve/decision writes; two adjusters post-reassign can silently overwrite. At
  minimum a 409-on-conflict via a version column for the money writes. (Roadmap
  P2-19; confirmed, but scoped to reserve/decision only.)
- **Accessibility evidence.** `:focus-visible` + contrast landed; no screen-reader or
  keyboard-only pass over the wizard. Public-sector buyers require the evidence.
  (Roadmap P2-17; confirmed as a P1 audit pass, not a rebuild.)

**Docs/packaging:**
- **README is stale** (says 140 tests / 11 journeys; truth is 152 / 13 + overview +
  queue-filters; V7 → V8, no dashboard/my-claims mention). A buyer reading setup
  docs that undercount the suite notices.
- **No demo story.** No seed-and-reset script for a sales demo, no landing/marketing
  page, no tenant branding/white-label, no billing/entitlement sketch, no admin
  onboarding flow, no audit-export for regulators, no SLA/support story. None of
  these are code-heavy; all get asked. (All missing from the roadmap — see gap
  table.)
- **Uncommitted skill fork** (`.agents/skills/enterprise ui/` + `design examples/`
  untracked) — bus-factor risk either way. Decide: commit or delete.

## 5. What this means for scope

P0 for "ready to sell and demo" = policy admin/import + email outbox + retry +
metrics/alerting baseline + queue pagination (with server search) + photo-storage
abstraction (or a justified, migration-pathed deferral) + demo seed/reset +
README/operations truth. P1 pilot order: reopen/appeal, staff surface, IP limits,
frontend tests, audit export, notification preferences, realm-per-tenant template.
Explicitly out: partial/multiple payments (breaks the atomicity story mid-sale),
row-level multi-tenancy, SMS, automated adjudication, i18n/offline, reopening beyond
the supervisor-only transition.

## 6. Notes and discrepancies found during orientation

- `docs/progress.md` says 151 backend tests; the suite reports **152, 0 failures**
  (19 surefire classes). The +1 predates this session; requirements pin 152 as the
  floor, not 151.
- Frontend build emits a pre-existing budget warning (`app.css` 4.22 kB vs 4.00 kB
  budget, +219 bytes). Cosmetic; not fixed in this pass unless touched anyway.
- `docs/decisions.md` was read through the slice-4 entry (output cap at line 710);
  later historical entries were not re-read — nothing in the visible portion changes
  the load-bearing list above, and the newest (production-push) entry was fully read.
- `design examples/` and `.agents/skills/enterprise ui/` are untracked (`git status`).
  The skill was still loaded and followed for frontend work.
