# Improvement roadmap — Claims Processing System

Date: 2026-09-07. Status of the app when this was written: all 8 plan slices green,
151 backend tests, 13/13 E2E journeys, production push committed (`d24344f`), running
at http://localhost:4200.

This document lists everything that **can and should** be changed or improved before
this is sold, ordered by sale-blocking importance. Each item states the **problem**
(what's true today), the **risk** (what it costs if ignored), and the **fix**
(concrete direction, not vague advice). Items already covered by `docs/decisions.md`
are not repeated here — this is forward-looking only.

Priorities: **P0** = a buyer will find this in diligence or hit it in week one.
**P1** = needed for a paid pilot. **P2** = needed to scale past the first customer.

---

## P0 — sale blockers

### 1. No policy onboarding (P0, product gap)

**Problem.** Policies are two seeded rows (`POL-10001`, `POL-20002`). There is no way to
create, import, or retire a policy except SQL migrations. FNOL matches on policy number
+ holder name/email typed by the claimant — which only works because the seed data is
memorized in tests.

**Risk.** No carrier buys a claims system they can't load their book into. This is the
first question in any sales call and today the answer is "we do it for you by hand."

**Fix.** A supervisor policy-admin surface: `policy` CRUD (create + retire; never
delete — claims reference it), CSV import with per-row validation errors, and a
duplicate-policy-number guard at the DB level (unique constraint already exists —
surface the error cleanly). FNOL should then offer policy lookup by number with
holder verification, unchanged semantically. Estimated: one vertical slice (DB already
shapes it; the work is the import validation + UI).

### 2. Emails are best-effort with no retry (P0, reliability gap)

**Problem.** `FnolEmailSender`, `AssignmentEmailSender`, `DecisionEmailSender` send
synchronously-ish with fire-and-forget error handling. If Mailpit/SMTP is down at the
decision moment, the decision email is lost silently — the claim still closes.

**Risk.** A missing decision notice isn't a cosmetic bug, it's a legal/compliance
exposure: the carrier can't prove the claimant was notified. The one email that must
never drop is the one with no retry.

**Fix.** Outbox pattern: write the email intent into an `email_outbox` table in the
closure transaction, deliver asynchronously with bounded retries + backoff, alert after
N failures. The audit log already records the decision; the outbox records the
notification attempt. Estimated: one slice; Testcontainers Mailpit assertions already
exist to pin it.

### 3. No metrics, no alerting (P0, operability gap)

**Problem.** Observability today is `/api/health`, `/api/ready`, and request-id logs.
There is no count of FNOLs/hour, decision latency, queue depth by level, email failures,
429 rate, or JVM health — nothing a buyer can put on a dashboard or page on.

**Risk.** You cannot sell an ops tool you operate blindfolded. First production incident
without metrics becomes guesswork, and enterprise diligence asks for this explicitly.

**Fix.** Micrometer + Prometheus endpoint (Spring Boot Actuator, supervisor-scoped or
network-scoped — never public), with a starter Grafana dashboard: claim cycle time,
open-by-level, escalation rate, email-outbox depth, 4xx/5xx by endpoint. Estimated:
small slice; most of it is dependency + config + dashboard JSON.

### 4. Queues have no pagination (P0, scale cliff)

**Problem.** `GET /api/queue`, `/api/escalations`, and `/api/claims/mine` return full
lists. Verified today: `grep Pageable backend/src/main` returns nothing. Works at tens
of claims; degrades linearly past hundreds.

**Risk.** The first customer with a real backlog gets a slow queue page and a slower
database. This is a "rewrite the endpoint under load" situation — cheaper now.

**Fix.** Keyset or offset pagination on all three list endpoints (default page size 25–
50), `ORDER BY created_at, id` already exists to paginate on. Frontend gets
load-more/infinite scroll, not numbered pages (adjusters work top-down). Estimated:
small slice per endpoint; E2E seeds 30+ claims to pin it.

### 5. Photos on local disk don't survive the hosting it ships with (P0, deploy gap)

**Problem.** `claims.uploads.dir` is a local path; `docker-compose.prod.yml` mounts a
named volume. That works on one host and silently orphans files on any multi-host,
ephemeral, or re-imaged deployment. The DB restore runbook already warns about
volume-restore pairing — the warning exists because the failure mode is real.

**Risk.** Lost evidence photos = lost coverage disputes. Silent data loss is the worst
category of bug for an insurance product.

**Fix.** Abstract `PhotoStorage` behind S3-compatible object storage (MinIO in
compose for dev, any S3 endpoint in prod), keeping the filesystem implementation as the
local-dev default. Store the object key (not path) in `attachment`. Migrate existing
files with a one-shot job. Estimated: medium slice; the `PhotoStorage` seam already
exists, which is why this is P0-cheap instead of P1-expensive.

---

## P1 — needed for a paid pilot

### 6. No frontend unit tests (P1, test gap)

**Problem.** Frontend coverage is E2E + production build only. The reserve-button
`.trim()` bug (number-bound input has no `.trim()`, button never enabled) shipped past
E2E because journeys always sign in first — a component test catches it in
milliseconds. The testing rule already says this
(`.agents/skills/project workflow/rules/testing-web.md`); the tests don't exist.

**Risk.** Every form-logic bug costs a full browser run to catch, and E2E stays blind
to states its journeys don't visit. Velocity halves as the UI grows.

**Fix.** Vitest + Testing Library: step-validity logic (FNOL wizard), button enable
rules (reserve, decision, authority save), `serverMessage` proxy-leak branches,
`badgeClass` mapping, guard redirect paths. Keep it to branching logic — not getters.
Estimated: 1–2 days; start with the three forms that already burned us.

### 7. Rate limiting is per-claimant only, no IP-level protection (P1, security gap)

**Problem.** The 20/day/claimant guard stops one account's burst or retry loop. It does
nothing against distributed submission floods — and `/api/claims` accepts multipart
uploads (the most expensive request type) from any authenticated claimant.

**Risk.** Cheap denial-of-wallet (storage) and denial-of-service (multipart parsing,
photo writes) from a botnet of throwaway claimant accounts.

**Fix.** nginx `limit_req` on `/api/claims` (burst + delay, documented in
`docs/operations.md`), plus a reverse-proxy connection cap. Application-layer
per-IP accounting is deliberately NOT recommended — that's the WAF's job.
Estimated: half a day (config + docs + a load-test note).

### 8. Single-tenant Keycloak is a sales objection (P1, packaging gap)

**Problem.** One realm, one `claims-frontend` client, roles as the only tenancy. A
second customer means a second full stack — or shared queues, which is a non-starter.

**Risk.** Every new customer costs a full deployment. No self-serve trial is possible.

**Fix (choose one).** (a) Realm-per-tenant with the existing compose pattern
templated — cheap, keeps the wall intact, ops-heavy. (b) Single-realm groups-per-
tenant with a `tenant_id` claim + row-level claim filtering — real multi-tenancy,
touches every query, needs its own slice + wall re-verification. For the first sale,
(a) is enough; (b) is the Series-A answer. Estimated: (a) days, (b) weeks.

### 9. No claim reopen / appeal path (P1, product gap)

**Problem.** Closure is terminal by design (`docs/plan.md` lists reopening as out of
scope). Real carriers reopen claims — new evidence, claimant disputes, regulator
asks. Today the answer is "file a new claim," which corrupts cycle-time metrics and
loses the audit trail link.

**Risk.** Pilot users hit this in month one and route around the system (email +
spreadsheet again), which is exactly the workflow this replaces.

**Fix.** `REOPENED` transition: supervisor-only, requires rationale, appends (never
rewrites) audit rows, re-enters assignment at the claim's level. The state machine and
audit-append-only rule already support it — add the transition + endpoint + test
matrix. Estimated: one slice; the authority gate re-applies on the new decision
automatically.

### 10. No partial/multiple payments (P1, product gap)

**Problem.** One payment per claim, always equal to indemnity (`payment.amount ==
indemnity_amount` enforced). Real claims pay in tranches (interim + final, expenses +
indemnity).

**Risk.** Adjusters track the tranches elsewhere, and the system's "single source of
truth" claim quietly becomes false.

**Fix.** `payment` 1—N per claim with a running total gated per-decision (each tranche
approved against authority; total visible on the claim). Keep single-payment as the
default path so existing journeys don't change. Estimated: medium slice; the
uniqueness constraint becomes a sum check.

### 11. Mgmt of adjusters lives in Keycloak, invisible to supervisors (P1, product gap)

**Problem.** Provisioning an adjuster means Keycloak admin console + manual `app_user`
row. A supervisor can't see who exists, who's at capacity, or deactivate someone who
left. The "staff cache out of sync with Keycloak?" log line proves the seam leaks.

**Risk.** Offboarding gap (a departed adjuster's queue just sits), and every staffing
change needs an admin, not a supervisor.

**Fix.** Supervisor staff surface: list adjusters with open-load counts (the
load-balancer already computes this), activate/deactivate (deactivation reassigns open
claims to least-loaded same-level), provision via Keycloak admin API. Estimated: medium
slice; deactivation-reassign reuses `ClaimAssigner`.

---

## P2 — scale and polish past the first customer

### 12. Coverage is a JSON blob, unverified by the system (P2)

FNOL verifies identity (policy number + holder), not coverage. The adjuster "verifies
coverage against the seeded policy" by reading JSON. Any coverage rule (excess,
exclusions, limits) is human judgment with no system check. Fix: structured coverage
fields on `policy` (excess, per-claim limit, exclusions list) + a coverage checklist
the adjuster ticks per claim (audited). Starts as documentation, grows into rules.

### 13. Search is in-memory `filter()`, not database search (P2)

Queue/escalation search filters the loaded page client-side. Fine under pagination
(item 4) only if search moves server-side too: `claim_number ILIKE` + status filter as
query params with the same indexes V8 added. Do it in the pagination slice — don't ship
paginated lists with page-local search, that's a UX lie.

### 14. No claimant notifications beyond email (P2)

Status-check calls are the cost this product claims to kill, but the only proactive
touch is email (FNOL confirm, assignment, decision). No SMS, no in-app notification
center, no "your claim moved" push. Fix: notification preferences on filing + the
outbox from item 2 as the delivery spine. SMS via a provider adapter, same interface
as mail.

### 15. Decision rationale has no structure (P2)

Rationale is free text, required but unvalidated. For audit quality ("100% of
decisions have recorded actor + rationale"), add: minimum length, structured fields
(amount basis, evidence reviewed checklist), and denial-reason codes (reporting +
consistency beat prose for regulators). Backward-compatible: codes + text, not codes
instead of text.

### 16. Timezone and locale assumptions (P2)

Money is GBP-formatted in the UI (`£` + `toFixed(2)`), dates are `yyyy-MM-dd`, the
aging job fires at 03:00 server time. All correct for one UK carrier; all wrong for
the second customer. Fix: tenant locale/timezone in config, `Intl.NumberFormat`
instead of string concat, job schedule in tenant TZ. Cheap if done before customer
two, painful after.

### 17. Accessibility is partial (P2)

`:focus-visible` and contrast fixes landed, but there's been no screen-reader pass, no
keyboard-flow audit of the wizard, no reduced-motion check. A public-sector or
enterprise buyer requires WCAG 2.2 AA conformance evidence. Fix: one audit pass with a
screen reader + keyboard-only run of all 13 journeys, fixes as found.

### 18. No data-retention / GDPR story (P2)

No retention policy, no export-my-data, no erasure path. Insurance data is special-
category personal data in the UK/EU. The append-only audit log (a selling point)
directly conflicts with naive erasure — this needs a designed answer (anonymize
claimant PII in place, keep the audit facts), not a `DELETE`. Fix before the first EU
pilot, not after.

### 19. Stale-data failure modes in the UI (P2)

Queue/detail screens fetch on load with no refresh, no optimistic concurrency
(`If-Match`/version on reserve/decision writes), no "another adjuster changed this"
handling. Two adjusters on one claim (post-reassign) can overwrite each other's notes
silently. Fix: `version` column + 409-on-conflict for reserve/decision, polling or
SSE for queue counts. The reassign panel made this more likely, not less.

### 20. The `enterprise-ui` skill fork (P2, process)

`.agents/skills/enterprise ui/` sits uncommitted in the workspace — a customized skill
that taught the 07b look. Either commit it (if it's product IP worth keeping) or
delete it (if upstream covers it). Uncommitted tooling that shapes the product's look
is a bus-factor risk either way.

---

## Suggested order

1. **Before any paid conversation:** 1 (policy admin), 2 (email outbox), 3 (metrics).
2. **During the pilot:** 4 (pagination), 5 (object storage), 6 (frontend tests), 9 (reopen).
3. **Before customer two:** 7 (IP limits), 8a (realm-per-tenant), 10 (tranches), 11 (staff mgmt), 16 (locale), 18 (GDPR).
4. **As leverage:** 12–15, 17, 19, 20.

Items 1+2+3 are the honest "production-ready to sell" bar. Everything else is priced
into the roadmap you show the buyer — which, given this repo's docs discipline, is a
roadmap they'll actually believe.
