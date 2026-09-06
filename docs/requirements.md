# Requirements — Claims Processing System

Status: Approved
Last updated: 2026-09-03

## One-line summary

A small claims-handling system for a personal auto book (single carrier) that carries a
claim from first notice of loss (FNOL) to closure, enforcing an authority gate on
payments, a hard visibility boundary between carrier and claimant, and a claimant
self-service status screen.

## Users

| Role | What they're trying to do | Can they self-register? |
|---|---|---|
| Claimant | Report a loss with evidence, track where their claim is in the process, see the decision. | Yes (via Keycloak) |
| Adjuster (L1 / L2) | Work their assigned caseload: verify coverage, set reserve, write internal notes, decide, approve payments within their level. | No — provisioned |
| Supervisor | See the team's claims, approve escalations, manage aging, reassign, configure authority tables, provision internal users. | No — provisioned |

## Core flows

### Flow 1 — FNOL (claimant reports a loss)

The claimant signs in (Keycloak), identifies their policy (which carries a product code),
describes what happened / when / where, attaches photos as evidence, and adds remarks.
On submit the system immediately returns a **claim number** and a plain statement of what
happens next, and sends the same by email. The claim is classified L1 or L2 by looking up
the policy's product code in the configurable authority table (parameters: estimated
amount and complexity), then lands in the unassigned queue.

Acceptance criteria:
- [ ] Submitting a valid FNOL returns a claim number immediately and sends the FNOL email.
- [ ] Photos and remarks submitted by the claimant are stored against the new claim.
- [ ] The claim is classified L1 or L2 from the policy's product code via the config table.
- [ ] An FNOL missing required loss details is rejected and stays on the form with an error.

### Flow 2 — Assignment and claimant status

An unassigned claim is routed to an adjuster of the matching level (L2 claim → L2
adjuster, L1 claim → L1 adjuster); within that level it goes to the adjuster with the
fewest open claims (never random). The claim moves to "under review"; the claimant's
process-steps screen updates and an assignment email is sent with who to contact.

Acceptance criteria:
- [ ] A new L1 claim is assigned to an L1 adjuster with the fewest open claims.
- [ ] A new L2 claim is assigned to an L2 adjuster with the fewest open claims.
- [ ] Assignment sends the claimant the assignment email and updates the process-steps screen.

### Flow 3 — Adjuster works the claim

The adjuster opens the claim, verifies coverage against the seeded (read-only) policy,
reads the description and photos, sets a reserve, and writes internal notes as they go.
Reserve and internal notes are never visible to the claimant.

Acceptance criteria:
- [ ] The adjuster can set/update a reserve and add internal notes on an assigned claim.
- [ ] Reserve and internal notes do not appear in any claimant-facing response or screen.

### Flow 4 — Decision and the authority gate

The adjuster reaches an indemnity figure. If it is within their level's limit they
approve it and the claim moves toward payment. If it is above their level, the system
**escalates rather than allowing approval**, routing directly to the level that has
authority (an L2-sized amount goes straight to L2; an L1-sized amount to L1). An adjuster
cannot approve their own escalation. The supervisor (highest authority) sees the file,
reserve, notes, and proposed figure, and decides. On denial, the claimant sees a
rejection with remarks.

Acceptance criteria:
- [ ] A payment within the adjuster's level limit is approved and recorded against the claim.
- [ ] A payment above the adjuster's level limit is blocked with an escalation, never approved.
- [ ] An adjuster attempting to approve their own escalation is rejected.
- [ ] Escalation routes to the lowest level with sufficient authority, skipping intermediate levels.
- [ ] A supervisor can approve or deny an escalated claim, with a recorded rationale.

### Flow 5 — Closure and notification

A single payment is recorded (a recorded fact, not a money movement) and the claim
closes. The claimant sees the decision — approved amount, or denial with remarks — and
receives the decision email. They never see the reserve or internal notes.

Acceptance criteria:
- [ ] Recording the payment closes the claim (one payment per claim; no partial payments).
- [ ] The claimant's screen shows the approved amount on approval, or rejection + remarks on denial.
- [ ] Reserve and internal notes remain absent from the closed claim's claimant view.
- [ ] The decision email is sent on closure.

### Flow 6 — Aging escalation

A claim not decided within the service commitment escalates up the ladder and this is
visible to the claimant on their process-steps screen.

Acceptance criteria:
- [ ] A claim undecided at 3 days (from FNOL) is pushed to L2.
- [ ] A claim undecided at 5 days (from FNOL) is pushed to the supervisor.
- [ ] The claimant's process-steps screen reflects the aging timeline.

## Data

| Entity | Key fields | Belongs to | Notes |
|---|---|---|---|
| Policy | policy number, product code, holder, coverage | carrier (seeded) | Read-only; one policy can have many claims |
| Claim | claim number, status, level (L1/L2), policy ref, loss description, loss date/location, assigned adjuster, reserve, indemnity figure, decision, decision remarks, timestamps | policy | One claim = one decision = one payment |
| Claimant identity | handled by Keycloak; app links claim → claimant | claimant | External identity |
| Internal note | text, author, timestamp | claim | Hidden from claimant |
| Photo / attachment | file, uploaded by claimant | claim | Evidence at FNOL; claimant-only |
| Payment | amount, authorized by, timestamp | claim | Recorded fact, not a transaction |
| Authority config | product code → parameters → L1/L2 thresholds and routing | carrier | Configurable table; edited by supervisor |
| Audit log | actor, timestamp, before/after, rationale | carrier | Immutable; every status change and decision |

Must survive a restart: claims, policies, photos, notes, reserves, decisions, payments,
authority config, and the audit log.
Sensitive / regulated: reserve and internal notes (hidden from claimants — hard boundary),
decision rationale (compliance evidence), audit log (immutable), claimant personal data.

## Accounts and access

- Login required: yes
- Method: Keycloak (OIDC), with roles claimant / adjuster / supervisor
- Can users see each other's data:
  - Claimant sees **only their own claims**, and only the claimant-facing fields (status,
    process steps, decision, remarks). Never reserve or internal notes.
  - Adjuster sees their assigned caseload.
  - Supervisor sees the team's claims, including reserve and internal notes.
- Admin role: supervisor performs admin functions — provision internal users, edit the
  authority config table, reassign claims.

## Non-goals

**The most important section here.** Everything on this list is code that doesn't get
written.

- Not building: **reopening / appeals after decision** — one-way flow to closure; noted for future.
- Not building: **multiple or partial payments per claim** — exactly one payment per claim.
- Not building: **policy administration, underwriting, or rating** — policies are seeded and read-only.
- Not building: **automated adjudication, fraud scoring, or document reading** — every decision is made by a person.
- Not building: **external system integrations** — no policy-admin integration; policies are seeded.
- Not building: **real money movement** — a payment is a recorded fact, not a transaction.
- Not building: **SMS notifications** — email only.
- Not building: **mobile app** — responsive web only.
- Not building: **real-time in-app updates (websockets)** — pull-to-refresh plus triggered email is enough.
- Not building: **multi-tenancy** — single carrier.
- Not building: **internationalization / offline support / SSO beyond Keycloak.**

Explicitly considered and excluded: multi-tenancy, roles beyond the three named,
notifications beyond email, real-time updates, file uploads beyond claimant evidence
photos, mobile app, i18n, offline.

## Constraints

- Stack we must use: Spring Boot (backend), Angular (frontend), PostgreSQL (database),
  Playwright (E2E tests), Keycloak (auth).
- Must integrate with: Keycloak only (no other external systems).
- Hosting: none specified.
- Deadline: none specified.
- Compliance: fair-claims-practices principles — every status change and decision is
  logged with actor and timestamp; decisions cannot be saved without a rationale;
  corrections are new log entries, never edits in place; 100% of decisions have a
  recorded actor and rationale.

## Reach

- Browsers: modern desktop (assumed); responsive for mobile.
- Mobile / responsive: yes — responsive layout, no separate mobile app.
- Accessibility target: WCAG 2.1 AA (assumed).

## Scale

- Expected users at launch: single-digit adjusters, 1–2 supervisors, claimants in the low hundreds per year.
- Expected users in a year: same order of magnitude.
- Largest realistic table size: audit log (grows continuously); claims/photos in the low hundreds to low thousands.
- Note: keep the implementation efficient (no N+1 queries, proper indexing) so it does
  not degrade if the book grows — without over-building for it now.

## Definition of done for v1

- [ ] Claimant can report a loss (policy, description, photos, remarks) and immediately receive a claim number.
- [ ] Claims are classified L1/L2 from product code via a configurable table, and assigned by level with load-balancing (fewest open claims).
- [ ] Adjuster can work an assigned claim: verify coverage, set reserve, add internal notes.
- [ ] The authority gate is enforced: within level → approve; above level → escalate (skip levels); no self-approval of escalations.
- [ ] Supervisor can see the team's claims, approve/deny escalations, reassign, and edit the authority config.
- [ ] Aging escalates a claim at 3 days (→ L2) and 5 days (→ supervisor), visible to the claimant.
- [ ] Closure records a single payment and shows the claimant the decision (amount, or denial + remarks).
- [ ] The visibility wall holds: reserve and internal notes never reach any claimant-facing surface.
- [ ] Immutable audit log captures every status change and decision with actor, timestamp, and rationale.
- [ ] Keycloak auth with the three roles; email on the three key events (FNOL, assignment, decision).

## Open questions

| Question | Blocking? | Assumed answer for now |
|---|---|---|
| Aging anchor: measured from FNOL, or per-status? | No | From FNOL (claim creation) |
| Do claimants self-register in Keycloak, or are they admin-provisioned? | No | Self-registration enabled |
| Who edits the authority config table — supervisor only? | No | Supervisor |
| Accessibility target | No | WCAG 2.1 AA |
| Photo size/type limits | No | Sane defaults (image types, capped size) |
| Exact product codes and threshold values (seed data) | No | Defer to plan/skeleton seed data |
