# Requirements V2 — Claims Processing System (rethink)

Status: Draft (for approval — no implementation until approved)
Last updated: 2026-09-07
Based on: `docs/requirements.md` (V1, Approved 2026-09-03) + user V2 direction + 8 clarifications (2026-09-07)

## One-line summary

Evolve the V1 auto-book skeleton into an enterprise-grade, cover-aware claims platform:
a claimant policy cockpit with multi-cover filing, skill-based dynamic assignment, a
staged adjuster workflow (Review → Verification → Decision), cover-level adjudication
with partial approval, a three-tier + supervisor authority ladder, and configurable
SLA — all demonstrable from a rich seeded dataset on day one.

## What changes from V1 (delta map)

| V1 (today) | V2 |
|---|---|
| 2 product codes (`HOME`, `AUTO`), opaque jsonb coverage | Product taxonomy (HEALTH / NON-HEALTH / PROPERTY), structured covers with limits, rating vs non-rating parameters, readable clauses |
| Claimant files FNOL against a typed policy number; one loss = one amount | Claimant cockpit: sign in → see my policies → policy detail → file claim selecting **one or more covers** with per-cover claimed amounts + documents |
| One claim = one indemnity figure = one payment | Cover-level financials (claimed → assessed → approved → deductible/adjustments → net payable); claim aggregate can be `APPROVED`, `DENIED`, or `PARTIALLY_APPROVED` |
| Routing = product `route_level` → least-loaded adjuster of that level | Routing = **product-code skill mapping** → eligible adjusters → least-loaded (level is entry point, not a capability filter) |
| Single `UNDER_REVIEW` work state | Staged workflow: `IN_REVIEW` → `IN_VERIFICATION` → `PENDING_DECISION`, with rejection possible at review and at final |
| Authority L1/L2 + supervisor, gated on `indemnity_amount` | Authority L1/L2/**L3** + supervisor, gated on the **amount being approved** under a documented, configurable rule |
| Fixed aging 3d → L2, 5d → supervisor | Configurable per-product SLA: warning threshold, breach threshold, breach action (escalate-a-rung / supervisor / notify) |
| 3 adjusters, 2 policies, thin seeds | Full dummy-data matrix first: ~8 products, ~12 customers/policies, 6 adjusters + supervisor, 10 scenario claims |

V1 guarantees carry over unchanged: the **visibility wall** (internal figures/notes never
reach claimant surfaces), **404-not-403**, immutable audit log, one-payment-per-claim
record, email on key events (extended with V2 events), Keycloak roles.

## Users (V2)

| Role | What they're trying to do | Self-register? |
|---|---|---|
| Claimant | See my policies and their covers/clauses; file a multi-cover claim with documents; track stage + per-cover outcomes. | Yes (via Keycloak, as V1) |
| Adjuster L1 / L2 / **L3** | Work my bucket through the stages: triage review, verification (digital/physical), assessment, cover-level decisions within my authority; propose-and-escalate above it. | No — provisioned |
| Supervisor | Team + escalations + aging buckets, dashboards, mid-stage reassignment, escalated cover-level decisions, authority/SLA/skill admin, policy admin. | No — provisioned |

L3 is a senior adjuster: high approval limit, still gated, no admin powers. Supervisor is
the only ungated decider and the only admin.

## Core flows

### Flow V2-1 — Claimant policy cockpit + cover-aware FNOL

The signed-in claimant lands on **My policies** (policies whose holder email matches
their account). Each row shows policy number, product, sum insured, status. Opening a
policy shows: holder, product family, sum insured + remaining benefit, the opted
**covers** (each with sub-limit), rating parameters (the numbers that define the cover),
and non-rating clauses (what is covered / excluded / scope). A **File a claim** action
pre-fills the policy. The claimant selects **one or more covers**, enters a claimed
amount per cover (≤ that cover's sub-limit, validated with a plain message), describes
the loss (what/when/where), attaches documents, and submits. The system immediately
returns a **claim number** plus the list of covers filed, and stores the per-cover
selections with the claim. Email confirmation as V1.

Acceptance criteria:
- [ ] A signed-in claimant sees only their own policies with covers, sub-limits, rating parameters, and clauses.
- [ ] Filing with 1–N covers returns a claim number immediately; the stored claim carries each selected cover + claimed amount + documents + description.
- [ ] **Limits constrain, they do not block filing:** a claimed amount above a cover's
      sub-limit is accepted at FNOL (flagged as above-limit for the adjuster's review).
      Assessment and approval then happen *within* the applicable limits — the adjuster
      assesses down to what the cover allows. FNOL is rejected only for missing/invalid
      data, duplicate filing, or a retired/expired policy — never merely because the
      requested amount exceeds a limit.
- [ ] Filing against a RETIRED or EXPIRED policy is rejected with the existing policy-mismatch shape (no signal which check failed).
- [ ] A duplicate filing (same policy + loss date + same cover set within 24h) is rejected with the existing claim number (no second claim).
- [ ] Remaining limits are tracked and shown: remaining policy sum insured and remaining
      per-cover sub-limit. Exhaustion of one cover never touches unrelated covers.

### Flow V2-2 — Skill-based dynamic assignment

On claim creation the system computes the eligible set: adjusters whose
**product-code skill mapping** includes the claim's product and who are active. The
claim's entry level comes from the product's `route_level`; assignment picks the
**least-loaded eligible adjuster at the entry level** (open-claim count, tie-break
lowest staff id — V1 rule preserved). If no eligible adjuster exists at the entry
level, it falls back up one rung at a time (L1→L2→L3); if none exists at any level, the
claim parks `UNASSIGNED` with reason `NO_ELIGIBLE_ADJUSTER`, visible on the supervisor
dashboard as an ops alert. Assignment is atomic under contention (V1 `FOR UPDATE`
discipline, scoped to the eligible set). Assignment email as V1.

Acceptance criteria:
- [ ] A new claim lands with the least-loaded adjuster mapped to its product code at the product's entry level.
- [ ] When several mapped adjusters tie on load, the lowest staff id wins (deterministic).
- [ ] A claim whose product has no mapped adjuster stays `UNASSIGNED` with a machine-readable reason and appears in the supervisor's attention list (never silently dropped, never randomly assigned).
- [ ] Two simultaneous filings to the same product/level never double-assign the same adjuster beyond correct recounting.

### Flow V2-3 — Staged adjuster workflow (Review → Verification → Decision)

The assignee works the claim in three explicit stages, each transition audited with
actor + timestamp + rationale/notes:

1. **Review (triage).** Read description, documents, claimed amounts, policy covers and
   clauses. Either **reject** (terminal, claim closes `DENIED` with claimant-visible
   remarks) or **advance to verification**.
2. **Verification.** A first-class record (not a checkbox): type `DIGITAL` or
   `PHYSICAL`; status `PENDING → IN_PROGRESS → COMPLETE` (`CANCELLED` if superseded);
   outcome `PASSED | FAILED | WAIVED | INCONCLUSIVE`; free-text notes;
   evidence/document references (links to claim attachments or cited refs);
   performed-by user; started/completed timestamps. Entering the stage opens a
   verification record; the claim cannot advance until the latest record is `COMPLETE`
   with an outcome + notes (`WAIVED` requires a rationale — the legitimate path for
   small straightforward claims). No vendor/field integration in V2.
3. **Decision (final).** Assessment first (per-cover assessed amounts), then cover-level
   decisions (Flow V2-4). A handler may send the claim back from decision to
   verification (re-open, audited) when new doubts arise.
4. **Send-back to claimant (`NEED_INFO`).** At Review or Verification — never at
   Decision — the handler may send the claim back to the claimant with requested
   items (missing document, unclear loss detail). The claim leaves the assignee's
   active bucket as `NEED_INFO`, the claimant is notified (email + tracker flag),
   and their response returns the claim to the **same stage it left** with full
   history preserved. The product SLA clock **pauses** while `NEED_INFO` and
   resumes on response — the adjuster is never penalised for the claimant's delay.

Acceptance criteria:
- [ ] Review shows documents, description, amounts, policy + selected covers; reject closes with rationale, advance opens a verification record.
- [ ] A verification record carries type, status, outcome, notes, evidence refs, performer, and timestamps; completion without outcome + notes is rejected.
- [ ] The claim cannot reach decision while its latest verification is incomplete.
- [ ] Every stage transition (including decision→verification re-open) writes an audit row with actor, timestamp, and rationale/notes.
- [ ] Supervisor reassignment mid-verification preserves the stage and the verification history; the new handler sees everything.
- [ ] Review or Verification can send the claim back as `NEED_INFO` with requested items; the claimant's response returns it to the same stage, history intact; send-back from Decision is rejected (the decision→verification re-open covers handler doubts).
- [ ] The SLA clock pauses while `NEED_INFO` and resumes on claimant response; breach is evaluated on handling time only.

### Flow V2-4 — Cover-level assessment, decision, authority gate, escalation

Money first (normative vocabulary — the full model lives in the plan):
`sum_insured` (policy-period cap) ⊇ `cover sub-limit` (per-cover cap) ⊇ per-cover
`claimed_amount` (what the claimant asks) → `assessed_amount` (what the adjuster
verifies as admissible) → `approved_amount` (what is granted) → minus
`deductible_amount` ± `adjustment_amount` → **`net_payable`** (what leaves the door).
Claim totals are the sums of their covers. Enforcement order per cover: approved ≤
assessed ≤ claimed; approved ≤ sub-limit; aggregate net payable ≤ remaining
sum insured. The adjuster's **authority is checked against the amount they are
attempting to approve** under the product's configured `authority_basis`
(`APPROVED_TOTAL` or `NET_PAYABLE_TOTAL`; default `APPROVED_TOTAL`), compared to that
level's limit for the claim's product.

The decider records per-cover outcomes (`APPROVED` with amounts, or `REJECTED` with
remarks) plus a claim-level rationale. Gate outcome:
- Aggregate within the actor's limit → apply, close the claim. All covers approved ⇒
  claim `APPROVED`; all rejected ⇒ `DENIED`; mixed ⇒ **`PARTIALLY_APPROVED`** with
  `approved_total`/`net_payable_total` computed from the approved covers. Single
  payment row recorded (V1 atomicity preserved).
- Aggregate above the actor's limit → **nothing is closed**: the per-cover proposals
  are saved as proposals, the claim escalates to the **lowest level with sufficient
  authority** (skip-level allowed, L1→L3 directly), assigned to the least-loaded
  eligible adjuster there, with the full trail (prior stages, verification, proposals)
  visible. The new handler may accept or modify each proposal; no one can approve
  their own escalation (structural, as V1).
- Pure rejection (no approved amount) is never authority-gated.

Acceptance criteria:
- [ ] The worked example closes correctly: Hospitalization APPROVED ₹2,00,000 +
      Room Rent APPROVED ₹50,000 + OPD REJECTED ⇒ claim `PARTIALLY_APPROVED`,
      approved total ₹2,50,000, net payable after a deductible computed per cover.
- [ ] **Assessment/approval respect the limit chain:** an assessed or approved figure is
      capped within the remaining cover sub-limit and the remaining policy sum insured;
      over-limit proposals are rejected with a field-level error and the claim stays open.
      (Filing above a limit is legal — see Flow V2-1; deciding above a limit is not.)
- [ ] An above-authority approval attempt escalates (never closes), preserves the
      per-cover proposals, and routes to the lowest level whose limit covers the
      aggregate; the escalated handler sees the full prior trail.
- [ ] An escalated handler can modify any proposal and re-decide; the original
      actor cannot touch the claim after escalation (404).
- [ ] Denying all covers closes the claim as `DENIED` at any deciding level with a rationale.

### Flow V2-5 — Configurable SLA/aging, dashboards, claimant tracker

Per product: `sla_warning_days`, `sla_breach_days`, `sla_breach_action`
(`ESCALATE_NEXT_LEVEL | ESCALATE_SUPERVISOR | NOTIFY_ONLY`), with global defaults.
Warning = the claim is flagged (dashboards, queue badge, metrics); no movement.
Breach = the configured action runs as a system action: escalation moves the claim one
rung up (L1→L2→L3→supervisor) **preserving its workflow stage**, reassigned to the
least-loaded eligible adjuster at the new rung (supervisor bucket if none). The
scheduled job is idempotent and clock-injectable (V1 discipline). Dashboards:
- **Adjuster workload dashboard:** my bucket by stage, SLA warnings/breaches, my
  today's decisions, my load vs team median.
- **Supervisor dashboard:** team queue by stage/level/product, escalations by target,
  unassigned-with-reason, SLA exposure, decisions by outcome, outbox health (extends
  the V1 overview + R3 metrics).
- **Claimant tracker:** stage steps (Filed → Under review → Verification → Decision),
  per-cover outcomes on closure, a plain "taking longer than usual" flag past breach —
  still never reserves, assessed figures, notes, or handler identity beyond a contact.

Acceptance criteria:
- [ ] Warning flags a claim without moving it; breach executes the product's configured action exactly once per rung.
- [ ] Time spent in `NEED_INFO` does not count toward warning/breach thresholds (clock pauses on send-back, resumes on response).
- [ ] A breached claim keeps its workflow stage and history after escalation.
- [ ] Adjuster dashboard shows bucket-by-stage + SLA flags; supervisor dashboard shows team-by-stage/level/product + unassigned reasons + escalations.
- [ ] Claimant tracker shows stage, per-cover outcomes, aggregate (`PARTIALLY_APPROVED` with totals), and a delay flag — and no internal field in screen or response.

### Flow V2-6 — Supervisor ops + admin

Supervisor: decide any escalated claim at cover level (ungated, rationale required);
reassign any open claim mid-stage (assignee changes, stage and history preserved);
edit per-product authority (`route_level`, L1/L2/**L3** limits, `authority_basis`),
SLA thresholds/action, and the adjuster↔product skill matrix; policy admin extended to
covers (create + cover-aware CSV import with per-row results, retire). All admin edits
take effect immediately on subsequent gate/assignment/SLA evaluations and are audited.

Acceptance criteria:
- [ ] Supervisor cover-level approval/denial closes an escalated claim with rationale; payment recorded atomically.
- [ ] Mid-stage reassignment preserves stage, covers, verification history (records,
      evidence refs, timestamps, performer, audit rows), and proposals — the new
      handler continues from the current state with full history visible; verification
      is never reset by reassignment.
- [ ] Editing a limit/basis/SLA/skill row changes the next evaluation (no cache, no restart) and writes an audit row.
- [ ] Cover-aware policy import reports per-row ok/error; unknown product code names the valid codes (R1 discipline).

## Data (new/changed entities)

| Entity | Key fields | Notes |
|---|---|---|
| Product | product_code, family (HEALTH/NON_HEALTH/PROPERTY), display name, description | New catalog; V1 `HOME`/`AUTO` rows preserved as-is |
| PolicyCover | policy FK, cover_code, display name, sub_limit, deductible_default | The ~5 opted covers per policy |
| Policy enrichment | sum_insured, rating_params (jsonb), clauses (covered/excluded/scope jsonb), EXPIRED status | Structured; replaces opaque coverage blob for new policies |
| ClaimCover | claim FK, cover_code, claimed/assessed/approved/deductible/adjustment/net_payable, decision, remarks, decided_by/at | One row per filed cover |
| Verification | claim FK, type, status, outcome, notes, evidence_refs, performed_by, started/completed at | History preserved (multiple rows per claim allowed) |
| AdjusterSkill | adjuster FK, product_code | Eligibility matrix |
| Authority/SLA config | per product: route_level, l1/l2/**l3** limits, authority_basis, warning/breach days, breach action | Supervisor-editable |
| Claim (extended) | stage status, level, financial totals, aggregate decision (`APPROVED/DENIED/PARTIALLY_APPROVED`), unassigned reason | V1 columns untouched |

Must survive a restart: everything above plus all V1 state. Sensitive: reserve,
assessed amounts, internal notes, verification notes/performer, decision proposals —
all behind the visibility wall until a cover is finally approved/rejected (only
outcomes + approved amounts + remarks become claimant-visible).

## Dummy-data matrix (seeded, resettable demo set; reference rows via migration, scenario claims via demo seed)

Products (INR): HLTH-BASIC / HLTH-PLUS / HLTH-CRIT (health), AUTO-STD / AUTO-COM
(non-health), PROP-HOME / PROP-FIRE (property), HLTH-ORPHAN (no mapped adjuster).
Entry levels and limits rise with product severity (exact figures in plan).

Customers/policies (~12): keep V1 `POL-10001` (Ada, enriched to HLTH-PLUS cover set)
and `POL-20002` (Grace, AUTO, untouched for V1 E2E); add motor, home, critical-illness,
family policies; one RETIRED, one EXPIRED, one low-sum-insured (exhaustion demo), one
ORPHAN-product policy.

Staff: keep V1 adjusters (Priya/Marcus L1, Ines L2) + add Aisha (L1, health),
Rahul (L2), Meera (**L3**, catch-all senior); supervisor as V1. Skill matrix overlaps
on HLTH-BASIC (tie-break demo) and leaves HLTH-ORPHAN empty.

Scenario claims S1–S10 (each maps to edge cases §Edge-case matrix): partial approval,
sub-limit breach, exhaustion, L2→L3 authority escalation, orphan-unassigned, tie-break
pair, SLA breach, duplicate attempt, retired/expired filing, mid-verification
reassignment.

## Edge-case matrix

| # | Edge | Seeded as | Expected behavior |
|---|---|---|---|
| E1 | Multi-cover partial | S1: HOSP approved, ROOM approved, OPD rejected | Claim `PARTIALLY_APPROVED`, totals from approved covers |
| E2 | Cover sub-limit exceeded | S2: OPD claimed 40k vs 30k limit | Field error at FNOL; assessment cannot exceed sub-limit |
| E3 | Sum-insured exhaustion | S3: 10k remaining, 50k assessed | Decision rejected with remaining-benefit error |
| E4 | Authority escalation | S4: 9L assessed, L2 limit 8L | Proposals saved → `ESCALATED_L3` → L3 sees trail |
| E5 | No mapped adjuster | S5: ORPHAN product | `UNASSIGNED/NO_ELIGIBLE_ADJUSTER` + supervisor alert |
| E6 | Workload tie-break | S6: two HLTH-BASIC filings | Least-loaded wins; tie → lowest staff id |
| E7 | SLA escalation | S7: 6-day-old open claim | Breach action runs once, stage preserved |
| E8 | Duplicate FNOL | S8: repeat of S1 cover set < 24h | 409 + existing claim number |
| E9 | Retired/expired policy | File on RETIRED + EXPIRED rows | 400 mismatch shape, no existence signal |
| E10 | Reassign mid-verification | S10 reassigned by supervisor | Stage + verification history intact |
| E11 | Self-approval attempt | Actor re-decides post-escalation | 404 (no longer assigned) |
| E12 | Concurrent decisions | Locked row + policy lock | Second sees decided state; single payment preserved |

## Accounts and access (summary; full matrix in plan)

Claimant sees own policies/claims (claimant-view DTOs, extended with covers/outcomes —
wall extended to assessed/reserve/notes/verifier/proposals). Adjuster sees only
assigned claims, acts only within stage + authority. Supervisor sees team, decides
escalations, reassigns, administers. Unauthorized → 404 (V1 rule).

## Non-goals (V2 explicitly not building)

- **Payment orchestration** (rails, retries, reconciliation) — V3. V2 records the
  payable fact exactly like V1 records the payment fact.
- Automated adjudication, fraud scoring, document reading — every figure approved by a person.
- External integrations (policy-admin, field-verification vendors, payment gateways).
- Appeals/reopening after closure (still one-way; noted for V3).
- Multi-tenancy, SMS, mobile app, websockets, i18n/offline (all V1 non-goals, unchanged).
- Per-adjuster aggregate exposure caps (limits stay per-decision amounts).

## Constraints / Reach / Scale

Same stack and compliance as V1 (Spring Boot, Angular, Postgres, Playwright, Keycloak;
audit immutability; rationale required). Realm gains `ADJUSTER_L3` (+ provisioned L3
user). Currency for seeds and examples: **INR (₹)**. Scale: tens of adjusters,
thousands of claims — queue/SLA queries stay indexed; no per-row polling from clients.

## Definition of done for V2

- [ ] Dummy-data matrix seeded and visible: cockpit shows policies/covers/clauses.
- [ ] Multi-cover FNOL → claim number with stored cover selections; duplicate/retired/sub-limit guards hold.
- [ ] Skill-based assignment with tie-break + orphan parking demonstrable.
- [ ] Review → Verification (full record) → Decision operable end to end with audit at each step.
- [ ] Cover-level decisions with `PARTIALLY_APPROVED` + deductible/net-payable math green.
- [ ] L1/L2/L3 + supervisor gate enforced on the configured basis with escalation trail.
- [ ] Configurable SLA warning/breach behaving per product; both dashboards live.
- [ ] Supervisor reassign mid-stage; admin edits effective immediately.
- [ ] Wall, 404s, audit, outbox, and all V1 regression gates still green.

## Open questions

| Question | Blocking? | Proposed answer |
|---|---|---|
| Is verification skippable? | No | No — every claim passes the stage; trivial claims use outcome `WAIVED` + rationale. |
| Can claimants add documents after FNOL? | No | Yes while the claim is open (single endpoint, audited); adjuster sees them. |
| EXPIRED as a third policy status? | No | Yes; rejected at FNOL exactly like RETIRED. |
| Authority default basis? | No | `APPROVED_TOTAL` (conservative); per-product switch to `NET_PAYABLE_TOTAL`. |
| Reserve in V2? | No | Keep claim-level internal reserve (estimate, ungated, walled) — continuity for adjusters. |
