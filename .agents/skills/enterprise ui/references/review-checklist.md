# Review checklist

Run before presenting any screen. Go through it honestly — the value is in finding
failures, not confirming success. Fix what fails, then report what changed.

Use it two ways: as a self-audit on new work, and as a diagnostic on an existing UI the
user wants improved. In diagnostic mode, report findings grouped by severity with the
specific fix, rather than rewriting everything unasked.

## A. The generated-look test

- [ ] No gradients anywhere — not on buttons, headers, backgrounds, or text
- [ ] Every raised surface (panel, card, sidebar, header) uses the **same** `--shadow-card`
      recipe — no random stronger shadows, no missing shadow on a sibling card
- [ ] White cards float on the light grey canvas — no grey-on-grey flat fusion, no
      hairlines doing all the separation work
- [ ] Radius per component type is tokenized and repeated: 6px controls, 8px tiles,
      10–12px cards, 999px pills. Nothing is 16–24px; nothing is 2px
- [ ] No emoji used as interface iconography
- [ ] One icon set, one weight, one size per context
- [ ] Exactly one accent color; no per-card or per-section color variation
- [ ] No `hover:scale`, lift, or translate on rows and cards
- [ ] Nothing animates on page load
- [ ] No stat-card grid of invented metrics (a single summary/banner card is fine)
- [ ] Page title is 20px or smaller; body text is 14px or smaller
- [ ] Content is left-aligned and full-bleed, not centered in a narrow column
- [ ] No raw framework palette values (`blue-500`, `gray-50`) in the code; greys come from
      the ramp, statuses from the enumerated token sets
- [ ] Copy contains no exclamation marks, no "Oops", no "Awesome", no `→` in button labels
- [ ] Compared against the `design examples` folder (when present): same surface language —
      white cards, quiet shadow, Inter, corporate blue, tinted pills

## B. Density and rhythm

- [ ] Table rows are 36–44px; at least 12–15 rows visible at 900px viewport height
- [ ] Card padding is 16–24px and consistent between sibling cards
- [ ] Every spacing value is on the 4px grid and comes from a token
- [ ] Vertical rhythm is consistent across sibling sections (equal gaps between cards)
- [ ] No horizontal scrolling at 1280px, and the layout survives 1024px

## C. Data presentation

- [ ] Numbers and currency are right-aligned with `tabular-nums`
- [ ] Decimal places are consistent within each column
- [ ] Dates are unambiguous and formatted identically everywhere
- [ ] Identifiers are monospace and link to their record
- [ ] Empty values render as an em dash, consistently
- [ ] Column alignment follows type (text left, numeric right) without exception
- [ ] Long values truncate with ellipsis and a title, rather than wrapping
- [ ] Totals rows are visually distinguished from data rows
- [ ] PII is masked by default with an explicit, logged reveal

## D. States

- [ ] Loading state exists and uses skeletons matching final geometry
- [ ] Empty state exists with an explanation and an action
- [ ] Filtered-to-zero state is distinct from the empty state
- [ ] Error state names the failure, offers retry, and shows a reference ID
- [ ] Permission-restricted state is explicit, not a silent hide
- [ ] Partial-failure results are reported per item, not as a blanket success
- [ ] Stale data shows its last-updated time

## E. Interaction

- [ ] One primary button per view
- [ ] Buttons name the outcome, and the same verb persists through confirm and toast
- [ ] Destructive actions confirm with the specific consequence stated
- [ ] Reversible actions offer Undo instead of a confirmation dialog
- [ ] Filter state and tab state are in the URL
- [ ] Table supports keyboard row navigation and Enter to open
- [ ] Modals trap focus, close on Esc, and return focus to the trigger
- [ ] Submit buttons are never disabled as a way of showing invalid state

## F. Accessibility

- [ ] Body text meets 4.5:1 contrast; borders and meaningful icons meet 3:1. Status pill
      text sits at AA on its tint (dark tint text, not the saturated hue itself)
- [ ] Visible focus ring on every interactive element
- [ ] Tab order matches visual order
- [ ] Status is conveyed by word + color (pill always carries the state text), never color
      alone
- [ ] Semantic HTML: real `<table>`, `<button>`, `<label for>`
- [ ] `aria-sort` on sortable headers, `aria-live` on async results
- [ ] `prefers-reduced-motion` honored
- [ ] Hit targets at least 32px in dense contexts

## G. Consistency across screens

- [ ] Same page-header structure on every screen
- [ ] Same toolbar order: search → filters → count → controls → primary action
- [ ] Same status pill mapping product-wide, defined in one place
- [ ] Same table conventions: header style, row height, action placement
- [ ] Same terminology throughout — one word per concept, matching what operators say
- [ ] Formatting goes through shared formatter functions, not inline code

## H. Final two questions

1. **Screenshot test.** Next to the reference screens in `design examples` (or Stripe
   Dashboard, Linear, ServiceNow), what specifically gives this away? Name it and fix it.
2. **Subtraction test.** Remove one decorative element — a shadow, a divider, an icon, a
   color. If nothing is lost, it was decoration — remove it and repeat.

## Severity guide for diagnostic mode

- **High** — anything in A, plus missing error/permission states, contrast failures,
  missing focus rings, inconsistent money formatting, dated flat-fusion layouts.
- **Medium** — density, spacing off-grid, missing empty states, inconsistent alignment,
  multiple primary buttons.
- **Low** — copy polish, icon inconsistency, missing keyboard shortcuts, tab counts.

Lead with High. Do not present a list of forty items without ordering it.
