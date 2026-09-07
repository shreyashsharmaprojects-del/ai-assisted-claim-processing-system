---
name: enterprise-ui
description: Design and build data-dense enterprise application UI — admin consoles, back-office tools, operator dashboards, CRMs, internal line-of-business apps, ticketing and case management, order and inventory systems, finance and claims processing — so it looks like a mature software company shipped it rather than like it was generated. Use this skill whenever building, restyling, or reviewing any application interface, including full screens, tables, forms, detail pages, filters, modals, navigation, or a token and design system. Trigger it even when the user only says 'make this look professional', 'clean up the UI', 'make it look less AI', 'make it look designed', or asks for a single component — and especially for regulated or high-stakes back-office software where credibility and density matter more than flair.
---

# Enterprise UI

## The brief

Enterprise operator tools win **trust and throughput**, and in 2026 the design language that
communicates that is the modern SaaS back office — the world of Stripe Dashboard, Linear,
ServiceNow and the "Insure Craft" reference screens in this workspace's `design examples`
folder: **white cards with one quiet shadow recipe floating on a light cool-grey canvas,
Inter type, one corporate blue (#0056b3), and tinted bordered status pills.** Calm,
layered, believable. Not gradient-drenched, and emphatically not the flat-hairline
2010s/2000s aesthetic (grey panels fused into a grey page, 1px borders doing all the work,
4px corners, no depth at all) — that reads dated no matter how correct the information
architecture underneath it is.

Three properties separate mature enterprise software from generated software:

1. **Density.** Real operators need many rows on screen. Modern density is 36–44px rows with
   breathing room inside white cards — not 64px padded rows, and not walls of fused grey.
2. **Consistency.** One radius set, one border color, **one shadow recipe**, one icon set,
   one spacing grid, one button hierarchy — repeated without deviation across every screen.
   The same token on the same element everywhere is what reads as designed.
3. **Meaning-bearing color.** Color is a data channel. Green means *approved*, not
   decoration. A screen at rest is ~95% neutral; the accent appears only on primary actions,
   active nav, and focus.

## Process

Work in this order. Skipping straight to components is what produces incoherent screens.

**1. Name the domain object and the operator.** Before any styling, write down: who uses
this (support agent? underwriter? warehouse supervisor? account manager?), the primary
record they act on, its lifecycle states, and the three actions they perform most. Density,
default sort, and the status palette all derive from this. If it is unclear, ask — one
question, not a survey.

Nearly every enterprise tool is a **record-lifecycle product**: objects arrive, get triaged
into a queue, move through states, accrue documents and money and notes, and end in a
terminal state with an audit trail. Design for that shape.

**2. Fix the tokens.** Read `references/design-tokens.css` and adopt it wholesale, or adapt
hues to an existing brand while keeping the *structure* (same step count, same semantic
names). Never invent one-off hex values in a component. Every color, radius, and space in
shipped code resolves to a token. If the workspace ships a `design examples` folder, treat
it as the canonical reference for the target look and check your screens against it.

**3. Build the shell before the screen.** App frame first: top bar, left nav, page header
with breadcrumb, content region. Screens live inside a persistent frame. If the product has
distinct audiences (a public/claimant side and an internal side), build the appropriate
chrome for each: an internal operator gets a sidebar + top bar; a claimant gets a clean
public header. Both share the same token system and card language.

**4. Self-audit.** Run `references/review-checklist.md` against what you built and fix the
failures before presenting. This step is not optional; it is where most of the quality
comes from.

For component-level geometry (tables, forms, badges, modals, toolbars) read
`references/component-specs.md`. For whole-screen patterns (work queue, record detail,
multi-step intake, money and approvals, documents, audit trail) read
`references/screen-patterns.md`.

## The tells: what to remove

Treat the left column as a defect list. Note which direction "mature" points: several
"flat" habits that looked fine in 2015 are now themselves tells.

| Generated or dated tell | What mature 2026 products do |
|---|---|
| Purple/indigo→pink gradients, gradient buttons, gradient text | Flat fills. One corporate accent used only on primary actions and active nav |
| Flat-hairline fusion: grey panels on a grey page, no depth, 1px borders everywhere | **White cards floating on a light grey canvas** (`#F1F5F9`/`#F8FAFC`), one quiet card shadow, 12px radius — this is the baseline look, not an error |
| Random shadows: `shadow-lg` on some cards, none on others | **One shadow recipe** (`--shadow-card`) on every raised surface; bigger shadows only for dropdowns/modals/drawers |
| `border-radius: 16–24px` on everything, or `2px` on controls | 6px controls/buttons, 8px small tiles, 10–12px cards/panels/sidebar, `999px` pills. One radius per component type, repeated |
| Emoji as icons (📊 💰 ✅) | One line-icon set at 16px (Lucide, Phosphor, Heroicons outline, or Font Awesome in the reference style), monochrome, inheriting text color |
| Raw framework palette (`blue-500`, `gray-50`) or ad-hoc greys | A defined slate ramp with token names; every grey resolves to the ramp |
| A different accent color per card or per status chip | One accent. Statuses from a fixed enumerated set with token tints + borders + text |
| Four stat tiles with invented metrics at the top of every page | A single summary/banner card with the 4–6 facts the operator acts on, or nothing |
| `p-8` cards, `text-3xl` headings, 64px row heights | 16–24px card padding, 20px page title, 36–44px table rows |
| Hover `scale`/`translate-y` on rows and cards, animated entrances | Hover = background tint only. Motion answers an action, 120–180ms |
| Centered content in a `max-w-4xl` column | Full-bleed working area, left-aligned; forms get a readable 640–720px column; center only genuinely narrow content (empty states, login) |
| Success toast with confetti or "🎉 Awesome!" | Quiet, specific, with an Undo affordance |
| Numbers left-aligned in proportional figures | Right-aligned, `font-variant-numeric: tabular-nums`, consistent decimals |
| Only the happy path exists | Loading, empty, filtered-to-zero, error, restricted, stale states all designed |
| Dates as "2 days ago" only, or `1/3/25` | Unambiguous absolute dates, relative time secondary |
| Placeholder-as-label inputs, floating labels | Persistent labels above inputs |
| Every button is a filled accent button | One primary per view. Everything else secondary (white + border) or tertiary (plain) |

## Foundations

### Color

Structure: a cool slate ramp, one corporate-blue accent ramp, six semantic statuses. The
page canvas is a light cool grey (`--surface-sunken` ≈ `#F1F5F9`); **cards, panels, the
sidebar, and the header are pure white** with a single quiet shadow. This white-on-grey
layering — not hairline-on-hairline — is what gives a modern back office its depth.

Reserve saturation for state. A screen at rest should be roughly 95% neutral. If a
screenshot looks colorful, something is wrong.

Semantic statuses map to lifecycle, not to vibes. Each renders as a **pill: tinted
background + colored border + dark-enough colored text** (the reference style), with the
state word always present — a colorblind operator and a printed PDF must both survive.

### Type

Inter (loaded from Google Fonts in the reference screens) is the house face for this
style; the system stack is the fallback and is fine if no webfont is practical. Weights
400/500/600 only. Do not pair a display serif into an operator console.

Base is 14px, tables 13px, labels and metadata 12px, page titles 20px, section headings
15–16px semibold. Sentence case everywhere; no shouting uppercase.

Monospace is for identifiers you compare character by character: record numbers, policy
numbers, transaction IDs. Not for labels.

### Space and density

4px base grid. Card padding 16–24px, panel-to-panel gap 16px, form field gap 16px, related
control gap 8px, section gap 24px. Table rows 36–44px depending on the product's density
preference, with 13px text; don't pad rows to the point that a screen holds only six.

### Elevation

The model is layered, not flat:
- **card** — white surfaces (panels, tables, sidebar, header) get `--shadow-card`: a quiet
  pair of shadows that lifts them off the grey canvas.
- **raised** — dropdowns, popovers, autocomplete: `--shadow-raised`.
- **overlay** — modals, drawers: `--shadow-overlay` + scrim.
- **drag** — `--shadow-drag`, the only time a card lifts aggressively.

One recipe per level, product-wide. Random bigger shadows on "important" cards are the tell.

### Motion

120ms hover/focus, 160ms dropdowns/expands, 200ms drawers/modals. `ease-out` opening,
`ease-in` closing. Nothing animates on page load. Honor `prefers-reduced-motion`.

## Layout architecture

```
┌──────────────────────────────────────────────────────────────────┐
│ ▪ Product       [ Search records, accounts, people     ⌘K ]  ⚙ R │ 56px top bar (white)
├──────────┬───────────────────────────────────────────────────────┤
│ ▸ logo   │ ┌──────────────────────────────────────────────────┐ │
│ Work     │ │ Policies › POL-2026-08814         [Approve]     │ │ ← white card
│  Queue   │ │ Status · Type · Key date · Owner · Reserve       │ │
│  Assigned│ ├──────────────────────────────────────────────────┤ │
│ Accounts │ │ Overview │ Details │ Documents │ Activity  white │ │
│ Money    │ │ ──────────────────────────────────────────────── │ │ ← white cards
│ Reports  │ │                                                  │ │
│          │ └──────────────────────────────────────────────────┘ │
│ 240px    │  content area on the grey canvas (F1F5F9)           │
└──────────┴──────────────────────────────────────────────────────┘
```

Rules that hold across screens:

- **Persistent left nav**, 240px white card (or sidebar with a right border), grouped by
  the operator's mental model. Active item: soft blue tint + accent text (+ optionally a
  left accent bar), never a gradient.
- **Top bar** holds the product brand and global actions; internal tools get a user chip
  and sign-out there, public/claimant surfaces get a clean header with their one or two
  CTAs.
- **Page header** carries breadcrumb, object title, object identifier, and the 1–3 actions
  for the whole object. Primary action right-aligned.
- **Summary/banner card** on detail pages: the 4–6 facts a supervisor asks first, in one
  row, label above value, inside the top card.
- **Tabs for object sections**, never for navigation between objects; preserve the tab in
  the URL.
- **List/detail** for triage work: list on the left, preview on the right, `j`/`k` to move
  between rows.

## States and content design

Every view needs six states designed, not one:

- **Loading:** skeleton rows matching final geometry, not a centered spinner.
- **Empty (nothing yet):** one line explaining what appears here, plus the action that
  creates the first one.
- **Empty (filtered to zero):** different copy — name the filters and offer "Clear
  filters".
- **Error:** what failed, whether it retried, what the operator should do, and a reference
  ID. Never "Something went wrong."
- **Restricted:** the record exists but this role can't see it — say so plainly.
- **Stale:** show "Updated 14:32" with a refresh control.

Write UI copy in plain operator language. No exclamation marks. Buttons name the outcome
(`Approve payment`), and the same verb survives through confirmation and toast. Destructive
confirmations state the consequence.

Mask PII by default with an explicit, logged reveal. Show timestamps with timezone on
anything legally consequential.

## Accessibility and keyboard floor

Non-negotiable, and also what makes the product feel expensive:

- 4.5:1 contrast on text, 3:1 on borders and icons that carry meaning. Status tints are
  chosen so their text passes AA (e.g. `#15803D` on `#ECFDF5`, not light-green-on-lighter).
- Visible focus ring on every interactive element (3px soft accent ring). Never
  `outline: none` without a replacement.
- Full keyboard path: Tab order matches visual order, Enter activates, Esc closes, focus
  traps inside modals and returns to the trigger.
- Real semantics: `<table>` for tables, `<button>` for buttons, `<label for>` on every
  input, `aria-live` for async results.
- Shortcuts for repeated actions with a `?` cheat sheet.
- Hit targets 32px minimum in dense tables, 44px on touch.

## Before presenting

Run `references/review-checklist.md`. Then apply the two-question test:

1. **Screenshot test:** pasted next to the reference screens in `design examples` (or
   Stripe Dashboard, Linear, ServiceNow), what gives this away — a colour, a shadow, a
   corner, a grey, a font? Fix that specific thing.
2. **Subtraction test:** remove one decorative element. If nothing is lost, it was
   decoration — remove it and repeat.

State plainly what you changed and why, in design terms. Do not pad the response with
praise for your own work.
