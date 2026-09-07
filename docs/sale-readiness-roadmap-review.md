# Roadmap review — critiquing `docs/improvement-roadmap.md`

Date: 2026-09-07. The roadmap (20 items, P0/P1/P2, dated 2026-09-07, post-`d24344f`)
is a strong senior review: the P0 top-3 are right, the failure-mode reasoning is
concrete, and the suggested order is honest. This note records where it is wrong,
vague, or incomplete — and becomes the input to `docs/sale-readiness-requirements.md`.

Cross-cutting finding: **no item has acceptance criteria and no estimate is
falsifiable** ("one slice", "small/medium slice", "1–2 days", "half a day",
"days/weeks"). Every item below notes what a real acceptance bar needs. The
requirements doc pins test layers per requirement; nothing here changes code yet.

## Item-by-item verdicts

### P0 items

**1. Policy onboarding — CORRECT, still P0, underspecified.**
The #1 sale blocker, correctly identified. But "one vertical slice (DB already shapes
it…)" hides the real work: CSV format + per-row error contract, retire-vs-delete
semantics (claims FK it — retire must keep history readable), duplicate-number UX,
who may import (supervisor-only, matching the authority-config surface), and the FNOL
lookup change (today FNOL matches on typed name/email; with a real book the form
needs number-lookup + holder verification, same semantics). No acceptance criteria.
Verdict: keep P0, expand scope note; requirements R1 pins it.

**2. Email outbox + retry — CORRECT, still P0, underspecified.**
Right failure analysis (silent decision-notice loss = compliance exposure) and right
pattern (outbox in the closure transaction, async delivery, bounded retries +
backoff, alert after N). Missing: which emails ride the outbox (decision only, or
all three senders — FNOL/assignment/decision), ordering/dedupe guarantees, retention
of sent rows, backoff parameters, what "alert" means concretely (log line? dashboard
gauge? supervisor-visible status?), and that existing Testcontainers-Mailpit
assertions must keep passing unchanged. "One slice" is not an estimate. Verdict:
keep P0; requirements R2 pins it.

**3. Metrics/alerting — CORRECT direction, OVERSCOPED, still P0-core.**
Actuator + Micrometer + Prometheus endpoint (supervisor- or network-scoped, never
public) is right, and diligence does ask. But "starter Grafana dashboard JSON" is
gold-plating for the first sale — the sellable bar is: endpoint live, a handful of
counters/gauges (FNOL rate, queue depth by level, outbox depth/failures, 429 rate,
decision latency, JVM basics), and the operations doc saying what to page on.
Dashboard JSON ships when there is a second environment to point it at. Verdict:
keep the P0, cut the Grafana JSON to P1.

**4. Queues without pagination — CORRECT, still P0, WRONGLY SPLIT from 13.**
Full-list endpoints + client-side `filter()` is a genuine scale cliff and the fix
direction (pagination on queue/escalations/mine, stable `ORDER BY created_at, id`)
is right. But search is specified here as "frontend gets load-more" while item 13
admits search must move server-side too — "don't ship paginated lists with
page-local search, that's a UX lie" is correct, so 4 and 13 are **one slice, not
two**. Also "E2E seeds 30+ claims" is the most expensive way to pin it: page
mechanics belong at integration (deterministic row counts), E2E only proves the
pager renders and advances. Verdict: keep P0, merge 13 into it, pin mostly at
integration.

**5. Photos on local disk — RIGHT RISK, WRONG P0 SHAPE.**
Lost-evidence analysis is correct and the `PhotoStorage` seam does exist. But
"S3-compatible object storage (MinIO in compose) + one-shot migration job" as a
pre-sale slice carries real checksum/migration risk in the middle of a sale motion,
for a scale (hundreds of claims/year) where a single-host volume works. The honest
P0 is smaller: **formalize the `PhotoStorage` interface, keep filesystem as the
local-dev default, store a storage key (not an absolute path) so a future backend
swap needs no schema change, harden the volume-restore pairing in the runbook, and
document the S3 path**. Full MinIO wiring is P1. Verdict: half-right — seam + key
now (P0), backend switch later (P1). Requirements R5 records the deferral with the
migration path.

### P1 items

**6. Frontend unit tests — CORRECT, still P1, well-scoped.**
"Branching logic only, not getters" is exactly right, and the three burned forms
first is the right order. Could argue P0 (the `.trim()` bug shipped past E2E), but
E2E guards the journeys and the suite is green — P1 holds. Missing: runner choice
justification and the file list. Verdict: keep P1, first in the P1 queue.

**7. IP-level rate limiting — CORRECT, UNDER-PRIORITIZED.**
nginx `limit_req` + docs is a half-day, zero-code-risk change against the most
expensive endpoint (multipart upload). Burying it in P1 while it costs nothing is
wrong — it ships in this pass as a P0-adjacent config item. "Application-layer
per-IP accounting is NOT recommended" is correct and should be recorded as the
decision. Verdict: promote to this-pass scope (requirements R6).

**8. Single-tenant Keycloak — CORRECT analysis, CORRECT P1, missing the decision.**
Realm-per-tenant (a) vs row-level (b) framing is right, and (a)-for-first-sale is
right. What's missing is the actual decision record plus the cheap artifacts: a
templated compose pattern and a one-paragraph objection-handler ("dedicated realm +
dedicated database per carrier; no shared queues; row-level tenancy is the Series-A
answer"). Verdict: keep P1, but the decision + docs paragraph ship now.

**9. Reopen/appeal — CORRECT, UNDER-PRIORITIZED.**
"Pilot users hit this in month one" is the author's own evidence — that sentence
makes it a pilot blocker, not mid-P1. Closure-terminal was a v1 simplification, and
the state machine + audit-append-only design already supports a supervisor-only
`REOPENED` transition with rationale. Missing: eligibility states, gate
re-application on the new decision, claimant visibility of reopen, whether reopen
re-sends mail, and the audit link back to the original closure. Verdict: promote to
P1-first (requirements R7); only kept out of P0 so P0 stays buildable in one pass.

**10. Partial/multiple payments — CORRECT description, WRONG SIDE OF THE LINE.**
Tranches are real, but touching `payment.amount == indemnity_amount` + the
uniqueness constraint + single-payment atomicity mid-sale risks the one invariant
diligence will actually test. "Keep single-payment as the default path" hand-waves
the uniqueness→sum migration. Verdict: **explicit non-goal for this pass** —
document as the priced roadmap item shown to buyers, build after customer two.

**11. Staff management — CORRECT, still P1.**
Roster/capacity/deactivation/reassign-on-deactivate via existing `ClaimAssigner` is
the right shape. Missing: Keycloak admin-API credential scope (new secret surface —
needs a decision), the L2-only-adjuster-deactivated edge, and auditing the
deactivation itself. Verdict: keep P1, after reopen.

### P2 items

**12. Coverage JSON blob — CORRECT P2.** Structured coverage fields + ticked
checklist (audited) is the right evolution; documentation-first is right. No change.

**13. Client-side search — CORRECT content, WRONG HOME.** This is not a P2; it merges
into the P0 pagination slice (see 4). As a standalone P2 it would bless the "UX lie"
the author warns about. Verdict: merge into P0, delete as separate item.

**14. Notifications beyond email — CORRECT, SPLIT IT.** SMS/provider adapter is P2+.
But **notification preferences on filing** (email on/off per event) is cheap,
buyer-visible, and rides the outbox from item 2 — that half is P1. Verdict: split.

**15. Rationale structure — CORRECT P2.** Codes + text (not instead of text) is the
right backward-compatible shape. Note: arbitrary minimum-length validation can
backfire (gaming); denial-reason codes need carrier product input first. No change.

**16. Locale/timezone — CORRECT P2, with a cheap forward-compat note.**
`Intl.NumberFormat` + tenant-TZ schedule are customer-two work. The cheap now-move:
never concatenate currency in new code (the `£` + `toFixed(2)` E2E contracts stay),
keep the TZ assumption documented. No change.

**17. Accessibility — CORRECT content, UNDER-PRIORITIZED.**
No screen-reader pass, no keyboard-only wizard run, no reduced-motion check — and
"enterprise buyer requires evidence" is right. Evidence-gathering (one audit pass
over the 13 journeys + fixes as found) is P1 work, not P2 polish. A rebuild would be
P2; the audit is not. Verdict: promote the audit pass to P1.

**18. GDPR/retention — CORRECT diagnosis, SPLIT IT.**
Anonymize-PII-in-place vs append-only-audit conflict is correctly identified and
needs a designed answer before an EU pilot. For a UK-first sale the shippable unit
is the **design note + docs paragraph** (P1); the build is P2. Verdict: split.

**19. Stale-data races — CORRECT, SPLIT IT.**
Polling/SSE for queue counts is P2. But silent overwrite of money writes is a
correctness issue: a `version` column + 409-on-conflict on reserve/decision is a
small, high-value P1 slice (the reassign panel made collisions likelier, per the
author). Verdict: split — 409 now (P1), live refresh later (P2+).

**20. Skill fork — CORRECT to flag; DECIDE: COMMIT.**
`.agents/skills/enterprise ui/` + `design examples/` are untracked and shaped the
shipped look — that is product IP with provenance value. Deleting loses the "why
does it look like this" record; upstream does not cover the Insure-Craft language.
Verdict: commit it (requirements records this as a docs/packaging task).

## Gap table

| Roadmap item | Still valid? | Missing pieces | New / changed items |
|---|---|---|---|
| 1 policy admin (P0) | Yes, P0 | CSV format + row-error contract; retire semantics; importer role; FNOL lookup UX; acceptance criteria + real estimate | R1 pins all of it |
| 2 email outbox (P0) | Yes, P0 | Which senders ride it; backoff/retry params; retention; alert channel; Mailpit pins | R2 pins all of it |
| 3 metrics (P0) | Core yes; Grafana JSON no | Falsifiable bar (which counters, scoped where, page-on-what) | Cut dashboard JSON to P1; R3 is endpoint + counters + runbook |
| 4 pagination (P0) | Yes, P0 | Server search (merged from 13); integration-first pinning, not 30-claim E2E | Merge 13 in; R4 |
| 5 object storage (P0) | Risk yes; full S3 now no | Interface + key-not-path + runbook hardening + documented S3 path | Seam now (R5), MinIO wiring P1 |
| 6 frontend tests (P1) | Yes | Runner + file list + branch inventory | R8 (P1-first queue) |
| 7 IP limits (P1) | Yes, promote | nginx snippet + burst params + load-test note | Ships this pass (R6) |
| 8 tenancy (P1) | Yes, option (a) | Decision record + template + objection paragraph | Decision + docs now; build P1 |
| 9 reopen (P1) | Yes, promote to P1-first | Eligibility; gate re-application; claimant view; mail?; audit link | R7 |
| 10 tranches (P1) | No — defer past sale | Sum-check migration design | Explicit non-goal; buyer-roadmap line |
| 11 staff mgmt (P1) | Yes | Admin-API secret scope; L2-only edge; deactivation audit | P1, after reopen |
| 12 coverage (P2) | Yes | — | Unchanged P2 |
| 13 search (P2) | Merged into 4 | — | Delete as separate item |
| 14 notifications (P2) | Split | Preferences (P1) vs SMS (P2+) | Preferences P1, SMS later |
| 15 rationale (P2) | Yes, with caution | Min-length gaming note; needs carrier input | Unchanged P2 |
| 16 locale (P2) | Yes + cheap note | No-concat rule for new code | Unchanged P2 |
| 17 a11y (P2) | Promote audit to P1 | Journey-by-journey audit evidence | Audit P1, rebuild P2 |
| 18 GDPR (P2) | Split | Design note + docs now (P1); build P2 | Split |
| 19 staleness (P2) | Split | 409-on-conflict now (P1); live refresh P2+ | Split |
| 20 skill fork (P2) | Decide: commit | Provenance record | Commit this pass |

## What's MISSING from the roadmap entirely

A buyer asks these; the roadmap answers none:

1. **Demo seed + reset.** No script loads a believable book (policies, aged claims at
   each ladder rung, an escalation, a closed claim) and resets it. Every sales demo
   today starts from two seed rows and hand-filed claims. Needs: `npm run demo:seed`
   + reset, documented, wall-safe (demo data clearly marked).
2. **Tenant branding / white-label.** One hardcoded "Claims Processing" voice, one
   palette, no carrier logo/name/livery surface. Buyers ask "does it look like us?"
   Needs: carrier name/logo/colors via config + email-template voice (P1, small).
3. **Billing / entitlement story.** Nothing says what is sold (per-seat? per-claim?
   tiers?) or gates it. No code needed pre-sale — but a one-page story + the seam
   where entitlements would hang (supervisor role split) is diligence material.
4. **Admin onboarding flow.** First supervisor, first adjusters, first authority
   table: today = Keycloak console + SQL + realm render. Needs a documented
   carrier-onboarding checklist (realm render → provision → import policies → verify
   gate limits → demo seed), even before any UI.
5. **Policy import UX.** Item 1 covers import mechanics; nobody covers the human side:
   template CSV download, dry-run validation preview, error-row download. The
   difference between "we support import" and a demoable import.
6. **Notification preferences.** Covered above (split from 14): per-event opt-out at
   filing, stored on the claim, honored by the outbox. Cheap, visible, asked-for.
7. **Audit export for regulators.** The immutable log exists but cannot leave the
   system except per-claim JSON on one endpoint. Needs: supervisor CSV/JSON export
   per claim + per period (read-only, paginated), documented as the compliance
   answer alongside "100% decisions have actor + rationale."
8. **SLA / support story.** No statement of response times, no support channel, no
   severity definitions, no status page story. One docs page; buyers universally ask.
9. **Landing / marketing page.** The `/` home is an operator tool, not a sellable
   front door: no product explanation, no carrier branding, no "file a claim /
   sign in" wayfinding for a cold visitor. Small public page, big demo difference.
10. **Hosted-auth vs self-hosted Keycloak objection.** The stack mandates self-hosted
    Keycloak; buyers ask "can you run auth for us / plug into our IdP?" Needs a
    decision + docs paragraph (OIDC-standard answer: any OIDC IdP via issuer URI;
    hosted-Keycloak option as managed service), not code.
11. **README/operations truth.** README still claims 140 tests / 11 journeys / V7
    (truth: 152 / 13 / V8 + dashboard + my-claims). Stale setup docs are a
    diligence tell. Fixed this pass.
12. **Backup/restore tested?** The runbook documents `pg_dump` + volume snapshot but
    nothing ever rehearsed it. A tested restore (even once, documented) is worth
    more than three new features in diligence.

## Suggested order (revised)

1. **This pass (P0 sale blockers):** R1 policy admin/import, R2 email outbox, R3
   metrics baseline, R4 pagination + server search, R5 storage seam (S3 deferred
   with path), R6 IP limits (config), plus demo seed/reset, README/ops truth,
   skill-fork commit, tenancy decision + objection paragraphs.
2. **P1 pilot queue:** frontend tests, reopen/appeal, staff surface, notification
   preferences, audit export, 409-on-conflict, a11y audit pass, GDPR design note,
   MinIO wiring, white-label, landing page, onboarding checklist, SLA page.
3. **After customer two:** tranches (10), row-level tenancy (8b), SMS, coverage
   rules, locale, live refresh.
