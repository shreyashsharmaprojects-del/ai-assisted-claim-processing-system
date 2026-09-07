# Plan V2 — Claims Processing System (rethink)

Status: Draft (for approval — no implementation until approved)
Last updated: 2026-09-07
Based on: `docs/requirements-v2.md` (Draft 2026-09-07); V1 `docs/plan.md` (Approved, still the build record)

> Why a second file: `docs/plan.md` + `docs/requirements.md` are the approved V1 build
> record and stay untouched. This file plans the V2 evolution. V1 guarantees
> (visibility wall, 404-not-403, immutable audit, atomic closure, email outbox) are
> constraints on every V2 slice, not history.

## Stack

Unchanged from V1 (constraint + boring wins): Angular SPA, Spring Boot, PostgreSQL +
Flyway, Keycloak (OIDC), Playwright. One addition: Keycloak realm gains role
`ADJUSTER_L3` and one provisioned L3 user. Currency for seeds/examples: INR (₹).

## Open forks (decided here, so slices don't re-argue them)

### Claim stage: new column vs status-enum sprawl
- **Option A** — reuse `claim.status` for stages (`IN_REVIEW`, …): one column, but
  conflates routing state with workflow position and breaks every V1 queue query.
- **Option B** — new orthogonal `claim.stage` (`REVIEW | VERIFICATION | DECISION`),
  `status` keeps its V1 routing meaning: V1 queries survive, stage guards are explicit.
- **Recommendation: B.** V1 statuses/queries stay green; stage is a second dimension.

### V1 decision endpoint: extend vs replace
- **Option A** — keep `POST …/decision` single-indemnity and add a parallel cover
  endpoint: two gates to maintain, divergent audit shapes.
- **Option B** — one atomic `POST …/decision` whose body carries per-cover outcomes;
  single-cover claims are the degenerate case (every V1 policy gets ≥1 default cover).
- **Recommendation: B.** One gate, one audit shape; V1 E2E journeys updated, not forked.

### Initial routing: lowest capable level vs entry-level + escalate
- **Option A** — route each claim to the lowest level that could approve its claimed
  total: fewer hops, but triage judgment happens before verification/assessment, and
  seniors drown in routine intake.
- **Option B** — route at the product's entry level; escalate on the assessed/approved
  figure when authority actually binds (user clarification 5: eligibility ≠ authority).
- **Recommendation: B.** Assessment happens at DECISION stage with evidence in hand;
  the gate fires on facts, not on the claimant's first estimate.

## Data model — V1 → V2 delta (all additive; V1–V11 migrations immutable)

```
# NEW TABLES
product                 (catalog; V1 codes HOME/AUTO preserved as rows)
  code PK, family enum(HEALTH|NON_HEALTH|PROPERTY), display_name, description

policy_cover            (the opted covers on a policy)
  id PK, policy_id FK → policy, cover_code, display_name,
  sub_limit NUMERIC(14,2), deductible_default NUMERIC(14,2) DEFAULT 0,
  sort_order INT — UNIQUE(policy_id, cover_code), INDEX(policy_id)

claim_cover             (one row per filed cover)
  id PK, claim_id FK → claim, cover_code,
  claimed_amount, assessed_amount NULL, approved_amount NULL,
  deductible_amount DEFAULT 0, adjustment_amount DEFAULT 0,
  net_payable (stored, server-computed), decision enum(PENDING|APPROVED|REJECTED),
  decision_remarks NULL, is_proposal BOOL DEFAULT FALSE,
  decided_by NULL, decided_at NULL — UNIQUE(claim_id, cover_code), INDEX(claim_id)

verification            (first-class stage record; history preserved)
  id PK, claim_id FK → claim, type enum(DIGITAL|PHYSICAL),
  status enum(PENDING|IN_PROGRESS|COMPLETE|CANCELLED),
  outcome enum(PASSED|FAILED|WAIVED|INCONCLUSIVE) NULL until COMPLETE,
  notes TEXT, evidence_refs jsonb (attachment ids / cited refs),
  performed_by (staff sub), started_at, completed_at NULL — INDEX(claim_id)

adjuster_skill          (eligibility matrix)
  adjuster_id FK → app_user, product_code FK → product — PK(adjuster_id, product_code)

# ALTERED TABLES (new migration(s) only)
authority_config
  + l3_limit_amount NUMERIC(14,2) (backfill; NOT NULL after)
  + authority_basis enum(APPROVED_TOTAL|NET_PAYABLE_TOTAL) DEFAULT APPROVED_TOTAL
  + sla_warning_days INT, sla_breach1_days INT, sla_breach1_action enum(ESCALATE_NEXT_LEVEL|ESCALATE_SUPERVISOR),
    sla_breach2_days INT NULL, sla_breach2_action (same enum) NULL
  Backfill HOME/AUTO: warning 2, breach1 3d→ESCALATE_NEXT_LEVEL, breach2 5d→ESCALATE_SUPERVISOR (V1 semantics preserved)

policy
  + sum_insured NUMERIC(14,2) (backfill from coverage json: POL-10001 500000, POL-20002 30000)
  + rating_params jsonb, clauses jsonb {covered[], excluded[], scope}
  + valid_from DATE NULL, valid_to DATE NULL; status check + 'EXPIRED'

app_user
  + level check gains 'L3'; + active BOOLEAN DEFAULT TRUE (assignment filters active)

claim
  + stage enum(REVIEW|VERIFICATION|DECISION) DEFAULT REVIEW (open claims)
  + claimed_total, assessed_total, approved_total (≡ legacy indemnity_amount, kept),
    net_payable_total NUMERIC(14,2) — all server-computed, never client-trusted
  + aggregate_decision enum(APPROVED|DENIED|PARTIALLY_APPROVED) NULL until closure
  + unassigned_reason enum(NO_ELIGIBLE_ADJUSTER|…) NULL
  + level check gains 'L3'
```

Constraints/indexes day one: the UNIQUEs above; `claim(stage)`, `claim(level)`,
`claim_cover(claim_id)`, `verification(claim_id)`, `adjuster_skill(product_code)`,
`policy_cover(policy_id)`; payment stays `UNIQUE(claim_id)` with
`payment.amount == claim.net_payable_total` (V1 `amount == indemnity` becomes the
single-cover degenerate case). Every stage/cover/SLA transition writes an audit row
(actor + timestamp + rationale); `audit_log` stays append-only.

## Claim state machine (normative)

```
UNASSIGNED --assign (eligible found)--> UNDER_REVIEW[REVIEW]
UNASSIGNED --(no eligible at any rung)--> UNASSIGNED + unassigned_reason (supervisor alert)
UNDER_REVIEW[REVIEW] --reject w/ rationale--> CLOSED{aggregate=DENIED}
UNDER_REVIEW[REVIEW] --advance--> UNDER_REVIEW[VERIFICATION] (opens verification PENDING)
UNDER_REVIEW[VERIFICATION] --complete (outcome+notes)--> UNDER_REVIEW[DECISION]
UNDER_REVIEW[DECISION] --send back w/ reason--> UNDER_REVIEW[VERIFICATION] (new verification row)
UNDER_REVIEW[REVIEW|VERIFICATION] --send back w/ requested items--> NEED_INFO (leaves assignee bucket; claimant notified; SLA clock pauses; never from DECISION)
NEED_INFO --claimant responds--> UNDER_REVIEW[prior stage] (stage + history preserved; SLA clock resumes)
UNDER_REVIEW[DECISION] --decide, within authority--> CLOSED{APPROVED|DENIED|PARTIALLY_APPROVED} + payment
UNDER_REVIEW[DECISION] --decide, above authority--> proposals saved; UNDER_REVIEW reassigned
    to lowest rung with authority (stage stays DECISION; full trail travels) — or
    ESCALATED_SUPERVISOR when no rung covers it
ESCALATED_SUPERVISOR --supervisor cover-decide--> CLOSED (ungated, rationale required)
Any UNDER_REVIEW --SLA breach--> rung up, STAGE PRESERVED (reassign; supervisor if top)
CLOSED terminal. No transition without an audit row. No actor touches a claim after it
leaves their hands (404).
```

## Cover-level state/decision model (normative)

Per cover: `FILED` (claimed set at FNOL) → `ASSESSED` (assessed set at DECISION stage,
`0 ≤ assessed ≤ claimed`) → `DECIDED` (`APPROVED` with `approved ≤ assessed`,
`approved ≤ sub-limit`; or `REJECTED` with remarks). Above-authority attempts freeze
covers as `PROPOSED` (`is_proposal=TRUE`) instead of deciding them. Claim aggregate:
all approved → `APPROVED`; all rejected → `DENIED`; mixed → `PARTIALLY_APPROVED`.
Claimant sees only per-cover outcomes + approved amounts + remarks (wall §RBAC).

## Financial model (normative vocabulary)

Four locked rules (approved 2026-09-07 — do not re-argue in slices):

1. **Limits constrain; they do not block filing.** The chain
   `sum_insured ⊇ cover sub-limit ⊇ claimed → assessed → approved → net_payable`
   binds *assessment and approval*, never FNOL. A claimant may claim above a cover
   sub-limit; the filing is accepted (flagged above-limit for review) and the adjuster
   assesses/approves within the applicable limits. FNOL rejects only missing/invalid
   data, duplicates, or retired/expired policies.
2. **Remaining limits are tracked explicitly:** remaining policy sum insured
   (`sum_insured − Σ prior non-rejected net payables on the policy`) and remaining
   per-cover sub-limit (same, scoped to the cover). Exhaustion of one cover never
   touches unrelated covers.
3. **Mid-verification reassignment never resets verification.** Records, evidence refs,
   timestamps, performer, and audit rows travel with the claim; the new handler
   continues from the current state with full history visible.
4. **Non-goals are hard:** no fraud automation, no auto-adjudication, no
   appeals/reopening, no payment rails, no external integrations, no
   mobile/multi-tenancy/SMS/websockets/i18n/offline, no per-adjuster aggregate caps.

Per cover: `claimed → assessed → approved → −deductible ±adjustment → net_payable`
(`net_payable = max(approved − deductible + adjustment, 0)`). Claim totals are Σ of
covers, server-computed. Enforcement order at decision time: `approved ≤ assessed ≤
claimed`; `approved ≤ remaining cover sub-limit`; `net_payable_total ≤ remaining sum
insured`. The gate compares the product's `authority_basis` (`APPROVED_TOTAL`
default, or `NET_PAYABLE_TOTAL`) against the actor's rung limit. V1 mapping:
`indemnity_amount ≡ approved_total`, `payment.amount ≡ net_payable_total`.
Payment orchestration stays V3.

## Assignment algorithm (normative)

```
assign(claim): rungs = [product.route_level, …, L3]
  for rung in rungs:
    cands = adjusters WHERE active AND skilled(product) AND level==rung
    if cands: pick min(open claims), tie → lowest app_user.id   # V1 rule preserved
      FOR UPDATE the candidate rows first (V1 concurrency discipline, scoped to cands)
      → UNDER_REVIEW[REVIEW]; assignment email + outbox; done
  park UNASSIGNED + reason NO_ELIGIBLE_ADJUSTER → supervisor attention list + ops alert
```
Open = `status <> 'CLOSED'` assigned to them. Eligibility (skill) and authority
(level) are independent axes by design (clarification 5).

## Authority/escalation algorithm (normative)

```
decide(actor, covers[], rationale): validate financial invariants first (field errors, stay open)
  basis = APPROVED_TOTAL or NET_PAYABLE_TOTAL per product config
  if all covers REJECTED → close DENIED (never gated)
  elif basis ≤ limits[actor.level] → apply, close (aggregate computed), payment = net_payable_total, atomically
  else target = lowest rung with limits[rung] ≥ basis, else SUPERVISOR
       → save covers as PROPOSALS, re-assign (least-loaded eligible at target rung;
          walk up if empty; supervisor if none), stage preserved, trail visible
       → original actor loses access (404); cannot self-approve (structural, as V1)
Supervisor on ESCALATED_*: ungated cover-level decide, rationale required, atomic close.
```

## RBAC matrix

| Capability | Claimant (own only) | L1/L2/L3 (assigned only) | Supervisor |
|---|---|---|---|
| My policies + policy detail (covers/clauses) | ✅ claimant-view | own-claim policy context | any |
| File multi-cover claim; add docs while open | ✅ | — | — |
| Tracker: stage, per-cover outcomes, delay flag | ✅ (no internals) | — | — |
| Review advance/reject; verification CRUD; assess; cover-decide | — | ✅ within stage; decide binds only within authority | ✅ escalated claims |
| Reassign mid-stage; admin (authority/SLA/skills/policy) | — | — | ✅ |
| Queue / workload dashboard | — | own bucket only | team |
| Non-assigned / others' claims; any admin | 404 | 404 | team-visible |
| Internal fields (reserve, assessed, notes, verifier, proposals) on claimant surfaces | never (DTOs omit) | — | — |

## Dummy-data matrix (migration reference rows + resettable demo scenario claims)

Products: `HLTH-BASIC`/`HLTH-PLUS`/`HLTH-CRIT` (HEALTH), `AUTO-STD`/`AUTO-COM`
(NON_HEALTH; AUTO-COM replaces AUTO for new seeds, AUTO row kept), `PROP-HOME`/
`PROP-FIRE` (PROPERTY), `HLTH-ORPHAN` (no skills). Limits rise with severity, e.g.
HLTH-BASIC L1 50k/L2 2L/L3 5L … HLTH-CRIT L1 2L/L2 8L/**L3 25L** (exact table in slice
V2-1). Entry levels: BASIC L1, PLUS L1, CRIT L2, AUTO L1/L2, PROP L1, ORPHAN L1.
SLA defaults: warning 2d, breach1 3d→next rung, breach2 5d→supervisor.

Customers/policies (~12): V1 `POL-10001` (Ada → enriched HLTH-PLUS 5-cover set) and
`POL-20002` (Grace AUTO single-cover, untouched for V1 E2E) stay; + motor/home/
critical-illness/family rows; one RETIRED, one EXPIRED, one low-sum-insured
(₹50k, exhaustion demo), one ORPHAN-product policy. Health policies carry ~5 covers
(HOSPITALIZATION, ROOM_RENT, DAYCARE, OPD, MATERNITY / CRITICAL_ILLNESS) with
sub-limits, deductibles, rating params (sum insured, room-rent cap, waiting periods,
zone) and clauses (covered/excluded/scope).

Staff: V1 Priya/Marcus (L1), Ines (L2) + Aisha (L1 health), Rahul (L2), Meera (**L3**,
catch-all). Skills overlap on HLTH-BASIC (tie-break demo); HLTH-ORPHAN empty.
Supervisor as V1 (still no app_user row).

Scenario claims S1–S10 (demo seed; each pins ≥1 edge E1–E12 in requirements-v2):
S1 partial approval · S2 sub-limit breach · S3 exhaustion · S4 L2→L3 escalation ·
S5 orphan-unassigned · S6 tie-break pair · S7 SLA breach · S8 duplicate target ·
S9 retired/expired filings · S10 mid-verification reassignment.

## API changes (auth rule per row; claimant DTOs structurally omit internal fields)

| Method | Path | Auth | Returns / notes |
|---|---|---|---|
| GET | `/api/policies/mine` | CLAIMANT | own policies + covers + remaining benefit |
| GET | `/api/policies/{n}` | CLAIMANT own / internal any-claim-context | full detail: covers, rating params, clauses |
| POST | `/api/claims` | CLAIMANT | **new body:** `covers[{cover_code, claimed_amount}]`; 409 + existing number on duplicate; sub-limit/retired/expired guards |
| POST | `/api/claims/{n}/documents` | CLAIMANT own, while open | attachment added, audited |
| POST | `/api/claims/{n}/review` | assignee | `{action: ADVANCE\|REJECT, rationale/notes}` |
| POST/PUT | `/api/claims/{n}/verifications[/{id}]` | assignee (+ supervisor reassigns) | full verification record in/out |
| PUT | `/api/claims/{n}/assessment` | assignee at DECISION | per-cover assessed amounts |
| POST | `/api/claims/{n}/decision` | assignee (**new cover-level body**) | approve/deny/propose per cover + rationale; atomic close or escalation |
| POST | `/api/claims/{n}/escalation-decision` | SUPERVISOR (**cover-level body**) | ungated close with rationale |
| POST | `/api/claims/{n}/reassign` | SUPERVISOR | stage + history preserved |
| GET/PUT | `/api/config/authority[/{code}]` | SUPERVISOR | extended: l3, basis, SLA fields |
| GET/PUT | `/api/config/skills` | SUPERVISOR | adjuster↔product matrix |
| GET | `/api/dashboard` | SUPERVISOR (extended) / adjuster workload view | stage/level/product buckets, SLA exposure, unassigned reasons |
| — | V1 queue/escalations/mine/outbox/metrics/audit | unchanged contracts | extended payloads only (stage, totals, SLA flags) |

`api.http` gains one example per new/changed endpoint in its slice.

## UI changes (enterprise-ui Tokens; claimant chrome vs internal shell preserved)

- **Claimant:** My-policies cockpit → policy detail (covers, sub-limits, rating,
  clauses, remaining benefit) → cover-picker FNOL (per-cover amount + docs) →
  tracker with stage steps + per-cover outcomes + delay flag. Simple, read-only calm.
- **Adjuster:** claim workspace with stage stepper (Review | Verification | Decision,
  later stages locked until guards pass); verification panel (type/status/outcome/
  notes/evidence/performer/timestamps); assessment grid; cover-decision grid with
  live totals + authority hint ("₹X against your ₹Y limit — will escalate to L3").
- **Supervisor:** team dashboard (stage × level × product, escalations, unassigned
  reasons, SLA exposure, outbox), escalated claim view with full trail + proposals,
  admin (authority+SLA+skills matrix, cover-aware policy import).
- Every list keeps loading/empty/filtered-zero/error/retry + pagination/load-more.

## Test strategy (testing-web.md, V1 discipline kept)

- **Unit (JUnit, no DB):** gate matrix (3 rungs × basis × skip-level), assignment
  picker incl. tie-break + walk-up + orphan, financial invariants (per-cover order,
  sub-limit, exhaustion, floor-zero), SLA policy (warning/breach1/breach2,
  idempotence), stage-machine guards (no DECISION before COMPLETE verification,
  no WAIVED without rationale), claimant-DTO wall (assessed/reserve/notes/verifier/
  proposals absent).
- **Integration (Testcontainers, per-endpoint matrix):** success / invalid / 401 /
  404-forbidden / not-found; cover-decision atomicity + single-payment; escalation
  preserves proposals + revokes actor; SLA job with injected clock; admin edits
  effective immediately; duplicate FNOL 409; retired/expired 400-shape.
- **E2E (Playwright, hermetic; V1 journeys updated, not forked):**
  1. cockpit → multi-cover file → number; 2. tracker shows partial + delay flag,
     internals absent; 3. orphan claim → supervisor attention; 4. review→verify→
     decide happy path; 5. incomplete verification blocks decision; 6. partial
     approval closes with payment = net payable; 7. over-authority → L3 sees
     proposals, modifies, closes; 8. supervisor mid-stage reassign preserves
     history; 9. SLA breach escalates once, stage preserved; 10. admin limit edit
     changes next gate.
- **CI:** push = unit + integration + frontend build; PR/main = E2E. Nothing red merges.

## Slices (each vertical: migration + backend + UI + tests; dummy-data first)

### V2-0 — Baseline + migration discipline
- Locks the V1 suite green as the regression floor; Flyway V12+ convention
  (V1–V11 immutable), realm L3 role, INR formatting rule.
- Tests: full V1 suite green on a clean checkout (record counts).

### V2-1 — Catalog, enrichment + dummy data, claimant cockpit (read-only)
- Satisfies: product taxonomy; enriched policies (covers, sub-limits, rating vs
  clauses, EXPIRED); skill matrix; staff L3; cockpit + policy detail screens.
- Tests: unit (seed referential integrity); integration (mine/detail auth + 404s,
  retired hidden-shape); E2E 1 (browse cockpit).
- Risk: cover CSV shape + remaining-benefit query; decide both here.
- **V1 regression impact (accepted, recorded 2026-09-07):** the 6-person staff
  changes V1's level-only load-balance expectations — the V1 tie-break test
  (`[l1One, l1Two, l1One]`) now observes the third L1 adjuster
  (`[l1One, l1Two, aisha]`), and the realm-sync test counts 6 staff. Dedicated
  single-cover V1 products (`V1-HOME`, `V1-AUTO`) are out of scope: V2-3 replaces
  level-only routing with the skill matrix, and these journeys are updated there.
  Until then the affected V1 tests assert the new deterministic outcomes.

### V2-2 — Multi-cover FNOL + tracker
- Satisfies: V2-1 flow (cover selection, claimed amounts unconstrained by sub-limit at
  filing [flagged above-limit for review], retired/expired/duplicate guards, stored
  selections, claim number, confirmation email, tracker, remaining-limit display).
- Tests: unit (duplicate key, sub-limit validation); integration (409 + number,
  400-shapes, wall on tracker); E2E 1–2.
- Risk: duplicate definition (policy + loss-date + cover-set + 24h) — pinned here.

### V2-3 — Skill-based assignment + orphan parking
- Satisfies: V2-2 flow (eligible-set routing, tie-break, walk-up, UNASSIGNED +
  reason + supervisor attention, atomicity).
- Tests: unit (picker matrix); integration (assignment outcomes, contention);
  E2E 3.
- Risk: FOR UPDATE scope on filtered candidate set; S6 tie-break determinism.

### V2-4 — Staged workflow: review + verification
- Satisfies: V2-3 flow (stage guards, reject-at-review, full verification record,
  send-back NEED_INFO at Review/Verification with SLA pause, re-open path, audit
  per transition).
- Tests: unit (stage guards, WAIVED-rationale); integration (verification CRUD
  auth, incomplete-blocks-decision); E2E 4–5.
- Risk: the "cannot advance" guards are the load-bearing logic — matrix them.

### V2-5 — Assessment + financial model + partial approval
- Satisfies: V2-4 flow (assessed/approved/deductible/adjustment/net-payable,
  enforcement order within remaining limits, PARTIALLY_APPROVED, payment = net
  payable, S1 worked example).
- Tests: unit (money math incl. floor-zero + exhaustion); integration (field
  errors leave claim open; single payment); E2E 6.
- Risk: rounding/scale on jsonb + NUMERIC(14,2); assert like V1 (no trailing-zero).

### V2-6 — L1/L2/L3 + supervisor ladder, configured basis, proposal escalation
- Satisfies: V2-4 gate (3-rung matrix, APPROVED_TOTAL vs NET_PAYABLE_TOTAL,
  skip-level, proposals travel, 404 after escalation, supervisor ungated close).
- Tests: unit (full gate matrix); integration (S4/S7-shaped escalations,
  self-approval 404); E2E 7.
- Risk: the product's core rule — the matrix is the safety net, as in V1 slice 4.

### V2-7 — Configurable SLA + dashboards
- Satisfies: V2-5 flow (warning vs breach, breach actions, idempotent clocked job,
  adjuster workload + supervisor team dashboards, tracker delay flag).
- Tests: unit (SLA policy incl. NEED_INFO pause); integration (job transitions, once-per-rung);
  E2E 9–10 (breach path).
- Risk: V1 3d/5d semantics must survive on HOME/AUTO backfill rows.

### V2-8 — Supervisor ops + admin
- Satisfies: V2-6 flow (escalated cover-decide, mid-stage reassign, authority/SLA/
  skill admin effective immediately, cover-aware import with per-row results).
- Tests: integration (admin auth + immediacy + audit; reassign preserves stage);
  E2E 8, 10.

### V2-9 — Hardening + regression
- V1 gates re-verified (wall extended to assessed/verifier/proposals, 404 matrix,
  audit immutability, outbox on new events, rate limits on new writes), E2E 1–10
  green, `api.http` + operations docs + demo script current.

Ordering note: data + cockpit first (demonstrable from V2-1); the two load-bearing
rules — **financial invariants** (V2-5) and **the gate** (V2-6) — land as pure
functions with exhaustive matrices before their endpoints, exactly like V1 slice 4.

## Out of scope for this plan

Payment orchestration/rails (V3); auto-adjudication/fraud/doc-reading; external
integrations; appeals/reopening; multi-tenancy; SMS; mobile app; websockets; i18n/
offline; per-adjuster aggregate caps. Cover-aware import format is decided in V2-1
and not revisited.
