# UI Doctrine — Claim Processing System

Single source of truth for the redesign. Every subagent brief points here.
When a decision changes, update this file — never carry corrections in chat alone.

## 1. What we are designing for

Operator tools win **trust and throughput**. An adjuster sits on this screen for
seven hours, closes forty claims, and gets audited on the result. Restraint beats
novelty. Spend creativity on data legibility and short workflows, not chrome.

Mature = Stripe Dashboard, Linear, Guidewire: flat, tight, hairline-bordered,
typographically disciplined. Never glassmorphism, gradients, large rounded cards,
animated entrances.

Three properties: **density** (many rows on screen), **consistency** (one of
everything, repeated without deviation), **meaning-bearing color** (a screen at
rest is ~95% neutral; green means *approved*, never decorative).

## 2. Project decisions (from code — do not re-litigate without asking)

- **Brand:** keep `--accent-600 #0056B3` corporate blue. One accent, primary
  actions + active nav only.
- **Locale/currency/timezone:** `en-GB` dates (`03 Apr 2026`), `INR` currency,
  tenant zone `Europe/London`. All formatting funnels through
  `frontend/src/app/format.ts` — use its functions, never inline `Intl` calls.
  Known defect: dates render in browser-local zone (no `timeZone` option);
  do not work around it per-screen — one central fix later.
- **SLA (ground truth):** backend ladder is authoritative — filing-anchored
  (`created_at`), 3-day L2 reassign / 5-day supervisor escalation. Display bands:
  <3 days "On track"/neutral, 3–4 "Due soon"/warning, 5+ "Breaching"/danger.
  Known defect: queue/escalations compute age from **loss date**
  (`queue.ts:56`, `escalations.ts:51`) while the backend counts from filing.
  Any SLA display work must use filing date. `NEED_INFO` does **not** pause the
  clock. `UNASSIGNED` is reachable (no eligible adjuster, staff deactivation).
- **Lifecycle (verbatim code tokens — never paraphrase, never invent):**
  Status: `UNASSIGNED` `UNDER_REVIEW` `NEED_INFO` `ESCALATED_SUPERVISOR` `CLOSED`.
  Stage (orthogonal): `REVIEW` `VERIFICATION` `DECISION`.
  Claim decision: `APPROVED` `DENIED` `PARTIALLY_APPROVED` (derived from covers).
  Cover decision: `PENDING` `APPROVED` `REJECTED`.
  Review actions: `ADVANCE` `REJECT` `NEED_INFO`.
  Verification: type `DIGITAL`/`PHYSICAL`/`DOCUMENT`/`CLAUSE`,
  status `PENDING`/`IN_PROGRESS`/`COMPLETE`/`CANCELLED`,
  outcome `PASSED`/`FAILED`/`WAIVED`/`INCONCLUSIVE`.
  Doc check: `PENDING`/`RECEIVED`/`WAIVED`. Policy: `ACTIVE`/`RETIRED`/`EXPIRED`.
  Levels: `L1`/`L2` (L3 provisioned, no routing path observed).
  Denial reasons (7): `NOT_COVERED` `EXCLUDED_PER_CLAUSE`
  `ABOVE_SUB_LIMIT_EXHAUSTED` `INSUFFICIENT_EVIDENCE` `DUPLICATE_PRE_EXISTING`
  `FRAUD_SUSPECTED_REFERRAL` `OTHER`.
  `IN_REVIEW / IN_VERIFICATION / PENDING_DECISION` appear only in docs — drift,
  never use. No Status enum exists; statuses are string literals.
- **Roles:** `claimant`, `adjuster_l1`, `adjuster_l2`, `adjuster_l3`,
  `supervisor`. Guards: claimant-only, internal (L1/L2/L3/supervisor),
  supervisor-only. Decision endpoints are adjuster-only (supervisor excluded).
  Adjusters see only assigned claims (404, never 403).
- **Vocabulary:** claimant/holder (never "customer"); payment = recorded fact,
  not money movement; internal-only fields (reserve, assessed, deductible,
  adjustment, verifier, proposals, notes) never enter claimant DTOs.
- **Claimant visibility (keep as-is):** on closure claimants see per-cover
  decisions + approved amounts + net payable; while open, nulls omitted.
  FNOL success + tracker copy is pinned by e2e — do not reword.
- **AI is advisory-only.** No decision path branches on AI output. Keep the
  disclaimer. Never gate an adjuster action on an advisory existing.
- **PII (keep as-is):** no masking exists; supervisors see cleartext. Do not
  invent a masking scheme per-screen — flag it, don't freelance it.
- **Retention:** 7-year closed-claim window, report-only; erasure is supervisor
  anonymize (nothing deleted). Do not touch erasure semantics.
- **Density target:** 36px rows (from 44px), 32px controls (from 38px), 48px
  topbar (from 56px), 640px form column (from 720px), panel radius 6px
  (from 12px), flat panels (no shadow on static elements). No density
  toggle/preference store exists — build one default, no per-user setting.
  Shell collapse state is in-memory only (resets on reload) for the same reason.
- **Shared helpers (post-shell-pass, in `ui.ts`):** `statusLabel()` (humanized
  sentence case for display; logic keeps raw tokens), `isEvidenceFile()`,
  `MAX_EVIDENCE_MB/BYTES` (=10), `currencySymbol()`, `downloadBlob()`.
  Screens adopt these in their passes; local copies get removed then.
  Toasts stay 6s autodismiss (doctrine said 5s; timing is behaviour-adjacent,
  left alone). Open shell follow-ups (no owner yet): j/k+Enter list/detail
  pattern + `?` shortcuts sheet, per-list CSV export, Stale indicator, modal/
  drawer size + focus-trap primitive.
- **Off-limits caution areas:** audit panel + audit export, privacy erasure form,
  FNOL/claimant-tracker/queue-money copy pinned by e2e (`fnol.spec.ts`,
  `queue.spec.ts`, `need-info-docs.spec.ts`). No "do not modify" comments exist;
  ordinary validation copy is fair game.

## 3. Tokens

One token file at `frontend/src/styles.css` (`:root`). Every color, space,
radius, and font size in shipped code resolves to a token. No one-off hex.

Structure (adapted to `#0056B3` brand, step counts fixed):
neutral ramp `--gray-0…900` · accent `--accent-50…800` (600 = primary actions,
700 = hover) · six status triads (fg/bg/br: neutral, info, success, warning,
danger, special, each fg AA on its own tint) · surfaces · text
(primary/secondary/tertiary/disabled/link/on-accent) · borders
(subtle/default/strong/focus) · radii `sm 2px / md 4px / lg 6px / full 999px`
· elevation (flat default; `raised` dropdowns; `overlay`+scrim modals/drawers)
· spacing `1/2/3/4/6/8` on the 4px grid — do not interpolate · type
(`11/12/13/14/15/18/20px`, weights 400/500/600 only, one sans + one mono)
· density (`--row-height 36px`, `--control-height 32px`)
· layout (topbar 48px, sidebar 240px / collapsed 56px, full bleed, forms 640px)
· motion (120/160/200ms).
Decided post-token-pass: `--control-height-sm 28px`, `--control-height-lg 36px`
(proportional scale-down, confirmed). The app shell adopts
`var(--topbar-height)`; the floating rounded topbar does not survive — shell is
flat hairline per §4.

Type scale: base 14px, tables 13px, labels/metadata 12px, section headings
15px semibold, page titles 20px max. Sentence case everywhere. Monospace only
for identifiers compared character by character (claim/policy/transaction IDs).
`.num` = right-aligned tabular numerals. Focus ring on everything; honor
`prefers-reduced-motion`.

Tailwind-style bans (enforced in vanilla CSS too): gradients, shadows on static
elements, radius >6px, `hover:scale`, entrance animation, raw palette steps,
more than one accent.

## 4. Layout architecture

48px top bar (product mark · global search ⌘K · icons · user) → 240px left nav
(collapsible to 56px rail, in-memory state only, resets on reload; grouped Work /
  Admin — revised post-shell-pass since the route table has no Records or Money
  destinations; revisit if routes are added; items 32px,
13px, 16px icon; active = tinted bg + 2px accent left border, never a pill) →
page header (breadcrumb · object title + identifier · 1–3 actions, primary
right-aligned, overflow behind `⋯`) → summary strip on detail pages (4–6 facts,
label above value, sticky) → tabs for object sections (state in URL) → working
area, full bleed, left aligned. Center only genuinely narrow content.
List/detail split for triage: list left, ~480px preview right, `j`/`k` + Enter.

## 5. Components

- **Buttons:** four variants, exactly one primary per view. Primary
  `--accent-600` fill, white text, no border. Secondary white + default border.
  Tertiary transparent for toolbar/row actions. Danger white + danger border/text
  (filled red only in the confirm dialog). 32px, `0 12px`, 4px radius, 13px/500.
  Loading keeps width. No gradients, shadows, uppercase, `→`, or full-width
  outside modals.
- **Inputs:** label (12px/500/secondary) above → control (32px, 1px border, 4px
  radius, 13px) → helper/error 12px. Persistent labels; placeholders show format
  only (`DD/MM/YYYY`). Validate on blur. Errors say what to do. Never disable
  submit for invalid state — submit, show errors, focus the first. Single column
  unless genuinely paired. Max 640px. Autosave long forms with "Draft saved HH:MM".
- **Tables:** header 32px, 11–12px/500/tertiary, sticky, `aria-sort`. Rows 36px,
  13px, 1px subtle dividers, no vertical gridlines, no zebra. Align by type:
  text left, numbers/currency right + tabular, dates left, badges left, actions
  right. Identifier first, monospace, linked, sticky. Ellipsis + `title`, never
  wrap. Row clickable; selected = tint + 2px accent left border. Row actions in
  `⋯` on hover *and* focus. Bulk selection swaps toolbar for a selection bar.
  Server pagination with accurate total ("1–50 of 3,271") — never infinite scroll.
- **Badges (central mapping — `ui.ts`, never per-screen):**
  neutral: `UNASSIGNED` `CLOSED` `RETIRED` `CANCELLED` `DRAFT` `ARCHIVED`
  · info: `UNDER_REVIEW` `IN_PROGRESS` `ASSIGNED` `SUBMITTED`
  · success: `APPROVED` `PARTIALLY_APPROVED` `ACTIVE` `SENT` `PAID` `SETTLED`
  `RECEIVED` `PASSED` `COMPLETE`
  · warning: `NEED_INFO` `PENDING` `ON_HOLD` `AWAITING_DOCUMENTS` `DUE_SOON`
  · danger: `DENIED` `REJECTED` `EXPIRED` `FAILED` `OVERDUE` `BREACHING`
  · special: `ESCALATED_SUPERVISOR` (any `ESCALATED*`), `INCONCLUSIVE`,
  `WAIVED`, investigation/litigation/subrogation if they ever appear.
  20px, 2px radius, 11px/500, 1px border, icon + text + color (never color alone).
  Display casing: humanized sentence case derived 1:1 from the verbatim token
  (`UNDER_REVIEW` → "Under review", `NEED_INFO` → "Need info",
  `ESCALATED_SUPERVISOR` → "Escalated supervisor"). This is formatting, not
  paraphrase — logic and logs keep raw tokens. (Decided post-token-pass.)
  Decided post-claim-detail-pass (owner call): e2e specs move to the doctrinal
  display — sentence-case badge text stands, Submit-decision confirm stands
  (`detail-submit-decision-confirm` does the write). Pinned single-click
  journeys get updated, not the screen.
  Conformed post-claim-detail-pass: structured-decision, staged, reopen, shots,
  queue specs route through the confirm + assert sentence case (queue audit
  'Claim escalated' included — same statusText() path). Deliberately left raw:
  claimant tracker `claim-status-state` (template interpolates raw status, no
  statusLabel — conforming would change what it verifies), p0 'RETIRED', staff
  'INACTIVE', legacy approve/deny single-click paths. Adoption debt for screen
  passes: only claim-detail uses statusLabel() — queue, escalations, my-claims,
  cockpit, policy-detail, policies, staff, claim-status still render raw tokens.
- **Toolbar:** 44px — search (240–320px) → filter chips (`Status: In review ✕`,
  never "Filters (3)") → spacer → result count → density/columns → primary right.
  Saved views as tabs above. Filter + tab state in URL. Every list exports CSV.
- **Modals/drawers:** 480px confirm / 640px form / 880px complex. Right drawer
  (400–560px) for preview/quick edit. Esc closes, focus traps + returns. Never
  nest. Confirms only for destructive/irreversible, naming the consequence and
  the verb. Reversible actions: no dialog, Undo in toast.
- **Toasts:** one line, specific, past tense, with Undo. 5s success, persistent
  errors. No emoji, no exclamation marks.
- **Timeline/audit:** left rail 16px icon + 1px line; actor 13px/500; plain-
  language action; object ref as link; absolute timestamp right, 12px tertiary.
  Group by day, sticky header. System vs human vs note distinguished.
  `before → after` for field changes. Visibly uneditable.

## 6. Data formatting (`format.ts` only, never inline)

Claim/policy numbers monospace, unwrapped, always the record link. Currency
right-aligned, tabular, grouped, symbol always, consistent decimals per column —
full value, never abbreviated, never rounded for display. Dates `03 Apr 2026`
(never `03/04/26`); date+time carries timezone on legally consequential events;
relative time secondary only. Aging integer + unit, color only past threshold.
Percentages one decimal. Empty = em dash in disabled color (never blank, never
"N/A" wallpaper). Masked PII: last 4 visible, reveal explicit + logged (when a
masking scheme exists — today none does; don't freelance one).
SLA urgency in its own column — never tint whole rows.

## 7. Six states per view

Loading (skeleton matching geometry; spinners only in buttons; keep old data on
refetch) · Empty-nothing (what appears here + creating action) ·
Filtered-to-zero (name the filters + Clear filters — distinct copy) · Error
(what failed, retry state, what to do, Retry + monospace reference ID — never
"Something went wrong") · Restricted (record exists, role can't see it, who to
ask — never silent) · Stale ("Updated 14:32" + refresh). Plain operator
language. Buttons name the outcome (`Approve payment`); the verb survives
confirm → toast.

## 8. Accessibility floor

4.5:1 text, 3:1 borders/meaningful icons. Visible focus ring everywhere.
Tab order = visual order; Enter activates; Esc closes; modals trap + restore.
Real semantics (`table`, `button`, `label for`, `aria-live`, `aria-sort`).
Shortcuts (`/` search, `j`/`k` rows, `e` edit, ⌘Enter save) + `?` sheet.
32px hit targets in tables, 44px touch.

## 9. Self-audit checklist (fix failures before presenting)

Generated-look · density (≥15 rows at 900px, 16px panel padding, 4px grid,
no h-scroll at 1280px, survives 1024px) · data · states · interaction
(one primary, verbs persist, destructive states consequence, Undo over dialog,
URL state, keyboard rows, focus trap, submit never disabled) · consistency
across screens (header, toolbar order, one badge map, one table convention,
one word per concept, shared formatters). Then: screenshot test (what gives it
away next to Stripe/Linear/Guidewire — fix that one thing) and subtraction
test (remove decoration until removing hurts).
