# Screen patterns

Whole-screen structures for record-lifecycle products. These are domain-neutral: the same shapes serve tickets, orders, loans, claims, cases, applications, and shipments. §10 maps the vocabulary onto specific industries.

## Contents
1. Data formatting rules
2. Work queue
3. Record detail
4. Multi-step intake
5. Money, limits, and approvals
6. Documents
7. Notes and audit trail
8. Risk and compliance signals
9. Roles and permissions
10. Domain vocabulary map

---

## 1. Data formatting rules

Formatting inconsistency is the single most common reason internal tools look amateur. Centralize these as formatter functions; never format inline in a component.

| Data | Rule | Example |
|---|---|---|
| Record identifier | Monospace, never wrapped, always the link to the record | `REC-2026-08814` |
| Secondary identifier | Monospace, grouped the way the source system groups it | `ACC 4471 8820 03` |
| Currency | Right aligned, tabular numerals, locale grouping, symbol always, decimals consistent within a column | `$42,000.00` |
| Large money in summaries | Full value, not abbreviated — "$1.2M" is unacceptable on a figure someone reconciles | `$1,204,500` |
| Dates | Unambiguous. Never `03/04/26` | `03 Apr 2026` |
| Date + time | Include timezone on legally or financially consequential events | `03 Apr 2026, 14:32 IST` |
| Relative time | Secondary only, next to or replacing absolute on recent items | `2 hours ago` |
| Age / duration | Integer + unit, with color only past a threshold | `14 days` |
| Percentages | One decimal, consistent | `82.5%` |
| Phone | Grouped with country code | `+91 98200 12345` |
| Names | `Surname, Given` in sortable lists; natural order elsewhere. Pick one per surface | |
| Empty values | An em dash in `--text-disabled`, never blank and never "N/A" everywhere | `—` |
| Masked PII | Last 4 visible, reveal is an explicit logged action | `•••• 4417` |

Show original currency alongside converted amounts for cross-border records. Never round a monetary figure for display.

## 2. Work queue

The screen an operator opens every morning and lives in. Optimize for triage speed.

**Saved views as tabs:** My queue · Unassigned · Awaiting input · Breaching SLA · Recently closed. "Breaching SLA" is usually the most valuable view in the product, and saved views generally are the feature that most makes an internal tool feel like real software.

**Columns, in order:** checkbox · identifier (mono) · primary party · type · status badge · value (right, tabular) · owner (avatar + surname) · created or event date · age · SLA · `⋯`

**Aging and SLA.** Compute an urgency signal and surface it in a dedicated column, not by coloring the entire row — full-row tinting makes long lists unreadable and breaks when two conditions collide. Thresholds: on track (neutral), due within 24h (warning), breached (danger). Sort by SLA by default in a queue view.

**Triage layout.** List/detail split with a preview pane at ~480px on the right, `j`/`k` to move, Enter to open full, `a` to assign. The preview shows the summary strip, the latest three activity entries, and anything pending review — enough to decide without a page load.

**Bulk actions:** assign, reassign, change status, request information, export. Report the result per item ("11 of 12 records reassigned. 1 failed — REC-2026-08790 is locked by another user.") rather than a generic success toast; partial failure is the normal case in back-office work.

## 3. Record detail

**Summary strip** directly under the page header — the facts a supervisor asks about first, one row, label above value, 12px label / 15px value:

`Status · Type · Key date · Value · Amount settled · Owner · Age · SLA`

Keep it sticky when the page scrolls. This is where the "four stat cards" instinct belongs, and it is much better here: dense, real, and above the fold.

**Tabs:** Overview · Details · Parties · Documents · Payments · Notes · Activity. Tab state in the URL. Counts on tabs holding collections (`Documents 7`).

**Overview layout:** two columns — a main column (description, structured detail, related records) and a 320px right rail (assignment, SLA, next action, quick links). The rail answers "who owns this and what happens next" in one glance.

**Locking.** If two people can open the same record, show "R. Iyer is editing" with the avatar, and either soft-warn or lock. Silent last-write-wins is a data-integrity bug that surfaces as a UI failure.

**Header actions:** the primary action is the next lifecycle step (`Approve`, `Send for review`), and it changes with status. Secondary actions are `Assign` and `Add note`. Everything else goes in `⋯`. Do not show actions the current role cannot perform — disable with a tooltip explaining why, or hide entirely if naming them leaks information.

## 4. Multi-step intake

Intake is often done under time pressure, sometimes while on a phone call. Design for interruption.

- **Stepped form with a visible stepper:** Lookup → Core details → Parties → Amounts → Documents → Review. Show completed / current / remaining, and allow jumping back to any completed step.
- **Lookup first.** Once the parent record (account, policy, customer, contract) is matched, prefill everything it already knows and show it as read-only context. Re-keying known data is the top complaint about intake tools.
- **Autosave every step**, with a visible "Draft saved 14:32" and recovery on reload. Losing twenty minutes of entry is how a tool loses its users.
- **Validate against the parent in-flight.** If a date falls outside the contract period or an amount exceeds an entitlement, say so at that field immediately, not on submit at step six.
- **Review step** shows everything entered, grouped, with per-section Edit links, then a single submit that returns the new identifier prominently and offers to open the record.

## 5. Money, limits, and approvals

- Entitlement table: line item · limit · deductible or allowance · used · remaining, all currency right aligned, with a totals row distinguished by a top border and 500 weight rather than bold alone.
- Changing a committed financial figure is an event, not a form edit: show the current value, require a reason code and a free-text justification, and write before → after to the audit trail. Display the change history as a small table beneath the current value.
- Show the running relationship plainly: `Committed $42,000 · Paid $10,540 · Outstanding $31,460`. Warn when a total exceeds an entitlement rather than silently allowing it.
- **Approval chains are visible:** who requested, who approved, what the thresholds are, and who the pending approver is by name.
- Never enable an action the role cannot perform. Above a user's authority, show "Requires supervisor approval above $20,000" next to the disabled control.
- Voiding or reversing a payment is a destructive confirmation with typed confirmation and a mandatory reason.

## 6. Documents

- Table view by default: name · type · uploaded by · date · size · status (Pending review / Accepted / Rejected). A thumbnail grid is optional and secondary — operators search by name and type, not by looking at pictures.
- **Inline preview in a drawer** with page navigation, zoom, rotate, and download. Opening a new browser tab for every PDF is a workflow tax.
- A required-document checklist for the record type, showing what is still missing. This converts directly into fewer follow-up calls.
- Bulk download as a zip, and a clear "Request documents" action that generates the outbound correspondence.

## 7. Notes and audit trail

Two related but distinct surfaces — do not merge them.

- **Notes** are human, editable within a short window, and may be internal or externally visible. Mark visibility explicitly with a badge on every note; ambiguity here creates real legal exposure. Support @mentions and pinning.
- **Audit** is immutable, system-generated, complete, and filterable by actor, date, and event type. Field changes render as `Value: $30,000 → $42,000`. Include an export for auditors and regulators.

Render both using the timeline spec in `component-specs.md` §10: actor in a left rail with a connecting line, plain-language action, absolute timestamp right-aligned, grouped under sticky date headers. System events, human actions, and notes are visually distinct. Nothing in an audit trail is editable, and it should visibly look that way.

## 8. Risk and compliance signals

- Risk flags appear as a discreet marker in the summary strip linking to detail, using the `special` status tokens — not as a red alarm banner. Overstating a probabilistic signal changes operator behaviour badly.
- If a score drives a decision, show the contributing factors. An unexplained number in a regulated workflow is a compliance problem, not just a UX one.
- Restricted records (employee, VIP, litigated, embargoed) show an access banner and log every view.

## 9. Roles and permissions

Design one screen for three readers rather than building three screens:

- **Operator** — depth on one record, speed to the next action, keyboard flow.
- **Supervisor** — queue health across a team, SLA breaches, approval requests, reassignment. Needs aggregate views and bulk actions.
- **Auditor / read-only** — needs the audit trail, document provenance, and export; no mutation affordances at all.

Where views diverge, change what is present, not where things are. Two roles should recognize the same layout.

## 10. Domain vocabulary map

The patterns above are the same across industries; only the nouns change. Substitute your own and the structures hold.

| Generic | Insurance claims | Support / ITSM | Lending | Commerce | Healthcare |
|---|---|---|---|---|---|
| Record | Claim | Ticket | Application | Order | Case / encounter |
| Parent record | Policy | Account / asset | Product + borrower | Customer | Patient chart |
| Intake | FNOL | Ticket creation | Application submission | Order placement | Registration |
| Primary party | Claimant | Requester | Applicant | Customer | Patient |
| Owner | Adjuster | Assigned agent | Underwriter | Fulfilment owner | Care coordinator |
| Committed amount | Reserve | — | Approved amount | Order total | Authorized cost |
| Entitlement | Coverage limit | SLA tier / entitlement | Credit limit | Credit / allowance | Benefit coverage |
| Terminal states | Settled / Denied | Resolved / Closed | Funded / Declined | Delivered / Cancelled | Discharged / Closed |
| Investigation | SIU / fraud review | Escalation | Verification | Chargeback / fraud | Utilization review |

Adopt whatever the operators actually say out loud. If the team says "file" and the database says "case_record", the UI says file. One word per concept, product-wide.
