# Component specs

Concrete geometry and behaviour for the primitives an operator tool is built from. Values reference tokens in `design-tokens.css`.

## Contents
1. Buttons
2. Inputs and forms
3. Data tables
4. Status badges
5. Toolbars, filters, saved views
6. Navigation and tabs
7. Modals, drawers, popovers
8. Notifications
9. Empty, loading, error states
10. Timeline / activity feed

---

## 1. Buttons

Four variants. **Exactly one primary per view** — if two things look equally important, the screen has no point of view.

| Variant | Fill | Border | Text | Use |
|---|---|---|---|---|
| Primary | `--accent-600` | none | white | The single main action |
| Secondary | white | 1px `--border-default` | `--text-primary` | Everything else |
| Tertiary | transparent | none | `--text-secondary` | Toolbar and row-level actions |
| Danger | white | 1px `--status-danger-br` | `--status-danger-fg` | Destructive; filled red only in the confirm dialog |

Geometry: height `--control-height` (38px), padding 0 16px, radius `--radius-sm` (6px), weight 500, size 13px. Icon-only buttons are square at the same height with a tooltip and an `aria-label`.

States: hover darkens fill one step (`--accent-700`) or tints background `--surface-hover`; active one step further; focus shows `--focus-ring` (soft 3px accent ring); disabled drops to `--text-disabled` with no shadow change. Loading replaces the label with a 14px spinner **and keeps the button's width** so the layout does not jump.

Never: gradients, shadows on buttons, uppercase labels, a `→` appended to the label, or full-width buttons outside modals and mobile.

## 2. Inputs and forms

Structure per field, top to bottom: label (12px, 500, `--text-secondary`) → control → helper or error text (12px).

Control: height `--control-height` (38px), padding 0 12px, 1px `--border-default`, radius `--radius-sm` (6px), 13–14px text, white fill. Hover moves the border to `--border-strong`; focus sets `--border-focus` plus the soft focus ring; error sets `--status-danger-br` with a 12px message below.

Rules that carry most of the professionalism:

- **Labels are persistent and above the field.** Placeholder-as-label breaks under autofill, on review, and for screen readers. Placeholders show format only: `DD/MM/YYYY`, `AB-1234-XY`.
- **Required is marked, optional is not** — unless most fields are required, in which case mark "(optional)" instead. Pick one convention per product.
- **Group into fieldsets** with a 15px semibold heading and a one-line description, max 640px wide. A form spanning the full 1920px viewport is unreadable.
- **Two columns only for genuinely paired data** (city/postcode, from/to dates). Otherwise single column — it is measurably faster to complete.
- **Validate on blur, not per keystroke.** Re-validate on submit and move focus to the first error with a summary at the top of the form.
- **Inline errors say what to do:** "Enter the date of loss as DD/MM/YYYY" beats "Invalid date".
- **Never disable the submit button** to express invalid state — the operator can't tell what's missing. Let them submit and show the errors.
- Currency inputs: prefix the symbol inside the field, right-align the digits, tabular numerals, no spinner arrows.
- Autosave drafts on long intake forms and show "Saved 14:32". Losing twenty minutes of entry is how a tool loses its users.

## 3. Data tables

The table is the product. Get it right and the app reads as professional even with plain styling.

**Geometry.** The table sits in a white card. Header 40px, rows `--row-height` (44px), cell padding `--cell-padding-x`, 13px text, 1px `--border-subtle` between rows, no vertical grid lines. Zebra striping is unnecessary and adds noise — a hover tint does the same job better.

**Header.** 12px, 500, `--text-secondary`, `--surface-tint` background (`#F8FAFC`), sticky on scroll with a bottom border. Sortable headers show a chevron on hover and a solid one when active, plus `aria-sort`.

**Alignment.** Text left, numbers and currency right with tabular numerals, dates left, status badges left, actions right. Column alignment inconsistency is one of the most visible amateur tells.

**Column discipline.** Identifier first (monospace, links to the record), then the 4–6 fields the operator triages on, actions last. Anything more belongs in a column picker. Truncate with ellipsis and a title attribute rather than wrapping — wrapped rows destroy scannability.

**Row behaviour.** Whole row clickable to open the record; hover tint `--surface-hover`; keyboard selection with `j`/`k` and Enter; selected rows get `--surface-selected` plus a 2px accent left border. Row actions live in a `⋯` menu revealed on hover *and* on focus, plus one or two inline icon buttons for the most common action.

**Bulk selection.** Checkbox column at 40px. Selecting rows swaps the toolbar for a selection bar: "12 selected — Assign, Change status, Export, Clear". Support shift-click ranges.

**Pagination.** Server-paginated with page size options (25 / 50 / 100) and an accurate total: "1–50 of 3,271". Infinite scroll is wrong for work queues — operators need to know how much is left and to return to a position.

**Sticky columns** for the identifier column when the table scrolls horizontally.

## 4. Status badges

Badge = 22px tall **pill**, radius `--radius-full` (999px), 11px 600 text, 1px border in the status tint, 10px horizontal padding, optional 12px icon. **Pill = tinted background + colored border + dark tint text** (e.g. success is `#ECFDF5` bg / `#A7F3D0` border / `#15803D` text). The state word is always present — color is never the only channel.

Map lifecycle states to the semantic set once, centrally, and never override per screen:

| Lifecycle state | Token set | Icon |
|---|---|---|
| Draft, Closed, Archived | neutral | circle-dashed / archive |
| Submitted, In review, Assigned | info | clock / eye |
| Approved, Paid, Settled | success | check |
| Awaiting documents, On hold, Pending | warning | pause / alert |
| Denied, Rejected, Expired, Overdue | danger | x / alert-triangle |
| Under investigation, Escalated, Disputed | special | shield |

Inside dense tables a smaller 18px pill with just text + tint is fine — but keep the tint
pair, never colour alone.

## 5. Toolbars, filters, saved views

Above every list, a 44px toolbar in one row: search field (240–320px, left) → filter chips → spacer → result count → density/column controls → primary action (right).

- **Filters as chips** showing the applied value: `Status: In review ✕`, `Owner: Me ✕`. A generic "Filters (3)" button hides state that the operator needs to see; the whole point is knowing what you are looking at.
- **Saved views** as tabs above the toolbar — "My queue", "Unassigned", "Breaching SLA", "All open". This is the feature that makes an internal tool feel like real software, and it is usually the highest-value thing you can add.
- Filter state lives in the URL so views are shareable and the back button works.
- Every list needs a working **Export CSV**. Back-office users will ask for it within a day.

## 6. Navigation and tabs

**Left nav:** 240px, `--surface-default` (white) with a right border — or a floating white card. Items 40px tall, 13px, 16px icon, 10px gap. Section labels 12px `--text-tertiary` with 16px top margin. Active: `--surface-selected` background (`#F0F7FF`), `--accent-700` text, 3px accent left border (or a tinted rounded item). Hover: `--surface-hover`. Collapsed rail keeps icons with tooltips.

**Breadcrumb:** 12px, `--text-tertiary`, chevron separators, last item plain text not a link. Truncate the middle on deep paths.

**Tabs:** 40px tall, 13px 500, 2px bottom border on the active tab in `--accent-600`, inactive `--text-secondary`. Counts appear as a grey pill after the label: `Documents 7`. Underline tabs for object sections; pill/segmented controls for view switching (Table / Board / Calendar). Do not use both idioms for the same job.

## 7. Modals, drawers, popovers

**Modal:** 480px (confirm), 640px (form), 880px (complex) — width by content, never `max-w-2xl` for everything. Radius `--radius-lg`, `--shadow-overlay`, scrim `--scrim`. Header 16px semibold with a close button; footer right-aligned with secondary then primary. Esc closes, focus traps inside, focus returns to the trigger. Never nest a modal inside a modal — use a drawer or a full page.

**Drawer:** right side, 400–560px, for detail preview and quick edit without losing list context. Preferred over modals for anything the operator might want to compare against the list.

**Popover/menu:** `--shadow-raised`, radius `--radius-lg`, 4px padding, items 30px tall at 13px. Destructive items last, separated by a divider, in `--status-danger-fg`.

**Confirmation dialogs:** only for destructive or irreversible actions. State the consequence specifically ("This voids payment PAY-4471 for $10,540 and notifies the customer"), label the button with the verb (`Void payment`), and require typing the identifier for anything unrecoverable. For reversible actions skip the dialog and offer Undo in the toast instead — faster and less annoying.

## 8. Notifications

Toast: bottom-right or top-center, 320–420px, `--shadow-overlay`, 5s auto-dismiss for success, persistent for errors. One line, specific, past tense: "Order ORD-2026-4417 assigned to R. Iyer." with an `Undo` link. No emoji, no exclamation marks, no "Success!".

Inline banners at the top of the content region for conditions that persist: system maintenance, a record locked by another user, a failed sync. These use the semantic tint backgrounds with a matching left border and always offer an action.

## 9. Empty, loading, error states

**Loading:** skeleton rows matching the real geometry (same heights, same column widths), animated with a slow opacity pulse rather than a shimmer sweep. Skeletons for initial load; keep old data visible with a subtle overlay for refetches so the screen doesn't flash.

**Empty — nothing yet:** centered in the panel, max 320px wide, 16px optional icon-in-circle, 14px 500 line ("No records assigned to you"), 13px `--text-secondary` explanation, one primary action. No illustration unless the product has a real illustration system.

**Empty — filtered to zero:** "No records match these filters" + a `Clear filters` button. Distinct copy from the above; conflating them confuses operators into thinking data was lost.

**Error:** what failed, whether the system retried, what to do, a `Retry` button, and a reference ID in monospace for support tickets. Log the real error; show the operator the actionable version.

## 10. Timeline / activity feed

Essential in regulated workflows, and one of the strongest credibility signals in enterprise software.

Each entry: 16px actor avatar or icon in a left rail with a 1px connecting line, then actor name (13px 500), action in plain language, object reference as a link, and an absolute timestamp right-aligned (12px `--text-tertiary`) with relative time in the tooltip. Group by day with a sticky date header.

Distinguish **system events** (grey icon, italic-free plain text) from **human actions** (avatar) from **notes** (rendered in a bordered block with the note body). Field changes show before → after values. Nothing in an audit trail is editable or deletable — and it should visibly look that way.
