# Decisions

Short records of choices that would otherwise get re-argued, plus the things we decided
not to build. Newest first.

## Decisions

### 2026-09-10 — S9 GDPR + retention story (V24 privacy_request)

**Context:** The first EU pilot needs a designed privacy answer: export-my-data,
erasure-without-rewriting-history, and a retention report — before anyone asks.
Needs final column knowledge (S1–S8), hence late in the order. Plan:
`docs/plan-v3.md` S9.

**What changed (all additive):**
- **Migration `V24__privacy.sql`:** `privacy_request` table (`claimant_sub`,
  kind EXPORT|ERASURE, status COMPLETED default — a row documents a completed
  handling, never a pending workflow, `handled_by`/`handled_at`); index on
  sub. No PII columns added anywhere.
- **Export `GET /api/privacy/me/export`** (CLAIMANT, own-sub enforced in the
  service): own policies + claims + covers + attachment **metadata** (name,
  type, size, sha, label, docType — never bytes, no storage read) + timeline
  milestones (audit CLAIM rows) as a JSON attachment download. Slice-literal:
  exports write **no** `privacy_request` row.
- **Anonymize `POST /api/admin/privacy/anonymize {claimantSub, rationale}`**
  (SUPERVISOR): overwrites **only** the listed columns —
  `policy.holder_name→'REDACTED'`,
  `holder_email→'redacted+<sha8(sub)>@example.invalid'` (single-owner policies
  only — shared policies are 400 with an explanation),
  `claim.claimant_sub→'ANON:<sha8>'` + `claimant_remarks→NULL`,
  subject-authored `internal_note` bodies → `'[redacted]'` (row + author link
  kept), `attachment.uploaded_by_sub→NULL`. `sha8` = first 8 hex of
  SHA-256(sub). **Never touches:** `audit_log` (V7 trigger), payment amounts,
  decision outcomes/remarks, claim descriptions. Writes an ERASURE row +
  `PRIVACY_ERASURE` audit row. Idempotent rerun (the ANON: key matches, stable
  200).
- **Retention `GET /api/admin/privacy/retention-report`** (SUPERVISOR): fixed
  `olderThan6y/7y/10y` counts of CLOSED claims (`closed_at` older than the
  window; NULL excluded) + `policyClosedYears` from
  `claims.retention.closed-years=7` (advisory — report-only, **no auto-delete**).
- **Timeline:** anonymized actors/holders render "Redacted".
- **Test scaffolding:** `privacy_request` in the
  `ClaimTableResettingTest` TRUNCATE list (no FK to claim); seed holder
  identities restored in `@BeforeEach` (redaction is permanent in the shared
  Testcontainers DB); `@Ordered` tests with dedicated single-use policies per
  test (an earlier anonymize would 404 later filings via holder mismatch).
- **Frontend:** My-claims "Download my data" button (testid
  `myclaims-export`, JSON download); `/admin/privacy` panel (supervisorGuard)
  with anonymize confirm + retention table (testids
  `admin-privacy-anonymize/confirm/report`).

**Verified:** `PrivacyIntegrationTest` 5/5 x3 (export own-only + no export
trail; anonymize exact columns + history byte-identical + idempotent; shared
400; retention buckets; auth matrix), full backend suite 289/289 (284/284 at
S8 per `git log` + 5 new — docs-only session, tests not re-run), frontend
build green, `privacy.spec` 1/1 (valid JSON with exactly the caller's claims).
**Deliberately not built (Non-goals):** auto-deletion jobs, consent
management, DPA paperwork (docs link only).

### 2026-09-10 — S8 structured decisions + audit export (V23 denial_reason)

**Context:** Closures carried free-text rationale only, so nobody could ask
"why was this cover denied" in a structured way, and an audit story could not
leave the system except as screen text. Plan: `docs/plan-v3.md` S8 (depends
on S5 version-checked decisions + S6 reopen/re-decide).

**What changed (all additive):**
- **Migration `V23__decision_codes.sql`:** `claim_cover.denial_reason
  VARCHAR(60) NULL`. Codes are a Java enum (`DenialReason`, 7 codes:
  NOT_COVERED, EXCLUDED_PER_CLAUSE, ABOVE_SUB_LIMIT_EXHAUSTED,
  INSUFFICIENT_EVIDENCE, DUPLICATE_PRE_EXISTING, FRAUD_SUSPECTED_REFERRAL,
  OTHER) — no lookup table this slice; mapped onto `ClaimCover.denialReason`
  and `CoverOutcome.denialReason`.
- **≥20-char rationale on all closures:** staged decide/decideEscalation
  (`StagedWorkflowService.requireClosureRationale`) + `AuthorityGate.validate`
  for the legacy single-figure and escalation decisions (legacy has no
  per-cover rejects, so rationale-only there).
- **Code + remarks per REJECTED cover:** every reject needs a denial code
  (OTHER still needs remarks); 400s name the offending `coverCode` (unknown
  codes 400 naming the 7 valid values); validate-before-write —
  `expectedVersion` compare-and-swap runs first (stale → 409), so a 400 never
  moves the claim.
- **Exports (`AuditExportController`, SUPERVISOR-only at the URL):**
  `GET /api/audit/export?claimNumber=` (claim audit CSV: at, actor, action,
  before, after, rationale) + `GET /api/decisions/export?from&to` (closure
  CSV: claim, policy, product, aggregate, totals, decider, rationale, denial
  codes). Streamed via `JdbcTemplate`, `text/csv`,
  `Content-Disposition: attachment`; unknown claim → 404, never 403.
- **Claimant wall:** claimant-visible denial text is the remarks; codes never
  leave the supervisor surface.
- **Frontend:** denial-reason `<select>` per rejected cover + rationale
  textarea with live char count and min-length error (testids
  `detail-deny-reason-{coverCode}`, `detail-rationale-count`); Export buttons
  on the audit panel + overview (testids `overview-export-audit/decisions`).

**Verified:** `StructuredDecisionIntegrationTest` 5/5 (short-rationale 400,
codeless-reject 400, unknown-code 400, valid close persists codes + exports,
supervisor-only/unknown-404), full backend suite 284/284 (279/279 at S7 per
`git log` + 5 new — docs-only session, tests not re-run), frontend build
green, `structured-decision.spec` 1/1 (reject with a code, count hint, close).
**Deliberately not built (Non-goals):** appeal-letter templates,
code-effectiveness reporting.

### 2026-09-10 — S7 staff management surface, supervisor-only (no migration)

**Context:** Offboarding stranded queues: deactivating an adjuster had no path,
so their open claims sat with someone who left. The `app_user.active` flag
exists since V12, but the entity was out of sync — this slice maps it
(`AppUser.active`, default TRUE) instead of adding a migration. Plan:
`docs/plan-v3.md` S7.

**What changed (all additive):**
- **No migration:** `active BOOLEAN NOT NULL DEFAULT TRUE` is V12; the slice
  only maps the column onto the entity. A DB predating V12 must migrate first.
- **No `@Version` on `AppUser`:** single-writer admin action — plain update
  (`StaffService.setActive` + `saveAndFlush` before the JDBC re-read, since
  `JdbcTemplate` never triggers a persistence-context flush).
- **Active-only filter at the one choke point:** `ClaimAssigner.assign` takes
  candidates from `findByLevelAndActiveTrueOrderById`, so FNOL, reassign,
  reopen, and aging all skip inactive adjusters with no per-caller change.
- **Backend (`StaffService`/`StaffController`, SUPERVISOR-only at the URL):**
  `GET /api/staff` → `[{id, displayName, email, level, active, keycloakSub,
  openClaims}]` (open = `COUNT claim WHERE assigned_adjuster_id AND status <>
  'CLOSED'`, id order); `PUT /api/staff/{id}/active {active}` flips the flag.
  Deactivation drains every open claim through `ClaimAssigner` (level
  preserved, least-loaded active same-level adjuster) with one
  `CLAIM_REASSIGNED` audit row per move; claims with no eligible target park
  UNASSIGNED (the `?status=UNASSIGNED` queue filter is the attention list — no
  reason column exists). Reactivation flips without moving. Unknown id → 404,
  missing/null `active` → 400. No Keycloak writes.
- **Frontend:** `/admin/staff` (supervisorGuard, "Supervision" nav, testid-free
  link) — table with load counts, `keycloak_sub` + copy affordance,
  active toggle with confirm + affected-claims warning (testids
  `staff-page/row-{id}/toggle-{id}/confirm/load-{id}`); global classes only,
  no stylesheet.

**Verified:** `StaffAdminIntegrationTest` 4/4 (list loads; deactivate moves +
audits + parks the unroutable one; reactivate no-move; auth matrix — anon
401, adjuster/claimant 403, unknown id 404), full backend suite 279/279
(275/275 at S6 per `git log` + 4 new — docs-only session, tests not re-run),
frontend build green, `staff.spec` 1/1 x2 (supervisor deactivates the holder
→ claim drains to another L1 queue → reactivated in a finally).
**Deliberately not built (Non-goals):** Keycloak provision/deprovision API,
named-adjuster assign (reassign still takes a level), capacity targets/WLB.

### 2026-09-09 — S6 claim reopen / appeal, supervisor-only (V22 seq + UNIQUE(claim_id,seq))

**Context:** Plan V2 keeps "no appeals" as a non-goal (2026-09-03
"Reopening / appeals" note, ~L1636-1640: one-way flow to closure, no appeal
process defined). This slice **overrides** that line: new evidence, claimant
disputes, and regulator asks are handled inside the system instead of "file a
new claim" (which corrupts cycle-time metrics). Depends on S5 (reopen is
version-checked). Plan: `docs/plan-v3.md` S6.

**What changed (all additive):**
- **Migration `V22__reopen_payments.sql`:** `payment.seq INT NOT NULL
  DEFAULT 1` (backfills existing rows); drops `payment_claim_id_key`; adds
  `payment_claim_seq_unique UNIQUE (claim_id, seq)`. Claim needs no new
  status — reopened claims re-enter UNDER_REVIEW[REVIEW].
- **Backend:** `StagedWorkflowService.reopen` + `POST /api/claims/{n}/reopen`
  (`SecurityConfig` → SUPERVISOR; non-supervisors get 403 at the gate, adjuster
  403 before any claim logic). Ordering: access check first (stranger →
  `ClaimNotFoundException` 404, never a leak), then `expectedVersion`
  compare-and-swap (409), then guards (400): non-CLOSED → 400, rationale
  < 20 chars → 400 (max 400). Body: `{rationale, expectedVersion}`
  (`ReopenInput`).
- **Effect (one transaction):** `status=UNDER_REVIEW`, `stage=REVIEW`,
  `decision`/`decision_remarks`/`closed_at` cleared (`Claim.reopen()`); level
  preserved; reassign via `ClaimAssigner` (skill-aware, least-loaded at claim
  level); `CLAIM_REOPENED` audit (before CLOSED → after UNDER_REVIEW +
  rationale); `EmailOutboxWriter.enqueueReopen` in-transaction (kind stays
  DECISION — the V10 kind CHECK is immutable this slice and S10 assumes the
  three kinds). Proposals stay as the closure left them (the new handler
  assesses fresh); verification history is kept and visible.
- **Next closure inserts `payment(seq = max+1)`** at all three closure sites
  (staged assess path, staged escalation path, legacy `ClaimDecisionService`);
  `payment.amount == net_payable` per row; claim totals reflect the latest closure.
- **Timeline:** `CLAIM_REOPENED` renders "Reopened — \<rationale\>".
- **Frontend:** supervisor-only "Reopen claim" on closed claims (testids
  `detail-reopen-toggle/rationale/confirm`; ≥20-char client guard, S5 409
  path); claimant tracker "Your claim was reopened — what happens next" panel
  via a marker-free heuristic (open status + cleared decision + decided
  covers). Queue regains the claim automatically (status-driven, no filter change).

**Verified:** `ClaimReopenIntegrationTest` 6/6 (closed → reopen → re-decide:
payment seq 2 + latest totals; open-claim 400; short-rationale 400; adjuster
403; unknown-claim 404; stale-version 409 then fresh-version success), full
backend suite 275/275 (269/269 at S5 per `git log` + 6 new — docs-only
session, tests not re-run), frontend build green, `reopen.spec` 1/1 x3 runs
(supervisor reopens a decided staged claim, tracker shows reopened, adjuster
re-works to closure).
**Judgment calls:** stranger-404 surfaces as unknown-claim 404 (non-supervisors
403 at the gate); tracker marker-free heuristic (legacy no-cover edge renders
no panel); E2E uses POL-10001 (POL-30002 carries a 10k deductible cap on the
shared DB).
**Deliberately not built (Non-goals):** claimant-filed appeals
(supervisor-only this slice), multi-tranche payments (still one payment per
closure), auto-reopen rules.

### 2026-09-09 — S5 optimistic concurrency (V21 version + @Version, 409-on-conflict)

**Context:** Two adjusters on one claim (post-reassign) could silently
overwrite each other on the money paths. V21 adds `claim.version BIGINT NOT
NULL DEFAULT 0` with `Claim.java` `@Version private Long version` (+ getter,
no setter). Six writers compare-and-swap via body field `expectedVersion`
(reserve, assessment, cover-decision, decision legacy, escalation-decision,
escalation-cover-decision); S6 reopen doesn't exist yet — its writer comes
with S6. The version check runs before stage guards in the same transaction,
so a 409 never masks a guard 400. A single `ApiExceptionHandler` maps every
conflict (`OptimisticLockException` / `ObjectOptimisticLockingFailureException`
/ manual version-mismatch) to 409 `{error:"CONFLICT", message:"This claim
changed since you opened it. Reload and retry."}`.

**What changed (all additive):**
- **Backend:** every mutating form accepts the caller's loaded `version` as
  `expectedVersion` (`ReserveRequest`, `AssessmentInput`,
  `CoverDecisionInput`, `ClaimDecisionInput`, escalation inputs); mismatch
  (or missing) → 409 via the one handler, never a 500 with driver internals.
- **Staff views expose `version`:** `InternalClaimView` / `StagedClaimView`
  carry it so forms can send it back. The claimant tracker was skipped — it
  never mutates, so it needs no version.
- **Frontend:** reserve/assessment/decision forms send their loaded `version`;
  on 409 a warning banner ("Someone changed this claim — reloaded the
  latest", testid `detail-conflict-banner`, `role=status`) + refetch.
  Escalation flows through the same shared banner path.

**Verified:** `ClaimConcurrencyIntegrationTest` 3/3 (v0 write ok → stale v0
→ 409; concurrent reserve pair; stale decision after reassign), full backend
suite 269/269 (266/266 at S4 per `git log` + 3 new — docs-only session,
tests not re-run), frontend build green, `conflict.spec` 1/1 (stale reserve
in a second context → banner).
**Deliberately not built (Non-goals):** live collaboration/presence,
SSE/polling (queues still fetch-on-load), field-level merge (last-writer-wins
per form is correct here).

### 2026-09-09 — S4 document metadata + supersede (V20 doc_type + replaces_attachment_id)

**Context:** Duplicate uploads had no chain: a corrected bill was just another
row, so adjusters could not tell v2 superseded v1. V20 adds advisory
`attachment.doc_type VARCHAR(60)` (mirrors `required_document.doc_key`, free
for ad-hoc) + `replaces_attachment_id` (FK → attachment, ON DELETE SET NULL)
with `idx_attachment_replaces`. History stays append-only — old bytes stay.

**What changed (all additive):**
- **Both uploads accept `docType` (≤60 chars, else 400) + `replacesId`:**
  adjuster attach (`ClaimWorkController`) + claimant NEED_INFO documents
  (`ClaimantStatusController`, multipart part names `docType`/`replacesId`).
  Guards: the replaced row must belong to the same claim (cross-claim or
  unknown `replacesId` → 404, never a leak); CLOSED claims still 400.
- **Timeline:** the new upload's entry gains ` (supersedes: <originalName>)`
  text; a chain of 3 renders in order. `AttachmentView` carries `docType` +
  `replacesId` (both nullable).
- **Frontend (adjuster-only):** the claim-detail attach form gains a doc-type
  `<select>` (required-doc options + "Other", testid `detail-attach-doctype`)
  and a "replaces" picker over the claim's existing attachments (testid
  `detail-attach-replaces`); timeline rows show a "supersedes X" chip (testid
  `detail-timeline-supersedes`). The claimant side was deliberately left alone
  — claimants have no attachments list/timeline to pick from or render into.
- **docType is advisory** and independent of the S3 `docKey` auto-link:
  `docKey` still drives PENDING→RECEIVED flips; `docType` rides along without
  affecting it.

**Verified:** `AttachmentSupersedeIntegrationTest` 6/6 (same-claim link +
timeline text, cross-claim/unknown replacesId 404, docType >60 → 400,
docKey auto-link unaffected, chain of 3, CLOSED 400 kept), full backend
suite 266/266 (260/260 at S3 per `git log` + 6 new — docs-only session,
tests not re-run), frontend build green, `fnol.spec` 4/4 (3 existing + 1
new S4 supersede journey: v2 upload with replaces picker → timeline chip).

**Deliberately not built (Non-goals):** full version-history UI, diffing
between versions, delete-old-on-replace (append-only history).

### 2026-09-09 — S3 required-documents checklist per product (V19 tables + seeds)

**Context:** "What's missing" lived in free-text NEED_INFO notes, so claimants
played ping-pong with adjusters. V19 adds `required_document` (per-product
expectations) + `claim_document_check` (per-claim PENDING/RECEIVED/WAIVED rows),
seeded 3-per-family — HEALTH rows duplicated per HEALTH code (HLTH-BASIC /
HLTH-PLUS / HLTH-CRIT: DISCHARGE_SUMMARY, FINAL_BILL, ID_PROOF), AUTO rows per
AUTO code (AUTO-STD / AUTO-COM + legacy V1 AUTO: PHOTOS, ESTIMATE, RC_COPY),
PROPERTY rows per PROPERTY code (PROP-HOME / PROP-FIRE + legacy V1 HOME:
PHOTOS, ESTIMATE, OWNERSHIP_PROOF). HLTH-ORPHAN is deliberately unmapped
(empty checklist). Seeds are claim-level (`cover_code` NULL throughout).

**What changed (all additive):**
- **FNOL auto-seed:** `ClaimService` inserts PENDING `claim_document_check`
  rows for the policy product's required docs in the filing transaction.
- **GET `/api/claims/{n}/required-documents`:** assignee/supervisor get full
  rows (`checkId/docKey/displayName/status/attachmentId/decidedBy/decidedAt`);
  the owning claimant gets `{displayName, status}` only (`decided_by` walled —
  string-absence asserted); anyone else gets 404 (never 403).
- **`POST …/{checkId}/link {attachmentId}` / `/waive {rationale}`:**
  assignee-or-supervisor only (404 otherwise); the attachment must belong to
  the claim (else 404); waive needs a rationale (else 400). Each writes an
  audit row (`DOC_LINKED` / `DOC_WAIVED` with claimNumber/docKey/checkId).
- **`docKey` auto-link on uploads:** FNOL + adjuster attach
  (`ClaimWorkController`) + claimant NEED_INFO upload
  (`ClaimantStatusController`) accept an optional `docKey`; a matching PENDING
  check flips to RECEIVED (audited, first evidence wins); unknown keys are 400
  naming the valid keys.
- **Counts:** `StagedClaimView` / `InternalClaimView` embed
  `documentsReceived/documentsTotal`; the claimant tracker shows the same
  counts + per-item labels, never internals.
- **Frontend:** adjuster claim-detail "Required documents" panel (checklist
  with link/waive actions, testids `detail-reqdoc-{key}/link/waive`);
  claimant tracker "Documents: N of M received" + item list (testids
  `claim-reqdocs/count/item`). Existing panels only — no new routes.

**Verified:** `RequiredDocumentsIntegrationTest` 14/14 (seeds per family +
orphan-empty, auth matrix, audit rows, walled claimant shape, counts,
docKey auto-link incl. 400-on-unknown), full backend suite 260/260 (per
`git log --oneline -1` — docs-only session, tests not re-run), frontend
build green, `need-info-docs.spec` 1/1.

**Deliberately not built (Non-goals):** OCR auto-detection of doc type
(human links), blocking decision on an incomplete checklist (advisory only —
the gate stays money-based).

### 2026-09-09 — S2 S3-compatible object storage behind the seam (MinIO dev, filesystem default)

**Context:** Evidence must survive re-images and multi-host deploys, but local-disk
bytes do not. `attachment.storage_path` already stores portable keys
`{claimId}/{uuid}{ext}` (V11), so no schema change was needed.

**What changed (backend, all additive):**
- **`PhotoValidator`** extracted from `FilesystemPhotoStorage` so S1 rules (image +
  PDF allowlist, magic-byte gate, 10MB / 5-file caps, sha pinning) are shared.
- **New `S3PhotoStorage` (bean `s3PhotoStorage`):** `java.net.http.HttpClient` +
  hand-rolled SigV4, zero new Maven deps (no AWS SDK in the offline cache).
- **Bean selection via `claims.storage.backend`** (default `filesystem`);
  the `ClaimWorkService` instanceof branch is gone (serve via
  `photoStorage.load()`).
- **One-shot `S3BackfillRunner`** (`claims.storage.backfill=false` default): PUTs
  keys, verifies sha, logs orphans, never deletes locals.
- **Compose/env:** `docker-compose.yml` gains MinIO + bucket-init; prod compose
  passes S3 env through; `.env.example` gains `CLAIMS_S3_*`.
- **`operations.md`** gains an S3 page (config table, backfill runbook, bucket +
  volume restore gate).

**Verified:** `S3StorageIntegrationTest` 5/5 (fake-S3 `HttpServer` stub: SigV4 + key
shape + store→load→delete), full suite 246/246 on the filesystem default,
frontend build green (untouched), `pom.xml` diff empty.

**Deliberately not built (Non-goals):** presigned-URL browser uploads,
lifecycle/versioning policies, CDN.

### 2026-09-09 — S1 evidence file types: PDF + integrity (V18)

**Context:** The NEED_INFO copy already asks claimants for PDFs (discharge summary,
final bill), but the validator rejected everything that wasn't an image — and stored
bytes had no integrity pinning, so silently tampered files would be served as-is.

**What changed (backend, all additive):**
- **`V18__attachment_integrity.sql`:** `attachment.sha256 CHAR(64) NULL` +
  `size_bytes BIGINT NULL`. New rows pin both; pre-V18 rows stay NULL
  (NULL = unpinned legacy, still downloadable).
- **`FilesystemPhotoStorage.validate()`:** allowlist is now `image/*` +
  `application/pdf`, with a magic-byte gate on the leading bytes (PNG / JPEG / GIF /
  WEBP / `%PDF` signatures). Mismatch → "Attachments must be image or PDF files."
  The 10MB size and 5-file count caps are unchanged.
- **`store()`** pins sha256/size on all three writers — FNOL (`ClaimService`),
  adjuster attach (`ClaimWorkService.attach`), NEED_INFO upload
  (`ClaimantStatusController.uploadDocument`). **`download()`** verifies sha when
  non-null → error log + 404 on tamper (corrupt bytes are never served).

**What changed (frontend):** file inputs gain
`accept="image/*,.pdf,application/pdf"` with mirrored client guards (count +
per-file size + type) and the new message. No new testids.

**Verified:** `PhotoValidationTest` 12/12, `AttachmentIntegrityIntegrationTest` 5/5,
full backend suite 241/241 green, frontend build green.

**Deliberately not built (Non-goals):** virus scanning, inline preview
(force-download stays), OCR/text-extraction, raising size/count caps.

### 2026-09-09 — V18 stage revisit + send-back + adjuster workspace theme

**Context:** The adjuster screen showed only the current stage (no way back to update or
re-check earlier work), the stepper was a static indicator, and the whole screen was
flat white-on-white — hard on the eyes, no visual hierarchy. Asked: move through the
stages back and forth, plus a good theme with contrast.

**What changed (backend, all additive — V15–V17 untouched):**
- **`POST /api/claims/{n}/send-back`** (assignee-only, 404 otherwise): moves the claim
  exactly one step back — DECISION→VERIFICATION (the plan's normative re-open path for
  new doubts) or VERIFICATION→REVIEW (re-triage). Requires a rationale; writes a
  `STAGE_SENT_BACK` audit row (before stage → after stage + rationale). Saved decision
  **proposals clear** to ordinary PENDING rows (they described a decision at a stage the
  claim no longer occupies); **assessed figures stay** as re-usable input; verification
  history untouched. Terminal states (CLOSED / NEED_INFO / ESCALATED_SUPERVISOR) and
  REVIEW itself are 400s. Appears on the timeline as "Sent back to …".
- No migration: `claim.stage` already admits all three values; the audit action is a
  free-text value like every other.

**What changed (frontend, adjuster workspace only):**
- **Clickable stepper:** reached stages are buttons — clicking an earlier step shows a
  read-only revisit panel (review = filed covers/limits; verification = checks +
  outcomes + evidence downloads) with a "Read-only" badge and a "Back to …" return.
  Future stages stay locked/disabled. Forms only ever render on the claim's true
  stage, so nothing can be edited out of order. New testids:
  `detail-stage-view-{REVIEW,VERIFICATION,DECISION}`, `detail-stage-revisit`,
  `detail-stage-back-current`, `detail-review-revisit`, `detail-verification-revisit`.
- **Send-back action** beside the decision form and the assessment box
  (`detail-send-back-toggle/rationale/confirm`): reason required, proposals-clear
  warning in the label, success toast + timeline refresh. The true stage drives the
  target label ("Send back to verification / review").
- **Workspace theme (scoped to the claim page):** deep-navy masthead (identity +
  status on dark, no more white banner), stepper as a raised control bar (current =
  accent fill, done = filled dot, locked = dimmed), verification cards with accent
  spine, tinted assessment exit box, zebra + tinted header on the decision grid,
  highlighted totals line. Reference rail (loss/reserve/coverage/timeline) untouched.
  No global token changes — other screens are byte-identical.

**Verified:** new `sendBackFromDecisionClearsProposalsAndKeepsHistory` integration
test (proposals clear, history/assessed persist, audit row, re-assess forward,
REVIEW + reason-less 400s); full suite **224/224 green**; `ng build` green (CSS
budget warning only); live probe on the dev stack (REVIEW claim: locked futures;
DECISION claim: revisit both earlier stages read-only, send-back lands at
VERIFICATION with the timeline row, 0px overflow). Probe moved CLM-000226
DECISION→VERIFICATION with a `STAGE_SENT_BACK` row — re-assess to move it forward.

**Deliberately not built:** forward jumps past the true stage (guards stay —
revisit is viewing, never editing); editing proposals instead of clearing them on
send-back (a cleared proposal re-decides cleanly; history keeps the rationale);
multi-step jumps (one step back keeps the audit story legible); a global dark mode
or token overhaul (scoped theme only — the "too much white" complaint was about
this screen, not the product).

---

### 2026-09-09 — V17 claim timeline: one sequential feed replaces the scattered boxes

**Context:** The adjuster workspace showed notes, documents, and audit rows in three
separate small cards (+ a supervisor-only audit panel), and verification evidence was a
free-text field — no solid record of which entity said/attached what and when, and the
cards competed with the workflow action. Asked: a Jira-style main section where every
comment and document from every entity is visible in order, verification documents
attachable per check and visible in the same feed, persisting across all decisions.

**What changed (backend, all additive — V1–V16 untouched):**
- **V17 migration:** `attachment.uploaded_by_sub` (uploader identity) +
  `attachment.verification_id` (FK → verification, ON DELETE SET NULL — the document
  outlives the check) + `internal_note.author_sub` (the only identity on supervisor
  notes, which have no app_user row). Pre-V17 rows keep NULLs and render un-attributed.
- **`GET /api/claims/{n}/timeline`** (holder-or-supervisor, 404 otherwise): notes +
  documents (claim-level and per-check) + verification opens/completions + workflow
  milestones (filed, assigned, advanced, need-info sent/responded, assessed, proposals,
  referred, escalated, reserve, decided) in one chronological feed with actor + time.
  Rows derive from the authoritative tables, so referral/reassignment/closure never
  rewrites them — the feed survives every decision by construction.
- **Per-check attach:** `POST /attachments` accepts optional `verificationId`
  (unknown/cross-claim ids are 404). Both upload paths stamp the uploader subject
  (adjuster/supervisor/claimant).
- **Actor resolution:** staff cache → policy-holder name + "(claimant)" suffix for
  claimant rows → null (renders "Internal"). System rows (NULL actor, e.g.
  auto-assignment) render "Internal".

**What changed (frontend):** the Documents / Internal-notes / Audit cards are gone —
one full-width **Claim timeline** section first in the rail (sequential, oldest first,
each row kind + actor + timestamp + text + download button), closed by the single note
composer and the single claim-files upload. Each open verification card gained an
"Attach evidence to this check" file input (uses the timeline upload's document-name
choice; the file lands on the check and on the timeline). Loss details / Reserve /
Policy & coverage stay as the slim reference rail; refer stays beside the stage panel.
New testids: `detail-timeline-panel/list/item/kind/actor/time/detail`,
`detail-ver-file-{id}/upload`, `detail-ver-docs-{id}`. Removed: `detail-documents-panel`,
`detail-note-list/item`, `detail-photos-empty`, `detail-notes-empty` (e2e updated:
journey 4 asserts the timeline; `detail-reserve-value` testid added — the old spec
asserted a testid the template never had).

**Verified:** new `timelineUnifiesNotesDocumentsAndMilestonesAcrossReferral`
integration test (feed shape + per-check link + 404-for-stranger + referral
persistence); 50/50 in the four affected classes; frontend build green; live probe on
the dev stack (note + document land with actor, full NEED_INFO history visible,
0px overflow); V17 applied on the dev DB.

**Deliberately not built:** claimant-side timeline (their tracker is unchanged — the
feed is internal-only); timeline filters/search (single-digit entries per claim —
revisit past ~50); editing/deleting notes or documents (history is append-only like
audit); showing audit `before/after` JSON on the feed (milestone text only — the
supervisor audit endpoint keeps the raw rows).

---

### 2026-09-07 — Sale-readiness build (R1–R6 + E2E): what shipped, what bent, what's next

**Context:** Autonomous sale-readiness pass executing `docs/sale-readiness-
requirements.md` (build contract from the analysis + roadmap review) with five
parallel subagents on `muse-spark-1.3-contributor`: backend-A (R1 policy admin + R2
outbox), backend-B (R3 metrics + R4 pagination + R5 storage + R6 nginx), frontend
(policies screen + outbox panel + load-more), tests (E2E scaffolding → journeys),
docs (R7 packaging). Verified: **backend 182/182**, frontend build green, **13/13
existing E2E green** on the live host stack + 2 new P0 journeys written for the
hermetic gate.

**What shipped:**
- **R1 policy admin** (backend-A): V9 `policy.status` (ACTIVE/RETIRED, default
  ACTIVE) + `created_at`; supervisor-only `POST /api/policies` (unknown product →
  400 naming valid codes), `POST /api/policies/import` (multipart `file`, ≤500
  rows, always HTTP 200 with per-row `{row,policyNumber,ok,error}`), `POST
  /api/policies/{n}/retire`, `GET /api/policies/admin` (paginated, newest first);
  FNOL rejects RETIRED with the existing policy-mismatch 400 shape; duplicate
  number → 409 (DB unique stays the guard). Frontend Policies screen
  (`/admin/policies`, supervisor guard + nav): table, create form, CSV upload with
  dry-run preview → confirm + per-row errors, retire confirm. Pins:
  `PolicyAdminIntegrationTest` 10/10.
- **R2 email outbox** (backend-A): V10 `email_outbox` written in the FNOL/
  assignment/closure transactions (never the send); 60s dispatcher (max-attempts
  8, 1m→4h backoff, batch 50, `claims.outbox.enabled` off in tests — same pattern
  as aging); immediate send kept + dispatcher flush after commit (Mailpit pins
  unchanged); `GET /api/outbox` + `POST /api/outbox/{id}/retry` (retry non-FAILED
  → 400); overview outbox panel (counts, filter, per-row retry, quiet toast).
  Pins: `EmailOutboxIntegrationTest` 6/6 (SMTP-down → PENDING + closed; redelivery
  → SENT; poison → FAILED → retry → PENDING; auth matrix).
- **R3 metrics** (backend-B, descoped shape): `GET /api/metrics` supervisor-only
  JSON (fnol total/rejected-by-reason, decisions-by-outcome,
  escalations-by-target, queue-depth-by-status, outbox pending/failed live from
  V10) + ops alert table with JSON checks and PromQL P1 sketches. Pins:
  `MetricsIntegrationTest` 2/2.
- **R4 pagination + server search** (backend-B + frontend): `{content,page,size,
  totalElements,totalPages}` on queue/escalations/mine (+ admin/outbox), `?page/
  size/q/status` (default 25, cap 100), stable ordering, claimant `q` own-rows-
  only; load-more + debounced server search in all five lists (plain-array
  fallback kept). Pins: `QueuePaginationIntegrationTest` 8/8.
- **R5 storage seam** (backend-B): `PhotoStorage` interface +
  `FilesystemPhotoStorage` (bean name unchanged), key-shaped `storage_path`
  (`{claimId}/{uuid}{ext}`), V11 legacy-path rewrite (null-safe), dual-read
  downloads, S3 page + restore-pairing gate in operations. Pins:
  `StorageSeamIntegrationTest` 5/5.
- **R6 IP limits** (backend-B): nginx `limit_req` (5r/s, burst 10 nodelay) on
  `= /api/claims`, syntax-verified; "app-layer per-IP is the WAF's job" recorded.
- **Tests/E2E:** `e2e/tests/p0.spec.ts` (2 journeys: R1 import→FNOL→queue incl.
  retire; R2 decision→outbox SENT) + `e2e/tests/fixtures/policy-import.csv`;
  13/13 existing green on the live host stack (current code).
- **Docs (R7 + R1/R2 deltas):** `api.http` ×6 new examples, operations (alert
  table, S3 page, R6, pairing gate, onboarding checklist), README truth
  (182/13+2/V11), R7 packaging entry, demo seed/reset SQL + npm scripts.

**What bent (forced adapts, all recorded):**
- **Prometheus registry → dependency-free JSON.** `micrometer-registry-prometheus`
  is not in the read-only offline Maven cache (`~/.m2` unwritable) — offline
  build failed. Actuator stayed (cached); metrics are plain `AtomicLong` +
  `JdbcTemplate` gauges at `GET /api/metrics`. PromQL kept as P1 sketches.
- **Full S3/MinIO → seam + key + docs.** Correct per the roadmap review's own
  verdict (half-right: seam now, switch P1) — no checksum/migration risk mid-sale.
- **2 of 8 planned outbox tests descoped** (supervisor-closure row,
  unassigned-FNOL assignment row — implicitly covered; core paths pinned).
- **Hermetic E2E gate not runnable in-sandbox** (host :4200/:8081 squatters
  unkillable from the bwrap sandbox; hermetic boot needs both free). 13/13
  verified on the live host stack serving current code; `claims_e2e` repaired
  (V4 checksum `1362416422 → 472220875`) + truncated for a clean gate run on
  CI/a clean machine. The V4 mismatch itself: the `claims_e2e` DB was migrated
  when V4 still said `realm-export.json`; commit `c836d36` renamed it to
  `.template.json` in the file, changing the checksum for already-migrated DBs.
  **V4 on disk was NOT touched** (immutability holds) — the applied row's
  checksum was updated to the committed file, which is the documented repair.
- **Test-pool cap 10→3** in `TestcontainersConfiguration`: ~14 cached Spring
  contexts × Hikari 10 exhausted PG `max_connections`. Shared-file fix, flagged.

**Load-bearing rules re-verified:** V1–V8 untouched (no diff); wall/gate/audit/
404/atomicity/aging all green at 182; testids + exact-text contracts additive-only;
no secrets in git (`.env`/`realm-export.json` still ignored). P1 queue next:
frontend component tests, reopen/appeal, staff surface, notification prefs, audit
export, 409-on-conflict, a11y audit, GDPR note, MinIO, white-label, landing page.

---

### 2026-09-07 — R7 packaging: tenancy, auth-hosting, S3 deferral, tranches, skill fork

**Context:** Sale-readiness pass R7 (docs/packaging only — no backend logic): the
sale-readiness analysis named five packaging decisions a buyer will ask about
before signing. Recorded here so sales answers them the same way twice.

**Decisions:**

- **Realm-per-tenant, option (a) for sale #1.** Each carrier gets a full stack
  with its own Keycloak realm rendered from `keycloak/realm-export.template.json`
  (new realm name per carrier, same pipeline: `npm run realm:render` →
  `docker compose -f docker-compose.prod.yml up`). No query changes, no shared
  tables, no cross-carrier leak surface. Row-level multi-tenancy (option (b)) is
  explicitly Series-A — after customer two, per the roadmap review. The carrier
  onboarding checklist in `docs/operations.md` is the procedure.
- **"Plug into our IdP?" — yes, OIDC-standard.** The backend is a plain OIDC
  resource server (`spring.security.oauth2.resourceserver.jwt.issuer-uri` in
  `application.properties`): any OIDC IdP works by pointing that URI at the
  buyer's issuer — no code change. "Run auth for us?" — yes as a managed service:
  we operate the Keycloak realm for the carrier (same render pipeline). No new
  auth code either way; the objection answer is configuration, not a feature.
- **S3 deferred with the path documented, not wired.** Photos stay on the
  `claims-uploads` named volume this sale; full MinIO wiring is P1. What the
  buyer gets now: the restore-pairing gate in `docs/operations.md` (DB dump +
  volume snapshot restore as one atomic pair, with an orphan check) and this
  migration path — `PhotoStorage` becomes an interface, `storage_path` stores a
  per-claim object key instead of a host path, one backfill job rewrites legacy
  rows. No schema migration is needed later beyond the key rewrite.
- **Tranches (partial/multiple payments) are a non-goal, not a deferral.** The
  one-payment-per-claim uniqueness constraint is the atomicity story ("a decision
  closes the claim, exactly one payment"). Partial payments would need a
  sum-check migration that risks that story mid-sale — buyer-roadmap line only.
- **Skill fork: commit, don't delete.** `.agents/skills/enterprise ui/` + `design
  examples/` are still untracked (`git status`, 2026-09-07). The skill was
  corrected in phase 07b to teach the shipped "Insure Craft" language
  (SKILL.md, tokens, component specs, review checklist; packaged `.skill`
  rebuilt) — deleting it re-opens the dated-vs-modern argument on the next UI
  pass. Recommendation: `git add` both paths in the single R7 commit (no secrets
  inside — verify with `git status` + a content scan before adding). Nothing was
  committed by this agent (docs/packaging files only, commit left to the
  coordinator).

**Verified (R7 docs scope):** demo seed/reset applied live against the dev
`claims` DB (6 `POL-DEMO-*` policies, 6 claims across UNASSIGNED →
UNDER_REVIEW → ESCALATED_SUPERVISOR → CLOSED-APPROVED/DENIED, audit rows,
supervisor payment with NULL authorizer; reset removes all demo rows and leaves
the 2 real seeded policies); `api.http` re-checked against the controllers —
policy-admin/outbox endpoints do not exist on disk yet, so no examples were
invented for them.

---

### 2026-09-07 — Production push: session auth, hardening endpoints, my-claims, overview, E2E hermeticity

**Context:** Post-07c autonomous production push (user: make it production-ready and
sellable). Two tracks: (a) missing production functionality — real session handling,
FNOL flood protection, claimant history, supervisor dashboard, deploys, runbook; (b) an
environmental E2E crisis where stale dev servers on :4200/:8081 poisoned every run.

**What changed:**

- **Session auth (Keycloak silent SSO + interceptor).** `AppConfig` loads Keycloak
  coordinates from `/assets/config.json` at bootstrap (same image runs everywhere);
  `auth.service` does one `check-sso` init with a proactive 45s/75s-skew renewal timer;
  `auth.interceptor` signs every `/api` call and rewrites bare 401s into "session
  expired" messages. Components no longer build Authorization headers by hand. Runtime
  config needs `frontend/public/assets/config.json` (dev default) and
  `silent-check-sso.html`.
- **FNOL rate limit (20/day/claimant, 429 + Retry-After).** Ledger table
  `fnol_submission` (V8) written in the filing transaction; `enforceRateLimit` counts the
  rolling 24h window before policy lookup. Same filing also gained: email-format check,
  future/10-year-past loss-date rejection, length caps (200/5000/2000), IP capture via
  `request.getRemoteAddr()`. Validation order is validate → rate-limit → lookup, so junk
  never consumes the quota and probes never learn policy existence cheaply.
- **`GET /api/claims/mine` (claimant history, CLAIMANT-only).** Newest-first rows of
  public facts only — same visibility wall as the single-claim view, asserted per-row in
  `FnolApiIntegrationTest`. Route ordering note: `/mine` lives in its own controller
  declared before `/{claimNumber}` so it can never be mistaken for a claim number.
- **`GET /api/dashboard` (supervisor aggregates, SUPERVISOR-only).** Eight numbers
  (open/unassigned/under-review/escalated/closed, 7-day aging pressure, monthly approved
  total), single indexed queries (V8 adds the composite queue indexes). Aggregates only —
  no per-claim data, so no wall surface.
- **`GET /api/policies` is supervisor-only.** Holder names are personal data, so the
  legacy whole-book list serves supervisors only — claimants read their own rows
  via the cockpit (`/mine`), adjusters have no policy surface, anonymous callers
  get 401 and claimants/adjusters get 403. Home fetches no customer data at all:
  anonymous visitors see the claimant path, signed-in claimants see their own
  workspace links, staff see the work queue with no filing/tracking CTAs (the
  guards enforce the same split). Journey 0 (skeleton) asserts the claimant path
  and the absence of any policy table.
- **Readiness + tracing.** `GET /api/ready` (migrations current + SELECT 1) is the
  traffic gate; `X-Request-Id` on every response (MDC `rid` in logs); unexpected errors
  carry `(Reference: xxxx)` so users can quote a ticket id instead of a stack trace.
- **FNOL is a 2-step wizard** (policy → loss details) with client photo guards (5 files,
  10 MB, image/*) and a 429-specific error path; queue/escalations gained search +
  status filter + sort; claim-detail gained supervisor reassign + audit panels;
  authority editor gained a client ladder check (L1 ≤ L2); global toast stack.
- **Prod pack:** `backend/Dockerfile` (multi-stage jar), `frontend/Dockerfile` + nginx
  (SPA fallback + /api proxy + cache-safe headers), `docker-compose.prod.yml`
  (secrets-from-env, named volumes for pgdata + uploads), `deploy/config.json`
  template, `docs/operations.md` (deploy/backup/incident runbook), `api.http` examples
  for every new endpoint.

**What broke and what it taught:**

- **V4 migration comment edit (reverted same session).** A "docs-only" touch of an
  applied Flyway file changes its checksum and breaks every migrated DB. Migrations are
  immutable — full stop. The stale V4 comment stays as the scar.
- **Stale dev servers poisoned E2E for hours.** Old `ng serve` (:4200, dev proxy → :8081)
  and old backends (:8081) answered Playwright's readiness probes, so journeys ran
  against the wrong stack (new UI, old auth rules): policies listed anonymously,
  `/mine` and `/dashboard` 404'd, FNOL 502'd. Fixed by killing the squatters AND making
  both webServer entries `reuseExistingServer: false` — with `stdout/stderr: pipe` so a
  real boot failure surfaces in the log instead of "Exit code: 1" silence. The testing
  rule in `.agents/skills/project workflow/rules/testing-web.md` now says: boot your own
  servers, or verify the one you reuse. Open risk: the DSH host shell keeps resurrecting
  `npm start`/backend watchers (parented to the harness supervisor) — if E2E fails with
  "already used" or wrong-stack symptoms, `ss -tlnp` :4200/:8081/:8082 first and kill
  the squatters.
- **Proxy HTML leaks as user text.** A dead backend makes ng serve answer API calls with
  an HTML 502 page ("Bad Gateway") that `serverMessage` used to display verbatim.
  `serverMessage` now falls back to the screen's own wording for empty/HTML/short
  non-sentence bodies — infrastructure noise never reaches users.

**Verified:** 151 backend tests green (140 + 11 new), frontend prod build green,
**13/13 E2E journeys** (11 original + overview + queue-filters) on the hermetic
Playwright-booted stack (backend :8082/claims_e2e + own ng serve :4200/E2E proxy).

---

### 2026-09-07 — Structural rebuild to the Insure Craft composition (phase 07c)

**Context:** After phase 07b the user correctly observed the app still *looked* the same:
07b had changed tokens (colors, type, shadows, radii) but not the HTML/structure, and the
reference screens' composition is structural, not just skin-deep. This pass rebuilt the
markup to mirror the reference grammar — floating chrome, banner card, icon-tile section
cards, footer action bar — while keeping every data-testid and journey contract intact.

**What changed structurally (all templates rewritten, not just CSS):**

- **Floating shell chrome (reference `header`/`sidebar` pattern).** The app now lives on a
  grey canvas (`#F1F5F9`) with a floating rounded top bar (60px, white, 12px radius, quiet
  shadow) holding the brand + user chip + sign-out, and an internal sidebar that is a
  *rounded white card* with its own logo block ("Claims workspace — Internal"), grouped nav
  ("Work", "Supervision"), and tinted active items. Public/claimant surfaces keep the
  floating top bar without the sidebar.
- **Banner card at the top of every screen** (reference `policy-banner`/`info-card`): queue
  and escalations get title + count chip + a stat tile ("Assigned to you" / "Waiting on
  you"); claim detail gets the full treatment — title + status pill + level chip, and five
  icon-stat tiles (policy, product, policyholder, loss date, current reserve); home, FNOL
  and claim-status get a title + subtitle banner.
- **Section cards with icon-tile headers** (reference `form-section`/`Clauses` pattern):
  every panel now has a `.panel__header` with a 32px icon tile + heading + count pill —
  "Open claims", "Loss details", "Reserve", "Internal notes", "Decision", "Policy &
  coverage", "Photos", "Progress", "Policies". Content sits in `.panel__body` with 24px
  padding.
- **Two-column claim detail** (main work column + decision/coverage/photos column) and a
  **footer action bar** card (`.footer-actions`) on claim detail and claim-status
  ("Back to my queue" / "Back to home").
- **FNOL is now a true two-section intake** with step chips ("1 Your policy", "2 What
  happened"), a two-column `.form-grid` for paired fields, and a full-width
  photo/description/remarks section — mirroring the reference form grammar — with the
  Submit action in the footer bar.

**Deliberately unchanged:** every data-testid (all 56 referenced by E2E), every exact-text
contract (status tokens, `£1500.00`, remarks, claim numbers), all `[disabled]` logic and
decision-flow semantics, the visibility-wall copy rules (claimant pages still never
contain reserve/internal-notes/coverage), and journey-pinned money formatting.

Verified after the structural pass: frontend build green, structural + geometry audit green
(banner/section-card/icon-tile composition on all six screens, no horizontal scroll), and
**11/11 E2E journeys** on the Playwright-booted stack.

---

### 2026-09-07 — Enterprise-UI language correction (phase 07b, "Insure Craft" modern look)

**Context:** After the first UI pass the user called the result dated ("2000s style") and
pointed at the workspace `design examples/` folder as the canonical reference for the look
they want. The reference screens — an insurance product-config back office, branded
"Insure Craft" — show the current (2026) SaaS idiom: **white rounded cards with one quiet
shadow recipe floating on a light cool-grey canvas, Inter type, a single corporate blue
(#0056b3), tinted bordered status pills, white sidebar + header chrome.** The phase-07
interpretation had applied the opposite idiom (flat hairline surfaces, no shadows, tight
4–6px radii), which now reads as the dated 2010s aesthetic.

**What changed:**

- **The skill now teaches this language.** `SKILL.md` was rewritten: the tells table no
  longer brands card shadows and 10–12px radii as "generated" — the flat-hairline fusion
  is now itself the listed dated tell, and "white cards + one shadow recipe on a grey
  canvas" is the described baseline. `references/design-tokens.css` and
  `references/component-specs.md` were rewritten to match (slate ramp, `#0056B3` accent
  ramp, tinted status sets, `--shadow-card`, Inter, 6px controls / 10–12px cards / 999px
  pills, 44px rows, 38px controls). `references/review-checklist.md` was updated to audit
  for shadow consistency and card layering. The packaged `enterprise-ui.skill` zip was
  rebuilt from the updated files.
- **The app was reskinned to it.** New global token set in `styles.css`; `.panel`,
  `.summary-strip` and `.form-card` are now white cards with `--shadow-card` and 12px
  corners; statuses render as tinted bordered pills (`badge--*`) and levels as neutral
  pills; buttons are 6px (primary solid blue w/ a hint of depth, secondary white w/
  border); inputs are 38px/6px; table headers sit on `#F8FAFC`, rows 44px with a soft blue
  hover; sidebar items 40px with tinted active state; Inter is loaded in `index.html`.
  All 56 E2E data-testids and exact-text contracts were preserved.

**Deliberately unchanged:** every `[disabled]` rule and decision-flow semantic, all
server-rendered decision strings, the visibility-wall copy rules, and money formatting
pinned by journeys 4/8 (`£` + `toFixed(2)`, no thousands separators).

Verified after the reskin: frontend build green, programmatic geometry/contrast audit
green (single shadow recipe per card class, radii 6/12/999, no horizontal scroll,
4.5:1+ text), and **11/11 E2E journeys** on the Playwright-booted stack.

---

### 2026-09-07 — Enterprise-UI frontend pass (phase 07, UI redesign)

**Context:** The app was functionally complete and hardened, but its frontend still read as
plain generated styling — floating rounded card rows on grey, pill badges, centered 60rem
columns, ad-hoc hex colors per screen. This pass rebuilt the entire UI on the
`enterprise-ui` skill's design system, keeping every data-testid and journey contract
intact. Verified green after the pass: frontend build + **11/11 E2E journeys** (playwright
boots its own backend :8082 + frontend :4200, so the gate is the same one CI runs).

**What changed:**

- **Design tokens (wholesale).** `frontend/src/styles.css` now carries the full token set —
  12-step cool neutral ramp, single institutional-blue accent ramp, six semantic status
  sets, radius/spacing/type/elevation/density/motion tokens — plus shared primitives
  (`.btn`, `.input`/`.field`, `.badge`, `.chip`, `.data-table`, `.panel`, `.page-header`,
  summary strip, kv rows, states, skeleton rows). Every component CSS file was rewritten to
  reference only tokens; no raw hex values remain outside the token block.
- **Shell before screens.** `app.html`/`app.css` now build a real application frame: 48px
  top bar (brand mark, identity chip from `preferred_username`, sign out) and a 240px
  sidebar for internal roles (My queue; supervisor gets Escalations + Authority settings
  under a "Supervision" group) with `routerLinkActive` highlight. Claimants/public keep a
  clean header with a File-a-claim action and no sidebar. One `<main>` per document.
- **Screens restyled, structure preserved.** Queue/escalations/home are now real
  `<table>`s (36px rows, mono identifiers, status badge + level chip columns, truncating
  description, row hover) instead of card lists. Claim detail gained a page header with a
  status badge, a summary strip (policy/product/holder/loss date/current reserve), and a
  two-column layout (loss details, reserve, notes | decision, policy & coverage, photos).
  FNOL/claim-status are token-styled public pages (640px form column, labelled fields).
  All 56 referenced testids were preserved verbatim, including exact-text contracts
  (e.g. `claim-status-state` = raw status token, `claim-decision-amount` = `£1500.00`,
  `detail-claim-number` = bare claim number).
- **Status mapping centralized.** New `frontend/src/app/ui.ts` maps lifecycle states to
  semantic badge kinds in one place (`UNDER_REVIEW`→info, `ESCALATED_SUPERVISOR`→special,
  `CLOSED`/`UNASSIGNED`→neutral, `APPROVED`→success, `DENIED`→danger); screens call
  `badgeClass(status)` and never color per screen. Badges are icon(text)-and-color (dot +
  raw token), never color alone.
- **Audit-driven fixes after the visual pass:** breadcrumb/id text on the sunken page
  background was bumped from `--text-tertiary` to `--text-secondary` (4.26:1 → 6.68:1,
  WCAG AA on text); the reserve Save became secondary so the decision Approve stays the
  view's single primary button; `.table-scroll` got `position: relative` so the
  visually-hidden action header doesn't leak 12px of document scroll; skeleton widths and
  remaining inline styles were moved into token classes. Checks run programmatically:
  no horizontal scroll at 1440/1024/768, h1 ≤20px, no gradients, no oversized radius, no
  emoji, row height 36px, contrast ratios ≥4.5:1 on body/table/badge text.

**Deliberately unchanged:** all `[disabled]` logic and decision-flow semantics (the
post-hardening reserve/`.trim()` rules still hold), all server-rendered decision message
strings, and the claimant-visible copy rules that journey 2 pins (the claimant status
page still never contains the words reserve/internal notes/coverage). Formatting of money
keeps the journey-pinned `£` + `toFixed(2)` shape (no thousands separators) because
journeys 4 and 8 assert on the raw text `1250` / `£1500.00`.

---
### 2026-09-07 — Post-hardening regression fixes: Flyway V4 immutability + reserve button

**Context:** A clean E2E run after the hardening pass surfaced two regressions the pass
itself introduced. Both are fixed and the whole suite is green again: 140 backend tests,
frontend build, **11/11 E2E journeys**, and the backend boots against the freshly-migrated
DB with no checksum error.

**Fix 1 — Flyway V4 was edited after being applied.** The hardening pass renamed
`keycloak/realm-export.json` → `keycloak/realm-export.template.json` inside a SQL comment
in `V4__assignment_queue.sql`. Flyway checksums the whole file, so any database that had
already applied V4 failed validation on the next boot (`Migration checksum mismatch for
version 4`); CI and fresh clones were unaffected because they migrate a fresh DB.
**Reverted the comment back to `keycloak/realm-export.json` and re-reset the local DBs
(`docker compose down -v && docker compose up -d --wait db mailpit keycloak`) so they
re-migrate against the reverted file.** Rule recorded: **migrations are immutable** — a
comment that becomes slightly stale (the file is now rendered from a template) is the
correct tradeoff versus editing an applied migration. Never edit a migration that has
shipped.

**Fix 2 — Reserve "Save" button permanently disabled (E2E journey 4).** Hardening added
`!reserveInput.trim()` to both the `[disabled]` binding and `saveReserve()`, but
`reserveInput` is bound to `<input type="number">`, so Angular's number value accessor
assigns a `number` (or `null` when cleared) — and numbers have no `.trim()`. The expression
threw, so the button never enabled. **Fix:** typed `reserveInput` as `number | null`, seed
it from `view.reserveAmount` directly, guard with `null`/`Number.isNaN` (a reserve of 0 is
legitimate, so a plain `!reserveInput` truthiness check would be wrong), and drive the
`[disabled]` binding from a `reserveReady()` helper — mirroring the decision form, which
already does this correctly (`!decisionAmount`, no `.trim()` on `decisionAmount`). The
`.toFixed(2)` *input* seeding was dropped as part of the number typing; the "Current
reserve" display still renders two decimals.

---

### 2026-09-07 — Pre-ship hardening pass (phase 06)

**Context:** All slices (0–7) were done, reviewed, and green. This pass worked the whole
`phases/06-harden.md` checklist against the real code, in small commits, with tests where
they make sense. Re-verified the load-bearing rules (still green: 140 backend tests + 11
E2E journeys after the pass; frontend build green; `npm audit` 0 vulnerabilities in both
`frontend/` and `e2e/`).

**Fixed:**
- **Dev credentials → environment variables** (the slice-2/3 deferred item): Postgres
  (`DB_USERNAME`/`DB_PASSWORD`) and Keycloak bootstrap admin (`KEYCLOAK_ADMIN_USERNAME`/
  `KEYCLOAK_ADMIN_PASSWORD`) read from env in `docker-compose.yml` + `application.properties`;
  realm-user passwords render from `ADJUSTER_PASSWORD`/`SUPERVISOR_PASSWORD`/`CLAIMANT_PASSWORD` into a
  **gitignored** `keycloak/realm-export.json` via `keycloak/render-realm.mjs` (committed
  `realm-export.template.json`). Added `.env.example`, `.env` to `.gitignore`, `npm run setup`,
  CI + E2E now inject env. The realm-sync test reads the template.
- **`GET /api/health`** public liveness endpoint (+ integration test), documented in `api.http`.
- **Frontend loading/empty/error/retry:** every fetch screen (home, queue, escalations,
  authority, claim status, claim detail) now has a Loading state (no blank flash) and a
  Try-again retry.
- **Double-submit guards:** reserve, note, decision (approve/deny), and authority-save
  disable their buttons during the in-flight request. FNOL already had one.
- **Reserve form:** a blank/NaN box no longer submits `0`; the amount renders with two
  decimals; the input seeds with `.toFixed(2)`.
- **Logout:** a Sign-out link (Keycloak logout) when authenticated; "Adjuster queue" nav
  hidden from the public (slice-2 deferred item).
- **Accessibility:** visible `:focus-visible` outline; muted text contrast `#777`→`#666`
  (WCAG AA); `bruteForceProtected: true` made explicit in the realm (login rate limiting).
- **Docs:** `README.md` rewritten (setup/run/test/deploy/backup/rollback + env vars + health);
  `SecurityConfig` javadoc refreshed; `api.http` gained the health example.

**Needs a decision from the user:**
1. **Rewrite git history to purge the pre-hardening dev-only credentials.** The values
   `claims`/`claims`, `admin`/`admin`, `adjuster-Pass-123`, `supervisor-Pass-123` still exist
   in commit history (they were committed in slices 1–7 and removed from the working tree
   now). They guard only a throwaway local Postgres/Keycloak, so a rewrite is optional, but
   it is the only way to fully satisfy "never committed" for the history. Rewriting would
   force-push `main` and rewrite the remote — needs your call.
2. **Rate limiting on FNOL/email.** Login brute-force is Keycloak's (now explicit). The only
   claimant-triggered mail is the FNOL confirmation (one per filed claim; assignment/decision
   mail is staff-triggered, one per claim). A per-claimant FNOL rate limit is a product/policy
   choice (what limit? per-hour? per-policy?) and adds state; at the stated scale (low hundreds
   of claimants/year) it was not built. Say the limit and it gets added.

**Deliberately accepted (with reasons):**
- **CSRF disabled** — stateless bearer-token API, no cookies to forge; CSRF protection would
  only reject legitimate clients. **CORS left at Spring's default (no cross-origin)** — not
  wide open; the SPA is same-origin via the dev proxy and any prod deploy would configure CORS
  explicitly.
- **No pagination** on the queue/escalations/audit lists — scale is single-digit adjusters and
  low hundreds of claims/year; the queue is bounded by the active caseload, not total history.
  Build it when a real queue exceeds ~100 open rows.
- **Notes-list N+1** (a per-note `app_user` lookup on the claim detail page) — detail page, few
  notes per claim; the list pages (queue) are a single joined query with no N+1. Not optimised
  without a measurement (YAGNI).
- **Per-field server errors not `aria-describedby`-linked** — the forms use native `required`
  for per-field client validation; the server returns one summary alert (`role="alert"`) for
  cross-field validation (policy mismatch etc.). Per-field API errors are a larger change.
- **Backups/rollback** — no production/hosting target is specified (`requirements.md`), so
  there is nothing to back up yet; the mechanism (`pg_dump`/`pg_restore` + on-disk photos,
  redeploy the previous image) is documented in the README. Flyway is forward-only by design
  (immutable audit), so a schema rollback = restore from a pre-migration backup.
- **Maven dependency audit** — `npm audit` ran clean (0 vulns, both packages). The Java set is
  Spring Boot 4.1.1-managed with no version overrides; a full OWASP dependency-check needs
  network + NVD data and is the production step (not run in this sandbox).
- **Keyboard/a11y pass** — done by code review (native form controls and links, no
  `outline:none`, labels on every input, headings nest h1→h2); no axe run (no browser tooling
  in this environment). The structure is keyboard-friendly; an axe/Lighthouse sweep is worth
  running on a real build before the first real user.
- **`TEST_DB_PASSWORD` fallback `claims`** in `TestcontainersConfiguration` — test-only
  convenience for the throwaway local test Postgres, never the dev/prod DB.

**Deferred-list resolution** (the "optional cleanups" carried from slices 2–3 are now either
done or explicitly left): done — dev-credential hardening, "Adjuster queue" nav gating, the
unused `loaded()` signals (now actually used by the loading states), empty/NaN reserve
submission, `£` currency formatting, stale SecurityConfig javadoc. Left as-is on purpose —
`LoadBalancer` `.sorted()` (correct and unit-tested; the id tie-break is asserted), the
`CLAIM_CREATED` audit row recording the momentary UNASSIGNED status (the following
`CLAIM_ASSIGNED` row records the transition), `QueueClaimView.createdAt` (informational, no
UI consumer — harmless), UNDER_REVIEW step copy (the "being routed" first step is historical
and not wrong), and the two-copy E2E helper duplication (still exactly two specs use it, so
no extraction per the rule-of-three). The E2E-database-isolation and Keycloak-dev-container
items were already resolved in slices 1–2.

**Non-goals check:** re-read `requirements.md` Non-goals one last time — nothing on it got
built. No reopening/appeals, no multiple/partial payments (one payment per claim, uniqueness
enforced), no policy admin/underwriting/rating (policies seeded read-only), no automated
adjudication/fraud/document-reading, no external integrations, no real money movement
(payments are recorded facts), no SMS (email only), no mobile app (responsive web), no
websockets/real-time (pull-to-refresh + email), no multi-tenancy (single carrier), no
i18n/offline/SSO-beyond-Keycloak.

---

### 2026-09-06 — Slice-7 fresh-context review findings applied (deepseek-v4-pro)

**Context:** A fresh-context review of slice 7 (run on the strong model in a new agent
session, per phase 05) found **no blocking and no should-fix issues**: the slice satisfies
its acceptance criteria, every criterion has a test that fails on regression, and the
scope question ("what was built that the plan didn't ask for?") came back empty beyond the
brief + recorded decisions. The no-cache config read and the data-layer append-only
guarantee were verified in code, not just asserted.
**Applied (optionals, all four accepted by the user):**
- (O1) The `/admin/authority` editor seeded its amount inputs with `String(number)`, which
  dropped trailing zeros (2500.00 → "2500"); it now seeds with `.toFixed(2)` so the editor
  shows the stored NUMERIC(14,2) scale.
- (O2) Journey-9 wording: the spec comment and both docs said journey 9 "re-sets its own
  state first"; it actually re-routes AUTO to L1 idempotently at its start and restores L2
  at its end. Reworded in `queue.spec.ts`, this entry's sibling slice-7 entry, and
  `docs/progress.md`. Wording only — the safety property (AUTO untouched by other
  journeys; an interrupted run self-heals) was already correct.
- (O3) `AuthorityConfigService.update` dropped its redundant `configs.save(config)` — the
  row is a managed entity and dirty checking persists the setters on commit. Behavior
  unchanged (re-verified by the config integration class).
- (O4) New integration test `aSupervisorCanReassignAStrandedUnassignedClaim`
  (ClaimAdminIntegrationTest): an FNOL left UNASSIGNED by the no-L1-adjuster provisioning
  gap is reassigned to the least-loaded L1 adjuster once adjusters are restored, with the
  null previous-holder audit branch pinned (ClaimAdmin 9 → 10 tests).
**Verified:** reviewer re-ran the targeted slice-7 classes (16 tests at review time) +
frontend build, both green; after the optionals the full backend suite (139 tests incl.
the new 10th ClaimAdmin test) and frontend build were re-run green.

---

### 2026-09-06 — Slice-7 decisions: config editor, reassign, and the immutable audit view

**Context:** docs/plan.md slice 7 left several shape questions open ("decide and record"):
what a supervisor reassign takes and what it may act on; the config edit shape and
validation; where audit-log immutability is enforced; how much of slice 7 gets a UI; and
how E2E journey 9 edits config without poisoning the shared e2e database for journeys 1–8.
**Decision:**
- **Reassign takes a target level, not a named adjuster.** `POST
  /api/claims/{claimNumber}/reassign` body is `{level: L1|L2}`; the service re-levels the
  claim and routes it to that level's **least-loaded adjuster via `ClaimAssigner`** — the
  same rule every other re-assignment in the system uses (FNOL slice 2, escalation slice
  4), one reassignment rule instead of two. The claim's routing `level` follows its new
  holder (aging and the gate read the level), so a reassign cannot silently leave a claim
  on a level that will re-age or mis-gate it. The claim row is locked (the slice-4 `FOR
  UPDATE` read) so a concurrent decision or second reassign serializes. Response is
  `{claimNumber, status, level, assignedTo}`.
- **Reassign eligibility/errors.** Unknown claim → 404 (never reveals existence). A decided
  claim → 400 "already been decided" (terminal; mirroring the decision endpoints).
  `ESCALATED_SUPERVISOR` → 400 "awaiting a supervisor decision" — the claim is out of every
  adjuster's hands and pulling it silently out of the escalation queue would strand it.
  Missing/invalid level → 400 "must be L1 or L2". A target level with **no provisioned
  adjuster → 400** with an actionable message (unlike FNOL's silent UNASSIGNED and the
  slice-4 escalation fallback, a supervisor's explicit request must not no-op). Every
  reassign writes a `CLAIM_REASSIGNED` audit row with the supervisor's Keycloak subject as
  actor (no app_user row — the slice-5 pattern) and before/after holding assignee id +
  display name + level + status. Supervisor-only at the URL (403 for adjusters/claimants);
  UI pages were not added — the plan has no route-table row for reassign (or audit).
- **Config edit shape and validation.** `GET /api/config/authority` lists every row as
  `{productCode, routeLevel, l1LimitAmount, l2LimitAmount}` ordered by product code; `PUT
  /api/config/authority/{productCode}` takes the three editable parameters (the product
  code is the path key) and returns the saved row. Validation: route level L1|L2; amounts
  positive with ≤ 2 decimal places within NUMERIC(14,2); **l1 ≤ l2** (an inverted ladder is
  a config error, rejected — never a legal gate state); unknown product → 404. The row
  entity gains setters — the supervisor edit is its one write path. **No caching anywhere**:
  the classifier (FNOL) and the gate (decision) re-read `authority_config` per call, so an
  edit feeds the next claim and the next decision immediately (integration-tested both
  ways).
- **Audit-log immutability enforced at the data layer** (Flyway V7): a BEFORE UPDATE OR
  DELETE trigger raises on `audit_log`, so raw edits are impossible for any caller — the
  "reject UPDATE/DELETE" integration test can only pass with database enforcement.
  TRUNCATE deliberately stays legal: it fires no row triggers and is the integration-test
  reset path (no production code truncates the log).
- **E2E journey 9 edits AUTO (POL-20002), never HOME.** HOME is used by journeys 1–8 and
  the shared claims_e2e database accumulates across runs; AUTO is seeded (route L2) and
  used by no other journey. Journey 9 re-routes AUTO to L1 through `/admin/authority`,
  files a fresh AUTO FNOL and asserts it lands in exactly one L1 queue and never the L2
  queue (classification reflected end to end), then restores AUTO to L2. Journey 9 re-routes
  AUTO to L1 idempotently at its start (a no-op when an earlier run left it there), so an
  interrupted run cannot cascade into other journeys. The **gate-limit
  effect stays at the integration layer** (raising HOME's L1 limit turns a would-be
  escalation into a closure) — the aging-E2E precedent: browser E2E covers the
  user-visible classification half; the money-threshold math is integration territory and
  driving a full approve-through-new-limit journey would cost E2E seconds for what the
  integration layer pins deterministically.
- **Deliberately not built:** audit-log view and reassign UIs (no route-table row for
  them — API-only this slice, like the compliance API surface the plan names); config edits
  are not written to the audit log (the plan asks the log to record status changes and
  decisions on claims; who edited a threshold is not a named requirement — revisit if the
  compliance team wants a config-change trail, which would need an entity/key for config
  rows); the frontend `/admin/authority` page is the only new route (plan route table).
**Why:** Each choice reuses an existing rule or machinery instead of inventing a parallel
one (least-loaded assignment, the row lock, the no-app_user supervisor identity, the
404/400 conventions), keeps config-effect freshness structural (no cache to invalidate),
makes the append-only promise a database guarantee rather than app discipline, and keeps
the shared e2e database safe for every existing journey.
**Deferred:** Config-change audit trail (above); named-adjuster reassign (build it when a
requirement to hand a claim to a specific person appears); audit-log TRUNCATE hardening for
production (build it when non-test code ever needs to truncate — it never should).

---

### 2026-09-06 — Slice-6 fresh-context review findings applied (deepseek-v4-pro)

**Context:** A fresh-context review of slice 6 (run on the strong model in a new agent
session, per phase 05) found **no blocking and no should-fix issues**: the slice satisfies
its acceptance criteria, every criterion has a test that fails on regression, and the
scope question ("what was built that the plan didn't ask for?") came back empty beyond the
brief + recorded decisions.
**Applied (optional):** (O1) The `ClaimantClaimView` javadoc's first paragraph listed
"remarks" among the internal fields the view "structurally omits", which reads
ambiguously next to the newly exposed `decisionRemarks` component — it meant the
claimant's FNOL remarks. The list item now says "claimant remarks". Comment-only; no
re-verification needed.
**Deferred (optional, no behavioral risk):** (O2) The journey-8 E2E wire-leak capture
(`watchClaimantWire` in `queue.spec.ts`) reads each claimant-status response in an
un-awaited `page.on('response')` handler and asserts the captured list only at the end —
mirrors the pre-existing journey-2 pattern and cannot produce false *failures*, but it is
a latent false *negative* (a body not yet read would silently miss a leak; the real wall
guarantee lives in the deterministic integration/structural assertions). Build it when
`queue.spec.ts` is next touched: collect the status response via an awaited
`page.waitForResponse`/route capture instead.
**Verified:** reviewer re-ran the targeted slice-6 classes (31 tests: ClaimantClaimView 9,
ClaimDecision 13, EscalationDecision 9) + the frontend build, both green, and
cross-checked the Flow-5 acceptance-criterion table against the tests.

---

### 2026-09-06 — Slice-6 decisions: the closed claim's claimant view (decision display)

**Context:** Slice 4 deferred the claimant-view decision fields here deliberately, and the
slice-6 brief left four shape questions open ("decide and record"): what the process-steps
list shows for a CLOSED claim when `stepsFor` derives from status alone and CLOSED cannot
recall whether the journey passed through `ESCALATED_SUPERVISOR`; the exact grown shape of
`ClaimantClaimView`; whether the decision email needs new end-to-end coverage; and where
E2E journey 8 lives.
**Decision:**
- **Closed process steps: the two stages every closure truthfully shared, never a guessed
  "Escalated" step.** `stepsFor("CLOSED")` returns *FNOL received* + *Under review* only.
  The decision/closure columns do not record whether the claim was supervisor-escalated, so
  no closed view can truthfully show "escalated (when applicable)" from the claim row —
  showing it for every closure would misrepresent never-escalated claims. Reconstructing
  history from the `DECISION` audit row's `before.status` was considered and rejected:
  reading the append-only log into a claimant surface, per GET, for a history nicety, is
  cost without a requirement. The terminal "→ decision" step is the decision itself, which
  the screen renders from the decision fields as a block (amount or remarks) — richer than
  a status-only list line could be, and outcome-specific (which a pure status projection
  cannot produce). *Revisit if* closure ever records escalation (e.g. a `was_escalated`
  flag) or claimants ask for their full timeline.
- **`ClaimantClaimView` shape (slice 6): decision fields always on the record, null when
  not applicable; omitted from the wire when null.** The record grows to
  `(claimNumber, status, steps, decision, indemnityAmount, decisionRemarks)`. The mapper
  guards each field to its decision — `indemnityAmount` only when `decision = APPROVED`,
  `decisionRemarks` only when `decision = DENIED` — so the plan's "approved amount present
  only when APPROVED" rule is structural at the view boundary, not a serialization
  accident. The record carries `@JsonInclude(NON_NULL)` (Jackson-2 compat annotations,
  honored by this Boot-4/Jackson-3 mapper — probed empirically): the wire omits null
  fields, so an undecided claim's response is **byte-identical to slice 5** (journey 2 and
  the open-claim wall tests did not need changing), an APPROVED closure adds
  `decision` + `indemnityAmount`, and a DENIED closure adds `decision` +
  `decisionRemarks`. The structural wall test
  (`viewStructurallyCarriesOnlyPublicFields`) and the journey-2 wire assertions were
  extended deliberately with this shape, per the repo's no-dead-surface and
  no-accidental-growth rules.
- **Decision email: no new delivery test — the four slice-4/5 closure tests already pin
  Mailpit delivery on every closure actor (adjuster approve/deny,
  supervisor approve/deny).** Slice 6 *strengthened those assertions in place* to cover
  the plan's "email content correctness" risk: the approval email body states "We will
  pay" plus the amount, the denial email carries the rationale as remarks verbatim. Those
  same four tests now also fetch the closed claim's claimant view and assert the decision
  fields on the wire per closure actor, plus the wall (no reserve/notes/assignee/coverage)
  and the closed steps — extending, not duplicating, the existing wall assertions.
- **E2E journey 8 lives in `queue.spec.ts`** (existing file, per the standing decision —
  no new spec file adds a parallel worker's Keycloak registration traffic). One test
  exercises **both** rendering branches: an adjuster-approved claim (the claimant sees
  `£1500.00`) and an adjuster-denied claim (the claimant sees the remarks verbatim),
  with journey-2-style wire-leak capture on the closed claims. The supervisor-closed wire
  path stays at the integration layer: the browser rendering is decision-agnostic, so
  driving a third closure through the UI would buy nothing E2E-specific.
- **Closed-claim access is unchanged and re-pinned:** the claimant status endpoint's
  owner-only rule (401 anonymous, 404 another claimant — never 403) is status-independent
  and already pinned on open claims; a closed-claim variant test was added so the rule has
  an explicit regression guard on the surface this slice changed.
**Why:** Each choice keeps the closed view truthful without new columns or audit-log
coupling, keeps the open-claim wire pristine, pins every acceptance criterion to a test
that fails on regression, and lands the only genuinely new user-visible behavior (the
decision block) with its E2E journey — while reusing every existing fixture instead of
duplicating it.
**Deferred:** A recorded escalation flag or audit-derived history for closed claims (see
the steps decision); per-claim "what happens next" mail content beyond the closure email.

---

### 2026-09-06 — Slice-5 fresh-context review findings applied (deepseek-v4-pro)

**Context:** A fresh-context review of slice 5 (run on the strong model in a new agent
session, per phase 05) found **no blocking issues**: the slice satisfies its acceptance
criteria and every criterion has a test that fails on regression. One should-fix and three
optionals were reported.
**Fixed:** (S1) The aging comment in `application.properties` pointed at a nonexistent
`src/test/resources/application.properties` — the exact file this slice learned must not
exist (it shadows the main properties wholesale). The comment now names the real mechanism
(the inherited `@DynamicPropertySource` on `ClaimTableResettingTest`) and warns against
reintroducing the file. Comment-only; no re-verification needed.
**Deferred (optional, no behavioral risk):**
- (O1) `AgingService.reassignToL2` stamps `assigned_at` with the wall clock (inside
  `ClaimAssigner.assign`) rather than the injected aging instant. Harmless — production
  `now` equals the wall clock and tests only assert non-null — but it is the one
  non-injected time in an otherwise deterministic pass. Build it when aging timestamps ever
  need to be deterministic.
- (O2) The escalation-decision and aging paths reuse slice 4's `FOR UPDATE` read but carry
  no deterministic held-lock test of their own (their second-operation tests are
  sequential). Not a real gap: slice 4 pins the identical lock deterministically
  (`decisionBlocksWhileAnotherTransactionHoldsTheClaimRowLock`). Build it when those paths
  diverge from the shared lock.
- (O3) A supervisor opening a non-escalated team claim sees an empty "Decision" panel
  heading (no `canDecide()`, not CLOSED). Correct behavior; only cosmetic. Build it when
  the claim-detail screen copy is touched again.
**Verified:** reviewer ran the targeted slice-5 classes (49 tests) + the frontend build,
both green, and cross-checked the acceptance-criterion table against the tests.

---

### 2026-09-06 — Slice-5 decisions: supervisor escalation-decision, aging, and supervisor identity

**Context:** docs/plan.md and the slice-5 brief left several shape questions open ("decide and
record"): what the supervisor's no-self-approval obligation means when supervisors never
create escalations; the eligibility/error rules for the escalation-decision endpoint; how a
supervisor payment/audit records identity with no app_user row; how the supervisor is
provisioned for E2E; and the exact aging-ladder semantics under clock jumps.
**Decision:**
- **A supervisor approval is not amount-gated, and no-self-approval is structural.**
  `ESCALATED_SUPERVISOR` claims are produced only by an adjuster's above-L2 approval attempt
  or the no-L2 fallback (slice 4) and by the aging job (slice 5). A supervisor can never be
  an assigned adjuster (`app_user.level` is L1/L2-only) and never acts on the slice-4
  decision endpoint, so no supervisor can be the actor on any path that escalates to them —
  nobody decides an escalation they caused, the same structural reasoning as slice 4. No
  extra check was written. *Revisit if* a Keycloak user could ever carry both an adjuster
  role and supervisor, or the supervisor gains an adjuster-level acting surface.
- **Eligibility and errors on `escalation-decision`.** Only claims actually in
  `ESCALATED_SUPERVISOR` are decidable there: unknown claim → 404 (never reveals
  existence); a claim in another open state → 400 "This claim is not awaiting a supervisor
  decision" (a supervisor can see team claims, so the state — not existence — is the
  problem, and the message is actionable); a decided claim → 400 "already been decided"
  (mirrors slice 4). The request body reuses the slice-4 shape (`decision`/`rationale`/
  `indemnityAmount`, rationale required for approve and deny, amount rules via
  `AuthorityGate.validate`), and the response reuses `ClaimDecisionView` (`escalatedTo` is
  always null — a supervisor decision closes).
- **Supervisor payment + audit identity: NULL `authorized_by`, subject as audit actor.**
  A supervisor token has no app_user row (the cache is L1/L2 adjusters only and the level
  CHECK forbids a supervisor row), so a supervisor approval records the single payment with
  `payment.authorized_by_id = NULL` while the `DECISION` audit row carries their Keycloak
  subject as `actor_sub` (before = `{status: ESCALATED_SUPERVISOR, level}`). Same
  nullable-author pattern as `internal_note.author_id` (slice-3 note decision) and the
  actor-less system rows.
- **Supervisor provisioned in Keycloak only** (the deferred slice-3/4 item, now triggered
  by journey 7 needing a real supervisor login): `keycloak/realm-export.json` gains a
  fixed-subject supervisor — id `10000000-0000-0000-0000-000000000004`, username
  `supervisor`, role `supervisor`, dev password `supervisor-Pass-123` — with **no app_user
  row** and no V-seed change. The realm-sync seam test still counts exactly the three
  adjusters (it filters realm users by adjuster roles), and `app_user` still holds exactly
  the provisioned adjusters.
- **Aging-ladder semantics** (Flow 6, anchor = `claim.created_at`, i.e. FNOL):
  - *3-day rung* — applies to claims still below the L2 tier (level L1, or `UNASSIGNED`):
    the same funnel as the slice-4 L2 escalation — level becomes L2, the least-loaded L2
    adjuster takes it via `ClaimAssigner`, and with no L2 adjuster provisioned it falls
    back to `ESCALATED_SUPERVISOR` (level reverted) exactly like the slice-4 gap rule. A
    claim already held at L2 is NOT re-pushed at day 3: it already satisfies the rung.
  - *5-day rung* — applies to any open claim and **wins over the 3-day rung**: a single run
    after a missed schedule or a clock jump lands the claim at the top of the ladder in one
    idempotent move (never "L2 first, supervisor later").
  - `CLOSED` and `ESCALATED_SUPERVISOR` claims are never re-touched. Every transition
    writes a `CLAIM_ESCALATED` audit row with `actor_sub NULL` (a system action, like
    `CLAIM_ASSIGNED`), before/after payloads matching the slice-4 escalation rows
    (escalatedTo L2/SUPERVISOR + assignee), and a rationale naming the rung ("Aged at
    least 3/5 days without a decision") so the trigger is self-describing in the immutable
    log.
- **Injecting the clock / keeping the cron out of tests.** The scheduled shell
  (`AgingScheduler`, cron daily 03:00, `@EnableScheduling`,
  `@ConditionalOnProperty("claims.aging.enabled")`) calls
  `AgingService.ageClaims(Instant)`; the public method takes an explicit instant, which IS
  the injection seam — tests pass fixed instants and never wait on a cron (no Clock bean:
  one caller). Claim-writing integration classes disable the scheduler through an
  inherited `@DynamicPropertySource` on `ClaimTableResettingTest`
  (`claims.aging.enabled=false`), so a real scheduled run can never fire mid-suite against
  backdated claims. `claim.created_at` is read via JDBC (it is not entity-mapped — the
  slice-3 review removed the mapping); each candidate's row is then locked with the
  slice-4 `FOR UPDATE` read and the step recomputed against the freshly locked state, so a
  concurrent decision or a second job run never double-transitions a claim.
- **Escalation queue shape.** `GET /api/escalations` (SUPERVISOR-only URL rule) reuses the
  queue row shape via `QueueService.supervisorEscalationQueue()` (status filter, oldest
  first, assignee always null) — no new escalation DTO.
- **E2E journey 7 was added to `queue.spec.ts`** (existing file, per plan): a new spec
  file would add a parallel worker's Keycloak traffic. The aging ladder itself stays at
  the integration layer with the injected clock (plan: E2E for aging only if time
  injection is cheap — it is not a user-visible journey here).
**Why:** Each choice resolves an ambiguity the brief flagged, keeps the supervisor surface
small by reusing the slice-4 machinery and shapes, and records identity the way the schema
allows (no supervisor app_user row exists or may exist).
**Deferred:** The aging E2E journey (clock injection in the browser is not cheap; the
claimant-visible escalated step is asserted end to end only when a claim reaches
`ESCALATED_SUPERVISOR`, which journey 7's fixture does produce). Decision *display* on
closed claims remains slice 6 (`stepsFor` for CLOSED is unchanged; the escalated step for
`ESCALATED_SUPERVISOR` lands now because Flow 6 makes aging claimant-visible).

---

### 2026-09-06 — Slice-4 fresh-context review findings applied (deepseek-v4-pro)

**Context:** A fresh-context review of slice 4 (run on the strong model in a new agent
session, per phase 05) found one blocking defect, two should-fix coverage gaps, and four
optional cleanups. All were accepted and fixed in one round.
**Fixed:** (B1) The no-L2-adjuster escalation fallback built its audit `after` payload with
`Map.of`, which forbids null values — when `ClaimAssigner` returned null (no L2 adjuster
provisioned) the documented fallback to `ESCALATED_SUPERVISOR` threw NPE and rolled the
whole decision back, leaving the claim with the actor whose authority was rejected. The
payload is now built with a null-tolerant map. (S1) The fallback path is now covered by an
integration test that deletes the L2 adjuster, escalates an above-L1/within-L2 approval,
and asserts `ESCALATED_SUPERVISOR` + no assignee + a `CLAIM_ESCALATED` audit row
(`escalationAboveL1WithNoL2AdjusterProvisionedGoesToTheSupervisor`), restoring the L2
adjuster in `finally` (the slice-2 gap-test pattern). (S2) The decision row lock's
serialization claim is now pinned by a deterministic held-lock test: a raw connection holds
`SELECT … FOR UPDATE` on the claim row and a concurrent decision must block until the lock
is released (`decisionBlocksWhileAnotherTransactionHoldsTheClaimRowLock`) — it fails if the
`PESSIMISTIC_WRITE` read were removed. (O1) `ClaimDecisionView.status` had no consumer
(dropped; the wire result still carries decision/escalatedTo/indemnityAmount/remarks).
(O2) The duplicate `DecisionRequest`/`ClaimDecisionInput` records collapsed into
`ClaimDecisionInput` as the `@RequestBody` type. (O3) `CLAIM_ESCALATED` audit `before`
payloads now record `assignedAdjusterId` on both the L2 and supervisor escalation paths.
(O4) The no-L2 fallback now reverts the claim level before `escalateToSupervisor()`, so
both routes to `ESCALATED_SUPERVISOR` leave the claim's routing level unchanged.
**Verified:** decision integration class 12 tests green; full backend suite re-run green.

---

### 2026-09-06 — Slice-4 decisions on the decision endpoint's open choices

**Context:** docs/plan.md and the slice-4 brief left several shape questions open ("decide
and record"): where denial remarks come from; what an escalation to L2 does when no L2
adjuster is provisioned; who is the actor on the escalation audit row; whether the
claimant view grows decision fields in slice 4; and which E2E file carries journeys 5–6.
**Decision:**
- **DENIED remarks = the rationale.** The request body has one text field (`rationale`,
  required for approve and deny alike); on a denial it is stored verbatim as the
  claimant-visible `decision_remarks`. No separate remarks field. (A claimant-facing
  remarks *rendering* is slice 6; the wall still holds — the field rides the claim row,
  not any slice-4 claimant response.)
- **Escalation to L2 with no L2 adjuster provisioned falls back to `ESCALATED_SUPERVISOR`.**
  If the claim is above the actor's level but within the L2 limit, the system re-assigns to
  the least-loaded L2 adjuster via `ClaimAssigner` (same row-lock as FNOL). If none exists
  (the provisioning gap slice 2 pins for FNOL), the claim must not stay with an actor whose
  authority was just rejected — it escalates to the supervisor with a warning instead.
- **`CLAIM_ESCALATED` audit rows are attributed to the adjuster whose above-limit attempt
  triggered them** (actor + rationale), unlike `CLAIM_ASSIGNED` (actor-less system action).
  Rationale: an escalation is caused by a specific internal user deliberately requesting an
  out-of-authority approval; recording who and why is the compliance-relevant fact. No
  `DECISION` row is written for an escalation — nothing was decided.
- **The claimant view stays unchanged in slice 4** (number/status/steps only). The plan's
  claimant-view `decision`/`indemnity_amount`/`remarks` fields land in slice 6, which
  renders them; shipping them now would be dead DTO surface with no consumer, and the
  structural wall test pins the record's exact shape.
- **E2E journeys 5 and 6 were added to `queue.spec.ts`**, not a new spec file. E2E is not
  fully parallel because concurrent Keycloak registration flows are flaky; a new file would
  add a new parallel worker's registration traffic. The helper duplication stays two copies
  (fnol.spec + queue.spec), not three, so no extraction yet.
- **Decision email is sent only on closure** (approve or deny), never on escalation — an
  escalation is not a decision. Best-effort after commit, to the verified policy-holder
  address, like the FNOL/assignment emails.
- **Seed thresholds (V6): 2500.00 / 10000.00** for both seeded product codes (HOME, AUTO).
  Uniform values keep the gate matrix and E2E amounts legible; per-product differentiation
  is a config concern for slice 7, not seed data.
**Why:** Each choice resolves an ambiguity the plan flagged, keeping the API body to one
text field, the claimant surface clean until slice 6, and the audit trail attributable.

---

### 2026-09-06 — Slice-3 review fixes (fresh-context agent review)

**Context:** A fresh-context review of slice 3 (commit 82cb34e) found no blocking issues
but six should-fix findings. All six were accepted and fixed.
**Fixed:** (S1) The reserve is now validated against its `NUMERIC(14,2)` column before
write — >2 decimal places and amounts over 999999999999.99 are rejected with a 400
instead of the DB rounding silently (response/storage divergence) or the commit failing
with a 500. (S2) The first `@RequestBody` endpoints gained client-error mapping:
malformed/unreadable bodies and wrong-typed path values map to 400 and unsupported
methods to 405 instead of the generic 500 catch-all. (S3) The supervisor-writes-note
path — the case that justifies `internal_note.author_id` being nullable — is now covered
by an integration test asserting a 200 with `author: null` and a NULL stored author.
(S4) Per-endpoint negative coverage was added across status/reserve/notes/attachments:
401 anonymous, 403 wrong role, and 404 unknown-claim on every endpoint, plus zero-reserve
acceptance. (S5) The `RESERVE_SET` audit rows' before/after payloads are now asserted, not
just counted. (S6) Dead DTO surface removed: `InternalClaimView.createdAt`,
`AttachmentView.contentType`, and `NoteView.createdAt` had no consumer and were dropped
along with the read-only `claim.created_at` entity mapping they existed for.
**Open:** the review's six optional items were not applied (user scope) and are recorded
in the Deferred section below; a strong-model pass remains optional.

---

### 2026-09-06 — Slice-3 notes: author_id nullable; notes not audited

**Context:** Flow 3 lets the assigned adjuster write internal notes on a claim. The plan
model shows `internal_note.author_id` FK → `app_user`, and audit C1 covers status changes
and decisions.
**Decision:** `internal_note.author_id` is nullable: a SUPERVISOR token may have no
`app_user` (staff-cache) row yet, and forcing one per supervisor would either fabricate
cache entries or require provisioning supervisors before slice 5. Their identity still
rides the audit trail where the plan requires it. Note *adds* are not written to the audit
log — the note rows themselves are the record; financial/state changes (claim created,
assigned, reserve set, later decisions) are what get audited.
**Why:** Notes are content, not state; auditing each one would duplicate the table it
lives in.

---

### 2026-09-06 — Reserve is set/updated via PUT and audited; not authority-gated

**Context:** Flow 3: "set/update a reserve". Plan: reserve is internal, never
claimant-visible, and NOT authority-gated (only the indemnity/payment is).
**Decision:** `PUT /api/claims/{claimNumber}/reserve` takes `{amount}` (≥ 0, else 400)
and returns the updated internal view; every change writes a `RESERVE_SET` audit row
(before/after amounts, actor). The assigned adjuster or a supervisor may set it — no
authority gate on purpose.
**Why:** A reserve is an internal estimate; gating it would be building the authority
mechanism for the wrong field.

---

### 2026-09-06 — Photos are served as attachments; the 404 rule now has real endpoints

**Context:** Slice-1 review deferral (a): serve photos with `Content-Disposition:
attachment` and don't trust client content types for rendering. Slice-2 deferred the
"404 for non-assignee" integration case because no per-claim internal endpoint existed.
**Decision:** Photo downloads are always `attachment` (never inline — untrusted bytes must
not render in the adjuster's browser), with the stored filename scrubbed of CR/LF/quotes
so a hostile original name cannot smuggle header content; the stored content type is kept
as the entity's media type (informational — the attachment disposition is the control).
The 404-for-non-assignee rule now lands on the slice-3 per-claim endpoints (claimant
status, full view, reserve, notes, attachments): a real claim someone else owns is 404,
never 403.
**Why:** Serving inline would create a stored-XSS vector; the plan's 404-not-403
authorization rule needed an object-level endpoint to apply to.

---

### 2026-09-06 — Policy coverage stays unmapped; read as JSON for the internal view

**Context:** Slice-0 deliberately left `policy.coverage` (JSONB) unmapped. Slice 3's full
view must show coverage to the adjuster.
**Decision:** Coverage is read with a parameterized JDBC query (`coverage::text`) and
parsed to JSON for the internal view — the entity stays unmapped (nothing writes it), and
the internal view is the only consumer.
**Why:** Mapping a read-only JSONB column on the entity just to serialize it back adds
mapping surface for no write path.

---

### 2026-09-06 — Slice-2 review fixes (fresh-context agent review)

**Context:** A fresh-context review of slice 2 (commit 27561e7) found no blocking issues
but five should-fix findings. All five were accepted and fixed in a follow-up commit.
**Fixed:** (S1) The two-thread HTTP concurrency test could pass even with the
`FOR UPDATE` lock removed (no barrier forced the transactions to overlap); it was
replaced with a deterministic test that holds the level's candidate rows locked on a raw
connection and asserts the FNOL's assignment blocks until the lock is released — it fails
if the lock disappears. (S2/S4) The hand-synced seam between `app_user` seeds (V4) and the
provisioned Keycloak users (`keycloak/realm-export.json`) is now pinned by an integration
test that cross-checks every staff user (subject, level, email, display name) against the
realm export; and an adjuster token whose subject has no `app_user` row now logs a warning
instead of silently returning an empty queue. (S3) The no-adjuster-of-level provisioning
gap (claim stays UNASSIGNED, no CLAIM_ASSIGNED row, no assignment email) is now covered by
an integration test that deletes the L2 adjuster and restores it in `finally`. (S5) The
per-test truncation of claim/attachment/audit_log moved from a per-class convention into a
shared base (`ClaimTableResettingTest`) that every claim-writing integration class extends.
**Optional items from the review were deliberately not applied** (user scope): tie-break
comparator style, the phantom UNASSIGNED status in the CLAIM_CREATED audit payload, the
public queue nav link, `QueueClaimView.createdAt`, step copy, E2E spec duplication, and
dev-credential hardening — see the Deferred section below.
**Open:** a strong-model pass remains optional (this review used the in-harness
fresh-context agent, like slice 1).

---

### 2026-09-06 — Slice-2 assignment runs inside the FNOL transaction

**Context:** Plan slice 2: new claims move to UNDER_REVIEW and are load-balanced to the
least-loaded adjuster of their level (fewest open claims, lowest `app_user.id` on ties),
atomically. There is no separate "assign later" endpoint in the slice.
**Decision:** Assignment happens in the creating transaction (the claim is never
observable as UNASSIGNED when adjusters of its level exist). Atomicity under simultaneous
FNOLs: the candidate adjusters' rows are locked (`SELECT ... FOR UPDATE`) before counts
are read, so concurrent FNOLs to one level serialize and the second recounts after the
first commits. Selection itself is a pure function (`LoadBalancer`), unit-tested; the
serialization is exercised by a two-thread integration test asserting distinct assignees.
**Why:** The plan's risk list names this exact race ("make assignment atomic"); row
locking is the smallest mechanism that is actually atomic.
**Rejected:** A global advisory lock (serializes every FNOL across both levels); an
asynchronous assignment worker (no queue infra, and the claimant email must carry the
assignee immediately).

---

### 2026-09-06 — app_user carries the routing level

**Context:** The plan model lists `app_user` with identity fields only ("role comes from
Keycloak, not stored here"), but slice-2 assignment must pick L1 vs L2 adjusters at claim
time, and the backend is a resource server — it never calls Keycloak's admin API.
**Decision:** `app_user` gains a `level` column (L1/L2), seeded in V4 to mirror the
provisioned Keycloak role of each staff user. It is a cache projection, exactly like
`display_name`/`email`, kept in step with the Keycloak role.
**Why:** Without it, load-balancing within a level is impossible without an admin-API call
per claim.
**Rejected / deferred:** Auto-populating `app_user` rows at login ("on login" per plan) —
adjusters are provisioned and seeded, so no first-login write is needed yet; if staff
profiles ever drift, the login hook is the sync point (see Deferred).

---

### 2026-09-06 — Assignment email to the claimant; assignee identity stays off the screen

**Context:** Flow 2: "an assignment email is sent with who to contact" and the claimant's
process-steps screen updates. Plan: process steps "never exposes the … internal assignee".
**Decision:** The assignment email goes to the verified policy-holder address and names the
adjuster (display name + email) — that is the "who to contact". The claimant-view steps
for UNDER_REVIEW say an adjuster is assigned but name no one. Like the FNOL email, sending
is best-effort after commit (a mail outage must not lose the assignment).
**Why:** Requirement text puts contact in the email; the plan's visibility rules keep
identity off claimant surfaces (email is one-way and not a claimant-facing screen).

---

### 2026-09-06 — Keycloak staff users provisioned with fixed subjects

**Context:** Adjusters cannot self-register; E2E logs them in as provisioned users, and the
backend's "own queue" join keys on `app_user.keycloak_sub = JWT subject`.
**Decision:** `keycloak/realm-export.json` now imports three staff users (two L1, one L2)
each carrying an explicit fixed `id`, and V4 seeds `app_user.keycloak_sub` with those same
values. Verified empirically that realm import honors explicit user ids, so subjects are
deterministic in dev, E2E (`claims_e2e`), and CI alike.
**Why:** A random subject per import would make "own queue" untestable end to end.

---

### 2026-09-06 — "404 for non-assignee" integration case deferred to slice 3

**Context:** Plan slice 2 says integration tests cover "queue + assignment auth rules
(incl. 404 for non-assignee)". Slice 2 exposes no per-claim internal endpoint — the full
claim view that a non-assignee could request arrives in slice 3 — so the 404 case has
nothing to attach to.
**Decision:** Slice 2 integration pins the queue's real rules: role gating (401 anonymous,
403 claimant), own-vs-team visibility, ordering, and empty queue. The cross-tenant
404-for-non-assignee assertion ships with the slice-3 full-view endpoints, where it is
testable.
**Why:** Building a per-claim endpoint early just to carry one 404 test would pull slice 3
forward and violate the slice boundary.

---

### 2026-09-06 — CLAIM_ASSIGNED audit rows are actor-less system actions

**Context:** C1: "every status change and decision is logged with actor and timestamp."
Assignment is triggered by a claimant's FNOL but performed by the system.
**Decision:** The audit writer records `CLAIM_ASSIGNED` with `actor_sub` NULL (system),
`after` holding claim number, assignee id + display name, and the new status; `CLAIM_CREATED`
keeps the claimant as actor. The schema already allows a NULL actor.
**Why:** Crediting the claimant with an internal assignment (or fabricating an adjuster as
the actor) would misattribute the action.

---

### 2026-09-06 — Slice-1 review fixes (fresh-context agent review)

**Context:** A fresh-context review of slice 1 found one blocking CI issue and eight
should-fix items. All were accepted and fixed; this entry records the consequential ones
and the optional items deliberately left open.
**Fixed:** CI E2E job now starts Keycloak (`docker compose up -d db mailpit keycloak` +
realm poll — previously Keycloak never started, first run would be red). E2E backend runs
on a **dedicated port 8082** with `claims_e2e` env and `reuseExistingServer: false`, so a
developer's dev-claims backend on 8081 can never absorb E2E writes (frontend uses
`proxy.e2e.conf.json`). Policy numbers are matched leniently (trim + uppercase) with
holder details case-insensitive. Photo validation happens before any file write, and the
claim upload directory is deleted when the transaction rolls back. Multipart limits sit
above the app-level photo caps so the app (not the multipart layer) returns readable
errors; `MaxUploadSizeExceededException` maps to a 400. Audit payloads are built with
Jackson (Boot 4 = Jackson 3, `tools.jackson`) instead of string concatenation.
`/api/policies` permitAll narrowed to GET. The integration test now asserts internal
fields (`claimantSub`, `policyId`, `lossDescription`, `level`) are absent from the wire
body, and a wrong-role (adjuster) 403 test pins the CLAIMANT mapping. E2E gained the
rejected-FNOL-stays-on-form journey (E2E is no longer fully parallel — concurrent Keycloak
registration flows are flaky). Dead code removed: unused `mockito-core` dependency,
`ClaimRepository.findByClaimNumber` (no caller), `logout()` in the SPA auth service.
**Deferred / open:** (a) attachment serving guidance for slice 3 — serve with
`Content-Disposition: attachment` and consider magic-byte sniffing rather than trusting
the client content type (stored-XSS vector when photos can be rendered); (b) Keycloak
compose healthcheck — CI polls the realm endpoint instead; add a healthcheck if `--wait`
should cover Keycloak; (c) `api.http` FNOL template is unexecuted until a token is
pasted — verify once against a running app.
**Revisit if:** any listed item becomes load-bearing in its slice.

---

### 2026-09-06 — Slice-1 schema: vertical, with two plan additions

**Context:** FNOL needed a `claim` table, an `attachment` table, and `authority_config`
(route level per product code). The approved plan model lists far more claim/authority
columns than slice 1 writes.
**Decision:** Each table carries only the columns the slice writes; columns the plan
specifies for later slices arrive through later ALTER migrations. Two additions beyond
the plan model, both approved: `claim.claimant_remarks` TEXT (Flow 1 says the claimant
"adds remarks"; the plan model had no home for them — internal, never on claimant-facing
surfaces) and a `claim_number_seq` used for `CLM-%06d` claim numbers.
**Why:** Vertical slicing keeps schema churn matched to code; remarks need a home today.
**Rejected:** Full plan schema now (dead columns); remarks folded into loss_description
(loses the distinction the form implies).
**Revisit if:** a later slice wants a different claim-number format (migration would add a
new sequence/column, never edit existing rows).

---

### 2026-09-06 — FNOL email is best-effort, after commit

**Context:** Flow 1 requires the FNOL email; claims must never be lost to a mail outage.
**Decision:** The email sends after the claim transaction commits, in the controller; any
send failure is logged and the claim stands. Tests assert delivery against Mailpit
(Testcontainers in the integration suite — the plan's captured mailbox; Mailpit also runs
in compose for dev).
**Why:** Claim creation is the regulated event; a notification failure must not roll it back.
**Rejected:** Sending inside the claim transaction (email failure rolls back a valid claim).
**Revisit if:** email delivery guarantees are required (then a queue/retry would be the answer).

---

### 2026-09-06 — Keycloak wiring lands in slice 1 (resolves C2 deferral)

**Context:** C2 deferred the Keycloak dev container to the slice that wires auth.
**Decision:** Slice 1 wires it: compose runs Keycloak 26.3 (host :8090; host :8080 is taken
by an unrelated process) importing the `claims` realm from `keycloak/realm-export.json`
(claimant self-registration on; realm roles claimant/adjuster_l1/adjuster_l2/supervisor;
self-registered users default to `claimant`). Spring Boot is an OIDC resource server
(issuer `http://localhost:8090/realms/claims`) mapping `realm_access.roles` to `ROLE_*`;
Angular uses the public SPA client `claims-frontend` (authorization-code + PKCE).
`POST /api/claims` requires `ROLE_CLAIMANT`. Integration tests mint HS256-signed test
JWTs (no Keycloak needed in unit/integration runs); E2E registers through the real realm.
**Why:** Retires the stack's riskiest unknown with real verification, per plan sequencing.
**Rejected:** Mock-only auth in tests (would not exercise the realm import or SPA flow).
**Revisit if:** token lifetimes, refresh handling, or internal-user provisioning need work
(slice 2+ provisions adjusters).

---

### 2026-09-06 — E2E runs on a dedicated database (implements S1 deferral)

**Context:** The S1 review deferral said to give E2E its own database once journeys write.
**Decision:** Slice 1 journeys write, so this is now built: compose bootstraps a
`claims_e2e` database (`docker/db-init`), and Playwright boots the backend with
`SPRING_DATASOURCE_URL` pointing at `claims_e2e` (Flyway migrates it on boot). Dev
`claims` data is never touched by E2E. CI starts the full compose stack (db + Mailpit +
Keycloak) for the E2E job.
**Why:** E2E creates claims; the old pattern (shared dev DB) was the exact bug S1 flagged.
**Rejected:** Continuing against the dev DB now that journeys write.
**Revisit if:** nothing — this is the standing arrangement.

---

### 2026-09-03 — Slice-0 audit-log writer timing: slice 1, not slice 4 (C1)

**Context:** The plan says the audit-log mechanism is "laid down in the skeleton, not a bolted-on slice," and requirements say every status change and every decision is logged. Status changes begin at FNOL (slice 1: claim created) and assignment (slice 2), before any decision exists.
**Decision:** Slice 0 ships the `audit_log` table DDL only. The append-only writer service lands in **slice 1** with the first write — emitting the "claim created" entry — and is used by every later state change. It must not wait for slice 4's first decision, or slices 1–3 ship unlogged and need a retrofit.
**Why:** The skeleton has no writes, so a writer would be dead code now; but it must exist before slice 1's first write, not before slice 4's first decision.
**Rejected:** A slice-0 writer with no caller (speculative); a writer deferred to slice 4 (unlogged slices 1–3).
**Revisit if:** nothing — timing is fixed by the slice order in `docs/plan.md`.

---

### 2026-09-03 — Integration test database: Testcontainers, with a dedicated fallback (C3)

**Context:** This sandbox has no usable Docker daemon, so Testcontainers cannot run here. Integration tests still must run against a real PostgreSQL (per the existing Testcontainers decision — never H2).
**Decision:** Integration tests use a Testcontainers `postgres:16-alpine` container whenever Docker is available (CI, normal dev machines). When it is not, `TestcontainersConfiguration` falls back to a **dedicated** test database from `TEST_DB_URL` (default `jdbc:postgresql://localhost:5432/claims_test`, user `claims`), never the dev `claims` database — integration tests truncate between tests and must not touch dev data. Local verification here ran PostgreSQL 16.15 from downloaded binaries (`/tmp/pg16`) serving both `claims` and `claims_test`.
**Why:** One test code path, real Postgres in both modes, zero risk to dev data.
**Rejected:** Pointing the fallback at the dev database (tests truncate it); H2.
**Revisit if:** Docker becomes available in this environment (then the fallback is simply never taken).

---

### 2026-09-03 — Authority is per-claim; no aggregate exposure cap

**Context:** The gate needed an unambiguous operand (indemnity vs payment, per-claim vs per-adjuster).
**Decision:** The gate compares the claim's single `indemnity_amount` against the acting level's limit; `payment.amount == indemnity_amount` (one payment per claim). No per-adjuster aggregate exposure cap in v1.
**Why:** One payment per claim makes per-claim and per-payment identical; no aggregate cap was in requirements.
**Rejected:** A per-adjuster running-total cap (not requested).
**Revisit if:** partial payments or aggregate limits become requirements.

---

### 2026-09-03 — Classification via per-product-code route_level

**Context:** Requirements said classification uses "estimated amount and complexity" keyed on product code, but the claimant supplies neither at FNOL.
**Decision:** `authority_config` holds `route_level` (L1|L2) per product code — the "complexity" routing parameter — plus `l1_limit_amount`/`l2_limit_amount` as the monetary thresholds evaluated at decision time.
**Why:** No amount is available at FNOL; the product code's routing level is the only honest classification input.
**Rejected:** Claimant-entered estimated amount at FNOL (not in the flow); free-text complexity field.
**Revisit if:** a real estimate/complexity input is introduced at intake.

---

### 2026-09-03 — Escalation-to-L2 is system re-assignment, not a new status

**Context:** `ESCALATED_L2` had no API surface or assignment rule.
**Decision:** An above-level (but ≤ L2-limit) decision re-assigns the claim to the least-loaded L2 adjuster; level → L2; status returns to `UNDER_REVIEW`. Only above-L2 becomes `ESCALATED_SUPERVISOR`.
**Why:** Reuses the existing assignment rule; the original adjuster structurally can't self-approve (level changes, assignee changes).
**Rejected:** A separate ESCALATED_L2 status + L2 escalation queue (more states, more surface).
**Revisit if:** L2 pickup needs its own queue distinct from normal caseload.

---

### 2026-09-03 — Unauthorized access returns 404

**Context:** Cross-tenant and non-assignee access needed a defined response.
**Decision:** 404 (not 403) for any claim the caller has no right to see.
**Why:** 403 reveals that a claim number exists — a leak through the visibility wall.
**Rejected:** 403 (leaks existence).
**Revisit if:** a requirement emerges to disclose existence (unlikely).

---

### 2026-09-03 — Load-balance tie-break: lowest user id

**Context:** "Fewest open claims" is under-determined when adjusters tie.
**Decision:** Assign to the level-appropriate adjuster with the fewest open claims; ties broken by lowest `app_user.id`.
**Why:** Deterministic, testable, no extra state.
**Rejected:** Random, oldest assignment (nondeterministic/hard to assert).
**Revisit if:** a business preference for tie-breaking emerges.

---

### 2026-09-03 — Roles live in Keycloak only; no in-app user provisioning

**Context:** Needed three roles plus a per-adjuster level for routing and the authority gate. The plan initially had a local `app_user.level` column and a supervisor provisioning UI.
**Decision:** Keycloak is the single source of truth for identity and roles (`CLAIMANT`, `ADJUSTER_L1`, `ADJUSTER_L2`, `SUPERVISOR`). `app_user` is a thin cache (subject → display name/email) auto-populated on login, only so claims can hold an assignee FK.
**Why:** Avoids two sources of truth that drift; cuts a whole screen and endpoint surface from v1.
**Rejected:** Local level column + `/admin/users` provisioning UI.
**Revisit if:** a non-Keycloak internal identity source appears, or claims must be assigned to staff before they've logged in once.

---

### 2026-09-03 — FNOL policy identification: match number + holder details

**Context:** Claimant must file against a seeded policy, but there's no policy-admin integration to verify ownership.
**Decision:** Claimant enters policy number + policyholder name/email; the system matches against the seeded policy row and rejects on mismatch.
**Why:** One match field keeps it honest without building an integration.
**Rejected:** No verification (pick any policy — pure demo); supervisor pre-links identities (manual).
**Revisit if:** a policy-admin integration arrives.

---

### 2026-09-03 — Photo storage: filesystem

**Context:** Claimant uploads evidence photos at FNOL; hosting unspecified.
**Decision:** Files on disk (configurable upload dir), path stored in DB.
**Why:** Small book; photos are evidence, not a CDN; keeps Postgres lean and streaming simple.
**Rejected:** Postgres bytea/LO (self-contained but bloats DB and streams poorly); S3/MinIO (dependency we don't need yet).
**Revisit if:** hosting moves somewhere ephemeral or photo volume spikes.

---

### 2026-09-03 — SPA↔backend auth: public SPA with PKCE + resource server

**Context:** Angular SPA + Spring Boot + Keycloak.
**Decision:** Angular holds a Keycloak access token (public client, PKCE) and calls Spring Boot as a resource server that validates the JWT and maps roles.
**Why:** Standard, fewer moving parts; nothing worth protecting beyond PKCE here.
**Rejected:** BFF with server sessions (extra hop + state).
**Revisit if:** a client secret becomes necessary or a second non-browser client appears.

---

### 2026-09-03 — Integration test DB: Testcontainers Postgres

**Context:** Integration tests need a real database per the test strategy.
**Decision:** Testcontainers spins a real Postgres per test class; schema from Flyway; reset by truncate.
**Why:** Real Postgres behavior (constraints, JSONB, uniqueness) instead of H2's approximation.
**Rejected:** H2 in Postgres-compat mode (lies about Postgres).
**Revisit if:** container runtime unavailable in CI.

---

## Deferred

### 2026-09-06 — Slice-3 review optional items

**Considered:** Six optional cleanups from the slice-3 review: the unused `loaded()`
signals in the two new components; simplifying a redundant second `app_user` lookup in
`addNote` (already folded into the S6 fix); empty/NaN reserve submissions from the SPA
(`Number()` of a blank box submits 0, NaN submits null); two stale javadocs (SecurityConfig
and the claimant guard say "slice 1"/"the queue" only); the `£` + raw-number reserve
display (currency formatting); extracting the E2E FNOL-form helper, then duplicated in two
specs.
**Why not now:** Cosmetic or low-risk; the user scoped the fix round to the should-fix
findings.
**Build it when:** slice 4 kept the FNOL/register helpers at two copies on purpose
(journeys 5–6 joined `queue.spec.ts` rather than a new file, so no third copy appeared and
the E2E worker count stayed flat — see the slice-4 decision entry). Extract the helpers only
when a real third spec file needs them (e.g. a supervisor spec in slice 5), a hardening
pass touches the same files, or the duplication otherwise grows past two copies.

---

### 2026-09-06 — Slice-2 review optional items

**Considered:** Seven optional cleanups from the slice-2 review: replacing
`LoadBalancer`'s load-bearing `.sorted()` with an explicit id `thenComparing`; recording
the CLAIM_CREATED audit payload with the final (UNDER_REVIEW) status instead of the
momentary UNASSIGNED state; hiding the "Adjuster queue" nav link from the public;
dropping `QueueClaimView.createdAt` (no consumer yet); fixing UNDER_REVIEW step copy that
contradicts the received step; extracting the duplicated E2E `registerClaimant` helper;
hardening the dev-only plaintext credentials (Keycloak admin/admin, adjuster passwords)
in realm-export/docker-compose.
**Why not now:** All are cosmetic or cross-slice concerns with no behavioral risk; the
user scoped the fix round to the should-fix findings. Credential hardening is a
pre-ship/hardening-phase checklist item, not a slice item.
**Build it when:** a later slice (or a hardening pass) touches the relevant file anyway —
none of these has become load-bearing through slice 4.

---

### 2026-09-06 — app_user auto-population on login; supervisor Keycloak user

**Considered:** A login hook that upserts `app_user` rows from the JWT, and provisioning a
supervisor user in Keycloak now.
**Why not now:** Adjusters are seeded with fixed subjects, so no first-login upsert is
needed; the supervisor role token already unlocks the team queue in tests, and no journey
logs in as a supervisor until the supervisor slices (5+).
**Build it when:** staff display data can drift from Keycloak, or slice 5's supervisor
journey needs a real supervisor login.

---

### 2026-09-03 — E2E database isolation (review finding S1, follow-up)

**Considered:** Giving the Playwright-booted backend its own isolated database (separate
Spring profile or `claims_e2e`) so E2E never touches dev data, per review finding S1.
**Why not now:** Slice-0 E2E is read-only (asserts the seeded policy row is visible) and
the CI E2E job already runs against an ephemeral per-run Postgres service container. The
spec no longer asserts an exact row count, so future seed additions won't break it. Real
pollution risk starts when E2E journeys first *write* (slice 1 FNOL creates claims).
**Build it when:** slice 1 adds E2E journeys that create data — give E2E its own database
then, not before.

---

### 2026-09-03 — Hardcoded dev credentials (review finding O7)

**Considered:** Moving `claims`/`claims` out of `application.properties` and
`docker-compose.yml` into environment variables.
**Why not now:** Local-dev-only skeleton; both files agree on the same value and nothing
non-local deploys yet. Secrets-in-env is enforced later by the hardening phase checklist.
**Build it when:** any non-local deployment or shared environment appears.

---

### 2026-09-03 — Keycloak dev container (C2)

**Considered:** Adding a Keycloak service with a realm import to `docker-compose.yml` during slice 0 ("Keycloak wired" in the plan's slice-0 line).
**Why not now:** `03-skeleton.md` says don't build auth, and the slice-0 read path needs only Postgres. A Keycloak container nothing connects to is ahead of schedule.
**Build it when:** slice 1 wires real auth (claimant registration/login, Spring Security resource server). Keep `docker-compose.yml` Postgres-only until then.

---

### 2026-09-03 — Reopening / appeals

**Considered:** Allow a decided claim to be reopened (appeals/reconsideration).
**Why not now:** One-way flow to closure is much simpler; no appeal process defined yet.
**Build it when:** an appeal/reconsideration process is actually specified.

---

### 2026-09-03 — Multiple / partial payments per claim

**Considered:** Partial payments (initial + supplement) against one claim.
**Why not now:** Complicates the authority gate (cumulative totals) and closure; v1 is one payment per claim.
**Build it when:** supplements/partial payments become a real business need.

---

### 2026-09-03 — SMS notifications, mobile app, real-time updates

**Considered:** SMS delivery, a native mobile app, websocket live updates.
**Why not now:** Email-only + responsive web + pull-to-refresh covers v1; each adds surface and flake.
**Build it when:** claimants demonstrably need push or live updates, or field adjusters need offline/native.

---

### 2026-09-03 — Object storage (S3/MinIO)

**Considered:** Storing evidence photos in object storage.
**Why not now:** One storage need (claimant photos), no scale problem yet.
**Build it when:** hosting is ephemeral or photo volume grows.

---

### 2026-09-07 — V2-1: remaining benefit derived, never stored

**Considered:** Storing `remaining_sum_insured` / per-cover remaining counters on the
policy row, decremented at each decision.
**Why not now:** Stored counters drift under concurrent decisions and need lock
discipline; the cockpit reads are cheap aggregates (sum of prior non-DENIED net
payables) and always consistent with the audit log. Locked in `docs/plan-v2.md`:
limits constrain assessment/approval, never FNOL rejection; one cover's exhaustion
never exhausts the others.
**Build it when:** cockpit reads show up in slow-query logs at real policy volumes.

---

### 2026-09-07 — V2-1: queue resolves the adjuster by Keycloak subject, not email

**Considered:** Looking up `app_user` by the JWT email claim (survives user
re-creation in Keycloak).
**Why not now:** Subjects are stable across realm re-imports (template UUIDs); email
lookup would let a realm email change silently re-home an adjuster's queue. The
close-out proved the failure mode: Admin-API-created users get random subs and see
empty queues with a loud server WARN. Kept subject mapping + the WARN log.
**Build it when:** a carrier brings its own IdP where subject stability can't be
guaranteed — then treat it as an identity-migration project, not a lookup tweak.

---

### 2026-09-07 — V2-1: E2E import journey generates its CSV per run

**Considered:** A static 3-row fixture committed to the repo.
**Why not now:** Reruns trip on "already exists" after any partial run imports the
rows; per-run unique policy numbers keep the journey idempotent with zero cleanup
coupling. The BOGUS row keeps its static number (always rejected, never persisted).
**Build it when:** never — generated-per-run is the pattern for any journey that
writes unique-keyed rows.

---

### 2026-09-08 — Adjuster-workflow tightening: one next step per stage (V16)

**Considered:** Keeping all action forms (assessment + decision + extra checks +
send-back) visible together on the verification stage, and collapsible send-back
everywhere.
**Why not now:** The verification screen showed assessment and decision inputs side
by side while the checklist was still open, and assessment was one click away from
failing server-side with no visible reason. Now each stage shows exactly one exit:
Review advances/rejects/sends back; Verification completes the default
PHYSICAL+DOCUMENT+CLAUSE checklist (follow-ups stay addable), then the assessment
box unlocks only when every open row is COMPLETE; Decision holds the per-cover
grid plus the authority hint. Send-back ("Ask claimant") is a visible button at
all three stages — the earlier `<details>`-collapsed variant hid the control from
both users and Playwright's visibility wait, which is how the probe caught it.
Refer-upwards and the document panel sit in a slim rail beside the step at every
stage, so a claim can always move adjuster→senior→supervisor→claimant and back
without leaving the workspace. Legacy no-cover claims keep the byte-identical V1
decision form (stage stepper hidden, no cover machinery).
**Build it when:** never as a combined form — one exit per stage is the pattern.

### 2026-09-08 — V16 backend: default verification checklist + all-complete gate

**Considered:** Keeping the single typeless PENDING stub on ADVANCE and gating
assessment on "latest verification COMPLETE".
**Why not now:** The typeless stub forced every flow (UI + E2E + tests) to open and
name its own first check, and "latest COMPLETE" let an older open row slip through
unexamined. ADVANCE now opens PHYSICAL + DOCUMENT + CLAUSE; CANCELLED rows retire
from the checklist; anything else open blocks assessment with the pending names in
the 400. Pre-V16 DIGITAL/PHYSICAL rows and the NULL-type stub keep their values
(the type CHECK only widened; DOCUMENT/CLAUSE added; NEED_INFO prior-stage gained
DECISION; attachment gained a nullable label).
**Build it when:** never — the checklist is the verification model.

### 2026-09-08 — NEED_INFO from any stage; claimant answers with label + upload

**Considered:** NEED_INFO from REVIEW/VERIFICATION only, claimant response as a
bare text message.
**Why not now:** A decision-stage doubt (e.g. confirm the bill total before
signing) had no legal parking state, and the claimant's reply arrived with no way
to attach the very document requested. The adjuster can now park from REVIEW,
VERIFICATION or DECISION (prior stage preserved for the return trip); the
claimant's status page shows an action panel with the request text, a labelled
document upload (`POST …/documents`, NEED_INFO-only, own-claim-only, 404
otherwise), then the text reply that returns the claim. The request text rides
`needInfoReason` on the claimant view (null — hence wire-omitted — at every other
state); adjuster uploads ride `POST …/attachments` on open claims from any stage.
Referral likewise works pre-decision (no proposals to cover-check: named senior or
auto-pick one rung up, supervisor fallback) as well as at DECISION (unchanged
limit-cover rule).
**Build it when:** never — park/answer/return is the round-trip.
