# Sale-readiness requirements — the build contract

Date: 2026-09-07. Status: approved for build (autonomous pass).
Input: `docs/sale-readiness-analysis.md` (§4 gaps) +
`docs/sale-readiness-roadmap-review.md` (verdicts + missing items).
Scope rule (YAGNI): P0 sale blockers only, tight enough to actually build. Every
requirement states its story, changes, acceptance criteria with the pinning test
layer, and what NOT to break. Load-bearing rules from the analysis §2 apply to all.

Conventions: `[U]` unit, `[I]` backend integration (Testcontainers/Mailpit),
`[E]` Playwright E2E (hermetic :8082/:4200), `[B]` production build,
`[D]` docs-only. "Floor" = 152 backend tests green, 13/13 journeys green —
nothing merges below it.

## P0 — sale blockers (this pass)

### R1 — Policy admin + CSV import (supervisor)

**Story.** As a supervisor I can load my carrier's book (create policies, import a
CSV with per-row errors, retire dead policies) so the first sales question has a
demoable answer. Policies are still never deleted — claims reference them.

**API/DB/UI.**
- DB: V9 migration — `policy.status` (`ACTIVE`/`RETIRED`, default `ACTIVE`) +
  `policy.created_at`; keep the `policy_number` unique constraint (duplicate guard
  stays at the DB level, surfaced cleanly). No other column changes.
- API (supervisor-only, matching the authority-config URL rules in
  `SecurityConfig`): `POST /api/policies` (create: number + product code + holder
  name/email + coverage JSON passthrough; unknown product code → 400 with the valid
  list), `POST /api/policies/import` (multipart CSV, ≤ 500 rows; per-row
  `{row, policyNumber, ok, error}` result, HTTP 200 with the summary — never a bare
  500 on row 400), `POST /api/policies/{n}/retire` (ACTIVE → RETIRED; RETIRED stays
  readable for history), `GET /api/policies/admin` (all rows incl. RETIRED, newest
  first, paginated per R4 default size). FNOL rejects RETIRED policies with the
  existing policy-mismatch message shape (no new claimant-visible branch).
- CSV: header `policy_number,product_code,holder_name,holder_email,coverage`
  (coverage optional raw JSON string); template downloadable from the UI.
- UI: supervisor `Policies` screen (banner card + icon-tile section grammar, same
  tokens): table, create form, CSV upload with dry-run preview → confirm, retire
  action with confirm, error-row display. Nav entry under internal sidebar.
  Landing route reuse: no new public route.

**Acceptance.**
- [I] create → FNOL against the new policy classifies + assigns; duplicate number →
  409 with a clean message; unknown product → 400 naming valid codes.
- [I] import of a mixed CSV (good + bad rows) returns per-row errors and persists
  only valid rows; > 500 rows rejected; retired policy FNOL → policy-mismatch 400.
- [I] non-supervisor on all four endpoints → 403 (adjuster/claimant), 401 anonymous.
- [E] new journey: supervisor imports a 3-row CSV (1 bad), sees the error row,
  files an FNOL against an imported policy as a claimant, claim appears in queue.
- [D] `api.http` examples for all four; operations note on import limits.

**Do NOT break:** seeded `POL-10001`/`POL-20002` rows and product codes untouched;
FNOL match semantics unchanged (number + holder name/email); visibility wall
(holder PII never anonymous — admin list is supervisor-only); V1–V8 untouched.

### R2 — Email outbox + retry

**Story.** As a carrier I can prove every decision notice was sent: no closure email
is ever silently lost on SMTP outage; failures retry with backoff and surface.

**API/DB/UI.**
- DB: V10 `email_outbox` — `id, claim_id FK, kind (FNOL|ASSIGNMENT|DECISION), to_
  address, subject, body, status (PENDING|SENT|FAILED), attempts, next_attempt_at,
  last_error, created_at, sent_at`. Index on `(status, next_attempt_at)`.
- Backend: closure paths (adjuster approve/deny, supervisor approve/deny) and FNOL +
  assignment write the outbox row **in the same transaction** (never the send);
  `@Scheduled` dispatcher (every 60s, `claims.outbox.*` props: max-attempts 8,
  backoff 1m→4h exponential, batch 50) sends via existing senders, marks
  SENT/FAILED, records `last_error`. After max attempts: FAILED + error log with
  claim number + `X-Request-Id`-style reference (operations runbook documents the
  triage query). Existing Mailpit assertions keep passing unchanged (senders keep
  their signatures; the dispatcher calls them).
- API: `GET /api/outbox` (supervisor-only, paginated): rows newest first with
  status filter; `POST /api/outbox/{id}/retry` (supervisor-only): resets a FAILED
  row to PENDING. Both documented in `api.http`.
- UI: supervisor `Email outbox` panel (or tab on overview): depth + failure counts,
  filter by status, retry button. Quiet toast on retry.

**Acceptance.**
- [I] SMTP down at closure → claim still closes, outbox row PENDING; SMTP restored
  → dispatcher sends (Mailpit receives), row SENT with attempts ≥ 1.
- [I] poison address (always-fails) → attempts cap, status FAILED, `last_error`
  set; supervisor retry → PENDING again.
- [I] outbox endpoints: supervisor 200, adjuster/claimant 403, anonymous 401;
  retry of a SENT row → 400.
- [E] new journey (or extend decision journey): supervisor opens outbox, sees the
  decision row SENT for the just-closed claim.
- [B] scheduler disabled in tests via `claims.outbox.enabled=false` (same pattern
  as `claims.aging.enabled`), dispatcher driven directly with fixed instants.

**Do NOT break:** closure atomicity (outbox write rides the same transaction —
never a second commit); best-effort ordering preserved (mail never rolls back a
decision); audit `DECISION` rows unchanged; all existing Mailpit tests green.

### R3 — Metrics / alerting baseline

**Story.** As the operator I can dashboard and page on the system: request/claim/
mail numbers exist at a scrape endpoint instead of in log-grep.

**API/DB/UI.**
- Backend: `spring-boot-starter-actuator` + Micrometer Prometheus registry;
  `GET /actuator/prometheus` exposed **supervisor-scoped** (authenticated,
  SUPERVISOR role — never public; readiness/liveness stay public). Counters/gauges:
  `claims_fnol_total`, `claims_decisions_total{outcome}`,
  `claims_escalations_total{target}`, `claims_queue_depth{level}`,
  `claims_outbox_pending`, `claims_outbox_failed_total`, `claims_fnol_rejected_total
  {reason}`, http server timing (default), JVM defaults. No new tables.
- Docs: `docs/operations.md` + README gain a "what to alert on" table (outbox
  FAILED growth, 5xx rate, FNOL 429 spike, queue depth by level) with example
  PromQL. Grafana dashboard JSON explicitly deferred to P1.

**Acceptance.**
- [I] authenticated supervisor scrapes `claims_fnol_total` incrementing after an
  FNOL; anonymous/claimant → 401/403.
- [D] alerting table present with four rules + PromQL; runbook triage query for
  FAILED outbox rows.

**Do NOT break:** `/api/health` + `/api/ready` semantics and publicity unchanged;
no per-claim PII in metric labels (claim numbers never label values).

### R4 — Pagination + server-side search (queue, escalations, mine, admin lists)

**Story.** As an adjuster with a real backlog my queue loads fast and search finds
claims beyond the visible page.

**API/DB/UI.**
- API: `GET /api/queue`, `GET /api/escalations`, `GET /api/claims/mine`, `GET
  /api/policies/admin`, `GET /api/outbox` accept `page` (0-based, default 0),
  `size` (default 25, max 100), `q` (claim/policy number, holder-adjacent location/
  description substring — never internal notes), `status` (queue-relevant values).
  Response `{content[], page, size, totalElements, totalPages}`. Stable sort
  `created_at, id` preserved (V8 indexes cover it). Claimant `q` searches own rows
  only (wall holds).
- UI: all five lists get load-more (adjusters work top-down — no numbered pages);
  search input + status filter hit the server (debounced), replacing client-side
  `filter()`; empty/filtered-to-zero states kept per existing copy.

**Acceptance.**
- [I] 30 seeded claims → pages of 25/5 with stable totals; `q` matches across
  pages; status filter counts match; claimant `q` never returns another's claim.
- [E] queue pager journey: file 2 claims, page size small via query override if
  exposed (else assert load-more affordance + server round-trip on search).
- Existing journey `queueShows` terminal-wait logic preserved; journeys keep green.

**Do NOT break:** default responses stay backward-compatible in shape for existing
E2E (paginated envelope is a **new** contract — update the three existing frontend
call sites + their tests in the same slice); ordering stability; wall on `/mine`.

### R5 — Photo-storage seam (S3 deferred with migration path)

**Story.** As a buyer my evidence photos are not silently hostage to one disk, and
the path to object storage needs no schema migration later.

**API/DB/UI.**
- Backend: formalize `PhotoStorage` as an interface (`store/load/deleteClaimDir`);
  filesystem stays the only implementation + local-dev default. `attachment.
  storage_path` stops receiving absolute paths: store the **object key**
  (`{claimId}/{uuid}{ext}`) with the base dir resolved at runtime (V11 migrates
  existing rows path → key; rollback-safe: keys resolve under the old dir too).
- Docs: operations runbook gains the volume-restore pairing checklist (already
  partially there — make it a pre-restore gate) + a "moving to S3" page: interface
  to implement, config keys, backfill job sketch. `docker-compose.prod.yml` keeps
  the named volume.
- Full MinIO wiring: explicitly P1 (requirements records the deferral + path).

**Acceptance.**
- [I] FNOL with photo → key-shaped `storage_path`; binary download unchanged;
  rollback-delete still cleans the claim dir; V11 migrates a legacy absolute path
  row to key form.
- [E] journey-1 photo assertions unchanged (no new journey).
- [D] S3 migration page + restore-pairing gate present.

**Do NOT break:** attachment download auth (assignee/supervisor, 404 otherwise);
upload caps (5 files, 10 MB, image/*); no V1–V8 edits.

### R6 — IP-level flood protection (config, this pass)

**Story.** As an operator a botnet of throwaway claimant accounts cannot cheaply
flood multipart FNOL.

**Changes:** nginx `limit_req` on `/api/claims` (burst + delay; values documented),
prod compose wires it, `docs/operations.md` records the tuning note + "app-layer
per-IP accounting is the WAF's job" decision. No Java changes.

**Acceptance.** [D] snippet present + values justified; compose diff reviewed.
Deliberately no automated test (infra config, outside all three suites).

### R7 — Packaging/docs P0s (no new backend logic)

- **Demo seed + reset:** `npm run demo:seed` loads a believable book (6 policies,
  claims at each ladder rung incl. an escalation + a closed claim, wall-safe marked
  demo data) + `npm run demo:reset`. [E] one journey runs against seeded demo claim
  (or docs-verified manual run recorded in progress.md).
- **README + operations truth:** counts (152 tests / 13+ journeys / V1–V11),
  dashboard/my-claims/outbox/policies endpoints, alerting table, restore-pairing
  gate. [D] reviewed diff.
- **Skill-fork commit:** commit `.agents/skills/enterprise ui/` + `design examples/`
  (or record the delete decision with reason). [D].
- **Tenancy + auth-hosting objection paragraphs:** realm-per-tenant decision (option
  (a) for sale #1, (b) is Series-A) + OIDC-issuer paragraph for "plug into our
  IdP?", both in decisions.md + operations. [D].
- **Carrier onboarding checklist:** realm render → provision → authority limits →
  import policies → demo seed → verify gate. [D].

## P1 — pilot queue (priority order, NOT this pass)

1. **Frontend component tests** (Vitest + Testing Library): FNOL wizard validity,
   reserve/decision/authority button-enable rules (incl. the number-no-`.trim()`
   regression), `serverMessage` proxy-leak branches, `badgeClass`, guard redirects.
2. **Reopen/appeal:** supervisor-only `REOPENED` transition + rationale, audit-linked
   (new rows reference the original `DECISION` row id), gate re-applies on the new
   decision, claimant sees "reopened" step, no new mail (decision mail covers the
   outcome). Matrix at [U]+[I], one [E] journey.
3. **Staff surface:** supervisor roster with open-load counts, deactivate (reassigns
   open claims via `ClaimAssigner`), Keycloak admin-API provisioning (new secret
   scope decision first).
4. **Notification preferences:** per-event opt-out at filing, stored on claim,
   honored by the outbox dispatcher.
5. **Audit export:** supervisor CSV/JSON per claim + per period (read-only,
   paginated) — the regulator answer next to "100% actor + rationale."
6. **409-on-conflict:** `version` column on claim, reserve/decision carry it,
   409 + "someone else changed this" UX. (Live queue refresh stays P2.)
7. **A11y audit pass:** screen-reader + keyboard-only run of all journeys, fixes as
   found, evidence note in docs.
8. **GDPR design note:** anonymize-PII-in-place vs append-only-audit answer + docs;
   build in P2.
9. **MinIO/S3 wiring** (from R5), **white-label** (carrier name/logo/colors +
   email voice via config), **landing page**, **onboarding UI**, **SLA page**,
   **Grafana JSON** (from R3).

## Explicit non-goals (this pass AND pilot)

Partial/multiple payments (uniqueness → sum migration risks the atomicity story —
buyer-roadmap line only); row-level multi-tenancy (Series-A); SMS; automated
adjudication/fraud; reopening beyond the supervisor-only transition; i18n/offline;
real money movement; mobile app; websockets.

## Global acceptance (Phase 5 gate)

Backend ≥ 152 tests green (new: policy-admin ~10, outbox ~8, pagination ~8,
storage-seam ~5, metrics 2 → target ~185); frontend `production build` green;
E2E 13/13 existing green + 2 new journeys (policy-import, outbox) green on the
hermetic stack; `docs/progress.md` + `docs/decisions.md` updated; `api.http`
covers every new endpoint; single commit with a clear message. No secrets in git.
No V1–V8 edits. No broken testids/exact-text contracts (additive only).
