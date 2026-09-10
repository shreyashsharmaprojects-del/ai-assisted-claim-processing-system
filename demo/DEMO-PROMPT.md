# Complete Demo Prompt — ClaimFlow Insurance Claim Processing App (improved)

> Copy-paste this prompt into any AI assistant (or a fresh session) to generate a
> complete end-to-end demonstration of the ClaimFlow claim processing system.

---

## The prompt

**"Create a complete, self-contained demonstration of the ClaimFlow insurance
claim processing application — a single-carrier system where claimants file
losses (FNOL), adjusters assess them through a staged workflow, and supervisors
oversee the whole book. The demo must cover the full journey of a claim across
all three roles, plus every platform capability listed below. Deliver it as a
narrated PDF walkthrough: one full-page screenshot per step, each with a title,
a one-line summary, and 2–3 short explanation bullets written in plain,
non-technical language suitable for a buyer, auditor, or new hire.**

### Roles and demo accounts (local dev defaults from `.env`)

- **Claimant** — `ada.lovelace` / `claims-Pass-123` (holds POL-10001, HLTH-PLUS,
  ₹10,00,000 sum insured, 5 covers). Also `grace.hopper` (POL-20002, AUTO-STD).
- **Adjusters** — `adjuster.one`, `adjuster.two` (L1, ₹1,00,000 authority),
  `adjuster.three` (L2, ₹4,00,000 authority) / `adjuster-Pass-123`.
- **Supervisor** — `supervisor` / `supervisor-Pass-123` (cross-book visibility,
  reassign, reopen, authority table, audit export, privacy admin).

### The core story (narrate one claim cradle-to-grave, screenshots in order)

1. **File (FNOL):** public home → 2-step claim form (policy identity, then loss
   details + per-cover picker with claimed amounts) → instant `CLM-` claim
   number → duplicate filing returns the *original* number (HTTP 409).
2. **Track:** claimant status tracker (steps only — never reserves/notes) → My
   Claims history with search + Approved filter → policy cockpit (per-cover
   sub-limit, claimed, remaining) → policy detail page.
3. **Triage (adjuster):** work queue (mine-only, age/SLA flags, tabs, search +
   sort) → claim work surface (facts, Review → Verification → Decision stepper)
   → reserve + internal notes + evidence (all internal-only, all audited).
4. **Staged assessment:** review triage per cover (claimed vs sub-limit,
   above-limit flags) → verification record (outcome, notes, evidence refs) →
   send-back to claimant with free-text questions (claim parks; tracker shows
   what was asked) → resume → per-cover assessment (server enforces assessed ≤
   claimed ≤ sub-limit ≤ remaining SI) → cover decision grid: approve / reject
   with remarks / split, structured denial code on every rejection.
5. **Decide:** authority gate — inside limit closes and queues payment +
   notification; above limit blocks and saves a proposal, then refer/escalate
   upward → legacy single-figure fast path for simple claims.
6. **Supervise:** overview dashboard (open, exposure, approvals, aging) →
   escalations queue → reassign mid-flight → notification outbox (SENT/FAILED +
   retry) → policy book admin (create, CSV import, retire) → authority ladder
   editor (L1/L2/L3/supervisor limits per product).

### Platform capabilities (each gets its own demo section)

- **Evidence integrity:** PDF + photo upload with magic-byte validation,
  SHA-256 hash + size stored per attachment, tamper-evident.
- **Object storage:** filesystem default with an S3-compatible seam (MinIO in
  dev, real S3 in prod, zero code change).
- **Required-documents checklist:** per-product document list per claim; link
  an upload or waive with rationale — kills the NEED_INFO ping-pong.
- **Document versioning:** supersede an attachment (new version replaces the
  old; timeline reads 'supersedes: \<name\>'), full version history kept.
- **Concurrency safety:** optimistic locking — two staff editing one claim,
  second saver gets a conflict banner showing who saved first, no silent
  overwrite (HTTP 409).
- **Reopen:** supervisor-only reopen of a closed claim with rationale;
  payments are sequenced (UNIQUE per claim), timeline notes 'Reopened — …'.
- **Staff admin:** activate/deactivate adjusters; queue auto-routes only to
  active staff, nothing strands.
- **Regulator-ready decisions:** structured denial codes (7 codes), rationale
  ≥ 20 chars, one-click CSV export of audit trail + decisions.
- **Privacy (GDPR):** claimant self-export of all personal data; supervisor
  anonymize action + retention report (7-year closed-claim retention).
- **Notifications:** bell with unread count, in-app notification center,
  per-user email/in-app/SMS preferences, outbox pattern (never lost).
- **Locale & accessibility:** INR formatting (₹1,500.00), en-GB dates,
  Europe/London day-boundaries, full keyboard navigation with focus trap,
  screen-reader roles.

### Guard rails to show (security vignettes)

Retired-policy filing blocked · stranger's claim reads as 404, not 403 ·
assessed amounts / reserves never cross the claimant wall · audit log is
append-only (no edit/delete exists).

### Output format

A4 PDF, one page per screenshot: small header ('ClaimFlow — complete demo'),
section number + title, one-line subtitle, the screenshot as large as the page
allows, and 2–3 explanation bullets beneath. Open with a cover page (product
name, tagline, page count, role→colour legend) and close with an appendix page
(system map: Angular SPA → Spring Boot → PostgreSQL + Keycloak SSO + Mailpit,
ports 4200/8081/8090, key API endpoints, authority ladder table). Embed a
Unicode font so the ₹ sign renders anywhere."

---

*Generated 2026-09-11. Source of truth: `docs/progress.md`, `docs/plan-v3.md`
(S1–S11), `demo/build_pdf.py`, `api.http`.*
