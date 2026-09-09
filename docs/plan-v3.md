# Plan V3 — Evidence, concurrency, reopen, staff, compliance, notifications, locale

Status: Draft (build in order; one session per slice).
Last updated: 2026-09-09.
Based on: `docs/requirements.md` (V1 Approved) + `docs/requirements-v2.md` (V2 Approved)
+ `docs/plan.md` + `docs/plan-v2.md` (still the build record — untouched).

> Why a third file: V1/V2 plans are the approved build record and stay untouched.
> This file plans the V3 hardening-to-product evolution. Every V1/V2 guarantee is a
> **constraint** on every V3 slice, not history: visibility wall, 404-not-403,
> immutable audit, atomic closure, email outbox, least-loaded + lowest-id tie-break,
> no-cache config reads, Flyway V1–V17 immutable.

## How to use this file with a weaker model (read this first)

Each slice is self-contained and ordered. Run **one slice per session** in numeric
order (S1 → S11). A slice is done only when its **Done checklist** is fully green.

**Copy-paste session prompt (fill SLICE_N):**

```text
Implement docs/plan-v3.md slice SLICE_N (only that slice — nothing else).
Follow the slice's Preconditions → Migration → Backend → Frontend → Tests →
api.http/docs steps in order. Obey the Global rules (§0) — especially: never edit
migrations V1–V17, never UPDATE/DELETE audit_log, 404-not-403 on per-claim reads,
claimant DTOs structurally omit internal fields, every state transition writes an
audit row. Verify with the slice's Verification commands and report the Done
checklist with exact test counts. If any verification fails, fix it before stopping.
Do not start the next slice.
```

**Stack (unchanged):** Angular SPA (`frontend/`), Spring Boot Java (`backend/`,
`mvn -f backend/pom.xml`), PostgreSQL 16 + Flyway (`backend/src/main/resources/db/migration/`),
Keycloak OIDC (`:8090`, realm `claims`), Playwright hermetic E2E (`e2e/`,
backend `:8082` + own `ng serve :4200`, DB `claims_e2e`), Mailpit (`:1025`/`:8025`).
Currency in seeds/examples: INR (₹). Next free migration number: **V18**
(V1–V17 applied — verify with `ls backend/src/main/resources/db/migration/`).

---

## §0 — Global rules (every slice, no exceptions)

1. **Migrations are immutable.** Never edit V1–V17 (Flyway checksums applied DBs).
   Each slice states its new `VNN__*.sql`. New columns: nullable first OR with a
   backfill in the same file; never break existing rows.
2. **Append-only audit.** Never UPDATE/DELETE `audit_log` (V7 trigger raises).
   New audit `action` strings are free text — no migration needed. Every state
   transition writes a row (actor + before/after + rationale/notes).
3. **404-not-403.** Per-claim reads/writes for a non-holder, non-assignee,
   cross-tenant caller return 404 (`ClaimNotFoundException`), never 403 — so the
   response never reveals a claim number exists. Role gates (claimant vs adjuster
   vs supervisor URLs) stay in `backend/src/main/java/com/claims/config/SecurityConfig.java`.
4. **Visibility wall.** Claimant DTOs (`ClaimantClaimView`, `MyClaimView`,
   tracker) **structurally omit** reserve, assessed/approved figures, internal
   notes, verifier identity, proposals. Assert absence in integration tests
   (string-absence on the response body), not just UI hiding.
5. **Authority + assignment discipline.** Gate comparisons re-read
   `authority_config` per call (no cache). Assignment reuses `ClaimAssigner`
   (least-loaded eligible, tie-break lowest `app_user.id`, `FOR UPDATE` scoped
   to candidates). Eligibility (skill) and authority (level) stay independent.
6. **Outbox spine.** Every claimant email is written via
   `backend/src/main/java/com/claims/outbox/EmailOutboxWriter.java` **in the
   business transaction** (never sent inline); the 60s dispatcher delivers.
   Tests drive `EmailOutboxDispatcher.dispatch()` directly (scheduler off in
   tests via `ClaimTableResettingTest`).
7. **Test reset contract.** Claim-writing integration classes extend
   `backend/src/test/java/com/claims/support/ClaimTableResettingTest.java`.
   Its `@BeforeEach` truncates
   `claim, attachment, internal_note, payment, audit_log, fnol_submission, email_outbox, verification`
   (CASCADE covers FK children like `claim_cover`). If your slice adds a table
   **without** an FK to `claim`, add it to that TRUNCATE list in the same slice.
8. **E2E discipline.** Hermetic gate only: `npx playwright test` from `e2e/`
   (boots backend `:8082` on `claims_e2e` + own `ng serve :4200`,
   `reuseExistingServer:false`). Per-run uniqueness: loss dates
   `2020-01-01 + (epochSeconds % 2000) days` (see `e2e/tests/staged.spec.ts`
   `runDate`), claimant usernames `prefix + Date.now() + counter`, CSV imports
   unique per run. Never touch the dev `claims` DB from E2E. `data-testid`
   selectors only, no fixed sleeps. New UI controls get new `data-testid`s;
   never rename existing ones.
9. **Frontend budget.** `frontend/angular.json` `anyComponentStyle` budget is
   ~17kB max — `claim-detail.css` sits at the ceiling. Prefer editing the
   existing classes/tokens in `frontend/src/styles.css`; do **not** grow
   `claim-detail.*` (1444/1534/1118 lines) — extract small components if a
   slice needs new stage UI, reusing testids.
10. **Docs per slice.** Append a `docs/decisions.md` entry (newest-first, same
    shape as V18: Context / What changed / Verified / Deliberately not built),
    add one `api.http` example per new/changed endpoint, update
    `docs/operations.md` where the slice says so. Commit one slice per commit.

**Baseline before S1:** run `mvn -f backend/pom.xml test`, `npm --prefix
frontend run build`, `npx playwright test` from `e2e/`; record the three green
counts in your session report. If any is red, stop — fix or report, do not start S1.

---

## S1 — Evidence file types: PDF + integrity (sha256/size + magic bytes)

**Goal:** a claimant can attach the documents carriers actually ask for
(discharge summary, final bill = PDFs), and every stored byte is integrity-pinned.
**Why first:** the NEED_INFO copy already asks for these; today the validator
rejects them. Smallest slice, highest buyer-visible value.

**Preconditions:** read `FilesystemPhotoStorage.java` (validate/store/resolve),
`PhotoStorage.java`, `StoredPhoto.java` (extend if it lacks sha/size fields),
`Attachment.java`, `ClaimService.java` (FNOL store path),
`ClaimWorkService.java` (attach/download), `ClaimantStatusController.java`
(NEED_INFO upload), `frontend/src/app/fnol/fnol.ts` (client guards).

**Migration — `V18__attachment_integrity.sql`:**
```sql
ALTER TABLE attachment
    ADD COLUMN sha256 CHAR(64) NULL,
    ADD COLUMN size_bytes BIGINT NULL;
-- New rows fill both NOT NULL via the service; pre-V18 rows stay NULL
-- (backfilled opportunistically by S2's storage job; NULL = "unpinned legacy").
```

**Backend:**
- `FilesystemPhotoStorage.validate()`: allowlist = `image/*` + `application/pdf`.
  Add **magic-byte check** on the first 8 bytes: PNG `89 50 4E 47`, JPEG `FF D8 FF`,
  GIF `47 49 46`, WEBP `52 49 46 46 … 57 45 42 50`, PDF `25 50 44 46` (`%PDF`).
  Mismatch → `FnolValidationException("Attachments must be image or PDF files.")`.
  Keep `claims.uploads.max-size-bytes` (10MB) and `max-count` (5) caps.
- `store()`: compute SHA-256 + byte size per file; return via `StoredPhoto`
  (extend the record if needed); persist `sha256`/`size_bytes` on the
  `Attachment` row in **all three writers**: FNOL (`ClaimService`), adjuster
  attach (`ClaimWorkService.attach`), claimant NEED_INFO upload
  (`ClaimantStatusController`). Rewriter: `download()` verifies sha when
  non-null; mismatch → error log + `ClaimNotFoundException` (never serve corrupt bytes).
- Error-message sweep: every user-facing "must be image files" string becomes
  "must be image or PDF files" (backend + `fnol.ts` + `claim-status` upload + `api.http`).

**Frontend (`fnol.ts`, `claim-status`, `claim-detail` attach inputs):**
- `<input type=file>` gains `accept="image/*,.pdf,application/pdf"`; client guard
  mirrors server (count + per-file size + type) with the new message. No new
  testids (existing upload controls unchanged).

**Tests:**
- Unit: magic-byte matrix (png/jpg/gif/webp/pdf pass; `%PDF`-named `.exe`, `text/plain`
  spoofed as `.png` rejected) + oversize/over-count still rejected.
- Integration (new `AttachmentIntegrityIntegrationTest`, extends
  `ClaimTableResettingTest`): FNOL with a 2-page minimal PDF stores `sha256` (64 hex)
  + `size_bytes`; adjuster + NEED_INFO uploads pin too; legacy-NULL rows still
  download; tampered file (overwrite bytes on disk) fails to download.
- E2E: extend one FNOL journey — attach a tiny PDF alongside the photo, assert the
  timeline document row + successful download (filename preserved).

**api.http/docs:** update the FNOL + attach examples with a PDF part; `decisions.md` entry.
**Verification:** `mvn -f backend/pom.xml test -Dtest=AttachmentIntegrityIntegrationTest`,
then full `mvn -f backend/pom.xml test`; `npm --prefix frontend run build`; the
extended E2E spec.
**Done checklist:** [ ] PDF files end-to-end (file → timeline → download) [ ] spoofed
types rejected [ ] sha/size on all new rows [ ] legacy rows unaffected [ ] full
backend suite green [ ] frontend build green [ ] E2E journey green.
**Non-goals:** virus scanning (S-sidecar later), inline preview (force-download stays),
OCR/text-extraction, raising size/count caps.

---

## S2 — S3-compatible object storage behind the seam (MinIO dev, filesystem default)

**Goal:** evidence survives re-images/multi-host deploys. `attachment.storage_path`
already holds portable keys (`{claimId}/{uuid}{ext}`) — no schema change.
**Why second:** silent evidence loss is the worst bug category for insurance; the
seam (`PhotoStorage`) was built for exactly this swap.

**Preconditions:** read `PhotoStorage.java`, `FilesystemPhotoStorage.java`,
`ClaimWorkService.photoStoragePath()` (the `instanceof` branch must go),
`StorageSeamIntegrationTest.java`, `docker-compose.yml`, `application.properties`
(`claims.uploads.*`), `docs/operations.md` ("moving to S3" + restore-pairing gate).

**Step 0 — dependency check (do this before writing code):**
`ls ~/.m2/repository/software/amazon*` — prior sale-readiness work found the Maven
cache is **offline/read-only** (micrometer-prometheus was missing; metrics went
dependency-free). If no AWS SDK is cached, **do not add a Maven dependency**:
implement `S3PhotoStorage` with `java.net.http.HttpClient` + hand-rolled AWS
SigV4 (PUT/GET/HEAD/DELETE against S3 REST) — zero new deps. Only if the network
build works may you use `software.amazon.awssdk:s3` instead.

**Backend:**
- New `S3PhotoStorage implements PhotoStorage` (bean `"s3PhotoStorage"`): same
  `validate()` rules as S1 (share via a `PhotoValidator` helper extracted from
  `FilesystemPhotoStorage`), keys identical (`{claimId}/{uuid}{ext}`), sha/size
  computed the same way and returned via `StoredPhoto`.
- Config (all in `application.properties` + `.env.example`): 
  `claims.storage.backend=filesystem|s3` (default `filesystem`),
  `claims.s3.endpoint`, `claims.s3.bucket`, `claims.s3.region`,
  `claims.s3.access-key`, `claims.s3.secret-key` (env-injected, never committed).
  A `@ConditionalOnProperty` (or `@Primary` selector) exposes the right bean as
  `"photoStorage"` — every injection point keeps working.
- Remove the `instanceof FilesystemPhotoStorage` branch in `ClaimWorkService`
  (serve via `photoStorage.load()` for both backends).
- One-shot backfill: `S3BackfillRunner` (disabled by default,
  `claims.storage.backfill=false`; run with `=true` once): iterates attachments
  with local files, PUTs each key, verifies sha, logs missing-file orphans —
  never deletes local files itself (operator removes after the orphan check).
- `docker-compose.yml`: MinIO service for dev (`minio/minio`, console `:9001`),
  bucket auto-created; `docker-compose.prod.yml`: real S3 endpoint via env.

**Tests:**
- Integration (new `S3StorageIntegrationTest`, Testcontainers MinIO **only if the
  image is cached**, else a fake-S3 `HttpServer` stub asserting SigV4 + key shape):
  store → key exists → load returns bytes → deleteClaimDir removes; filesystem
  default suite (`StorageSeamIntegrationTest`) unchanged and green.
- Existing suites must pass with `claims.storage.backend=filesystem` (default) —
  zero behaviour change for local dev.

**Docs:** `operations.md` S3 page (endpoint/bucket/creds, backfill runbook, updated
restore-pairing gate: DB dump + bucket versioning snapshot as one atomic pair +
orphan check); `.env.example` new vars; `decisions.md` entry.
**Verification:** full backend suite twice (default backend + `claims.storage.backend=s3`
against stub/MinIO for the new class); frontend untouched (build only).
**Done checklist:** [ ] default dev flow byte-identical [ ] S3 store/load/delete green
[ ] backfill dry-run logs orphans without writes [ ] no new Maven dep (or dep justified
+ offline build still passes) [ ] operations runbook updated.
**Non-goals:** presigned-URL browser uploads (server stays the ingress), lifecycle/
versioning policies (operator-owned), CDN.

---

## S3 — Required-documents checklist per product (kills the NEED_INFO ping-pong)

**Goal:** "what's missing" stops living in free text. Per-product required docs →
adjuster ticks per claim (audited) → claimant sees "2 of 4 received".
**Depends on:** S1 (PDFs attachable).

**Migration — `V19__required_documents.sql`:**
```sql
CREATE TABLE required_document (
    id BIGSERIAL PRIMARY KEY,
    product_code VARCHAR(40) NOT NULL,
    cover_code VARCHAR(40) NULL,          -- NULL = claim-level (e.g. ID proof)
    doc_key VARCHAR(60) NOT NULL,         -- stable key: DISCHARGE_SUMMARY, FINAL_BILL, ...
    display_name VARCHAR(120) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    UNIQUE (product_code, COALESCE(cover_code, '-'), doc_key)
);
CREATE TABLE claim_document_check (
    id BIGSERIAL PRIMARY KEY,
    claim_id BIGINT NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    required_document_id BIGINT NOT NULL REFERENCES required_document (id),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','RECEIVED','WAIVED')),
    attachment_id BIGINT NULL REFERENCES attachment (id) ON DELETE SET NULL,
    decided_by VARCHAR(100) NULL, decided_at TIMESTAMPTZ NULL,
    UNIQUE (claim_id, required_document_id)
);
CREATE INDEX idx_claim_doc_check_claim ON claim_document_check (claim_id);
-- Seed: 3-4 per family (HEALTH: DISCHARGE_SUMMARY, FINAL_BILL, ID_PROOF;
-- AUTO: PHOTOS, ESTIMATE, RC_COPY; PROPERTY: PHOTOS, ESTIMATE, OWNERSHIP_PROOF).
-- Cover-scoped rows only where the cover demands it (else cover_code NULL).
```

**Backend:**
- On claim creation (in `ClaimService` FNOL transaction): auto-insert
  `claim_document_check` PENDING rows for the policy product's required docs.
- `GET /api/claims/{n}/required-documents` — assignee/supervisor full rows;
  claimant own-claim gets `{displayName, status}` only (wall: no decided_by).
- `POST /api/claims/{n}/required-documents/{checkId}/link {attachmentId}` +
  `/waive {rationale}` — assignee-only (404 otherwise); attachment must belong to
  the claim (else 404); each writes an audit row (`DOC_LINKED`/`DOC_WAIVED`).
  FNOL/attach auto-links when the uploader passes a matching `docKey` (extend
  both upload endpoints with optional `docKey`; unknown keys are 400 naming valid keys).
- `SecurityConfig`: add the three matchers (GET internal+claimant-own — enforce
  own-claim inside the service; POST/waive adjuster roles).
- `StagedClaimView`/`InternalClaimView`: embed `documentsReceived/total` counts;
  claimant tracker shows the same counts + per-item labels (never internals).

**Frontend:** adjuster claim-detail panel "Required documents" (checklist with
link/waive actions, testids `detail-reqdoc-{key}/link/waive`); claimant tracker
"Documents: 2 of 4 received" + item list (testids `claim-reqdocs/count/item`).
Keep it inside existing panels — no new routes.

**Tests:** unit (seed coverage per family); integration
(`RequiredDocumentsIntegrationTest`): auto-created rows on FNOL, link/waive auth
matrix (stranger 404, claimant 403 on waive), audit rows, claimant view shows
counts but no `decided_by`; E2E: NEED_INFO flow now links a doc and the tracker
count increments.
**Done checklist:** [ ] every new claim gets checklist rows [ ] link/waive audited
[ ] claimant sees counts, never internals [ ] suites + E2E green.
**Non-goals:** OCR auto-detection of doc type (human links), blocking decision on
incomplete checklist (advisory only — the gate stays money-based).

---

## S4 — Document metadata + supersede (versioning without a DMS)

**Goal:** kill duplicate uploads: re-upload supersedes, timeline links the chain.
**Depends on:** S1.

**Migration — `V20__attachment_metadata.sql`:**
```sql
ALTER TABLE attachment
    ADD COLUMN doc_type VARCHAR(60) NULL,          -- mirrors required_document.doc_key, free for ad-hoc
    ADD COLUMN replaces_attachment_id BIGINT NULL REFERENCES attachment (id) ON DELETE SET NULL;
CREATE INDEX idx_attachment_replaces ON attachment (replaces_attachment_id);
```

**Backend:** both upload endpoints (`ClaimWorkController.attach`,
`ClaimantStatusController` documents) accept optional `docType` (≤60 chars) +
`replacesId`; guards: replaced row must belong to the same claim (else 404);
CLOSED claims still 400. Timeline entry gains `supersedes: <originalName>` text.
`AttachmentView` carries `docType` + `replacesId`.

**Frontend:** upload forms gain a doc-type `<select>` (populated from the claim's
required docs + "Other") and a "replaces" picker (existing attachments on the
claim); timeline rows show a "supersedes X" chip. Testids:
`detail-attach-doctype/replaces`, timeline chip `detail-timeline-supersedes`.

**Tests:** integration (`AttachmentSupersedeIntegrationTest`): same-claim replace
links + timeline text; cross-claim replacesId → 404; chain of 3 renders in order;
E2E: upload v2 of a bill, assert the chip.
**Done checklist:** [ ] replace-chain links [ ] cross-claim rejected [ ] timeline
readable [ ] green suites.
**Non-goals:** full version history UI, diffing, delete-old-on-replace (history is
append-only — old bytes stay).

---

## S5 — Optimistic concurrency: no more silent overwrites (409-on-conflict)

**Goal:** two adjusters on one claim (post-reassign) stop overwriting each other.
**Touches the money paths — implement carefully.**

**Migration — `V21__claim_version.sql`:**
```sql
ALTER TABLE claim ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

**Backend:**
- `Claim.java`: `@Version private Long version;` (+ getter, no setter).
- Conflict surface (all compare-and-swap): `PUT …/reserve`,
  `PUT …/assessment`, `POST …/cover-decision`, `POST …/decision` (legacy),
  `POST …/escalation-decision`, `POST …/escalation-cover-decision`, S6 reopen.
  Each accepts the caller's `version` (body field `expectedVersion`, or
  `If-Match` header — pick body field, simpler for the SPA). On
  `OptimisticLockException`/`ObjectOptimisticLockingFailureException` →
  HTTP 409 `{error:"CONFLICT", message:"This claim changed since you opened it. Reload and retry."}`
  via `ApiExceptionHandler` (one handler covers all).
- Stage guards run **inside** the same transaction as the write (read-check-write
  under the row lock), so a 409 never masks a guard 400 — version check first,
  then guards.

**Frontend:** every mutating form sends its loaded `version`; on 409 show a
warning banner ("Someone changed this claim — reloaded the latest") + refetch
(testid `detail-conflict-banner`). Reserve/assessment/decision forms only.

**Tests:** integration (`ClaimConcurrencyIntegrationTest`): read v0 → write v0 ok →
stale v0 write → 409; concurrent `PUT reserve` pair (second 409); stale decision
after reassign → 409 (not silent overwrite). E2E: open claim in two contexts,
save in A, save in B → banner visible.
**Done checklist:** [ ] all seven writers version-checked [ ] single 409 shape
[ ] banner + refetch on every form [ ] green suites.
**Non-goals:** live collaboration/presence, SSE/polling (queues still fetch-on-load),
field-level merge (last-writer-wins per form is correct here).

---

## S6 — Claim reopen / appeal (supervisor-only, audit-append-only)

**Goal:** new evidence, claimant disputes, regulator asks — handled inside the
system instead of "file a new claim" (which corrupts cycle-time metrics).
**Depends on:** S5 (reopen is version-checked). Plan V2 keeps "no appeals" as a
non-goal — this slice **overrides** that line; record the override in decisions.

**Migration — `V22__reopen_payments.sql`:**
```sql
-- payment today is UNIQUE(claim_id) (one payment per claim). Re-decision after
-- reopen needs history: keep row 1, add seq. Inspect \d payment first — the
-- constraint name below must match the real one.
ALTER TABLE payment ADD COLUMN seq INT NOT NULL DEFAULT 1;
ALTER TABLE payment DROP CONSTRAINT IF EXISTS payment_claim_id_key;
ALTER TABLE payment ADD CONSTRAINT payment_claim_seq_unique UNIQUE (claim_id, seq);
-- claim needs no new status: reopened claims re-enter UNDER_REVIEW[REVIEW].
```

**Backend (`StagedWorkflowService.reopen` + `POST /api/claims/{n}/reopen`, SUPERVISOR):**
- Guards: claim CLOSED (else 400); body `{rationale (≥20 chars), expectedVersion}`.
- Effect (one transaction): `decision/closed_at` stay as history on the row?
  No — reset to open: `status=UNDER_REVIEW`, `stage=REVIEW`, `decision=NULL`,
  `decision_remarks=NULL`, `closed_at=NULL`; level preserved; reassign via
  `ClaimAssigner` (skill-aware, least-loaded at claim level); audit `CLAIM_REOPENED`
  (before CLOSED → after UNDER_REVIEW + rationale); outbox `enqueueReopen`
  (new EmailOutboxWriter method, same tone as decision mails); proposals from the
  old decision stay cleared-cleared (they were applied/cleared at closure —
  the new handler assesses fresh; verification history is kept and visible).
- Next closure inserts `payment(seq = max+1)`; `payment.amount == net_payable`
  per row; totals on the claim reflect the **latest** closure.
- `SecurityConfig`: `POST …/reopen` → `hasRole("SUPERVISOR")`.

**Frontend:** supervisor-only "Reopen claim" action on closed claims
(testids `detail-reopen-toggle/rationale/confirm`) + timeline "Reopened" row +
claimant tracker "Your claim was reopened — what happens next" text. Queue
regains the claim automatically (status-driven, no UI filter change).

**Tests:** integration (`ClaimReopenIntegrationTest`): closed → reopen (open,
REVIEW, audit, outbox PENDING, assignee set) → re-decide (payment seq 2, single
latest total); guards (open-claim 400, rationale-less 400, adjuster 403,
stranger-404); E2E: supervisor reopens a decided staged claim, claimant tracker
shows reopened, adjuster re-works to closure.
**Done checklist:** [ ] terminal-closure preserved as history [ ] exactly one new
payment per re-closure [ ] audit chain legible [ ] green suites.
**Non-goals:** claimant-filed appeals (supervisor-only this slice), multi-tranche
payments (still one payment per closure), auto-reopen rules.

---

## S7 — Staff management surface (offboarding stops stranding queues)

**Goal:** a supervisor sees who exists, who's loaded, and can deactivate someone
who left — open claims move, history stays. **No Keycloak writes this slice**
(provisioning stays console + `app_user` row; the UI shows the `keycloak_sub` to
copy).

**Migration — none** (`app_user.active` exists since V12). If your DB predates
V12, stop — migrate first.

**Backend (`StaffController`/`StaffService`, SUPERVISOR-only):**
- `GET /api/staff` → `[{id, displayName, email, level, active, openClaims}]`
  (`openClaims` = `COUNT(*) claim WHERE assigned_adjuster_id AND status<>'CLOSED'`
  — the LoadBalancer's definition).
- `PUT /api/staff/{id}/active {active, expectedVersion?}` → flip; on
  deactivation: reassign each open claim via `ClaimAssigner` same-level
  least-loaded (skill-aware); claims with no eligible target park UNASSIGNED +
  reason (supervisor attention list, as V2-3); every move writes `CLAIM_REASSIGNED`
  audit rows. `app_user` needs `@Version` only if you add it — else plain update
  (single-writer admin action; document the choice).
- `SecurityConfig`: `GET/PUT /api/staff*` → supervisor.

**Frontend:** `/admin/staff` screen (supervisorGuard, route + "Supervision" nav):
table with load counts, active toggle with confirm + affected-claims warning
(testids `staff-page/row-{id}/toggle/confirm/load`). Reuse queue table grammar.

**Tests:** integration (`StaffAdminIntegrationTest`): list counts correct;
deactivate moves N claims + audit rows each + parks the unroutable one;
reactivate flips without moving; auth matrix (adjuster 403, anon 401).
E2E: supervisor deactivates an L1 with an open claim → claim appears in another
L1's queue.
**Done checklist:** [ ] no stranded queue after deactivation [ ] history intact
[ ] green suites.
**Non-goals:** Keycloak provision/deprovision API, named-adjuster assign
(reassign still takes a level, as V1), capacity targets/WLB.

---

## S8 — Structured decisions + audit export (regulator-ready rationale)

**Goal:** rationale stops being free-text-only; any audit story exports to CSV.

**Migration — `V23__decision_codes.sql`:**
```sql
ALTER TABLE claim_cover ADD COLUMN denial_reason VARCHAR(60) NULL;
-- Codes are a Java enum (no lookup table this slice):
-- NOT_COVERED | EXCLUDED_PER_CLAUSE | ABOVE_SUB_LIMIT_EXHAUSTED |
-- INSUFFICIENT_EVIDENCE | DUPLICATE_PRE_EXISTING | FRAUD_SUSPECTED_REFERRAL | OTHER
```

**Backend:**
- Validation (in `StagedWorkflowService` + `EscalationDecisionService` + legacy
  `ClaimDecisionService`): claim-level rationale **≥ 20 chars** (all closures);
  every REJECTED cover carries a denial code + remarks (OTHER still needs remarks).
  400s name the offending cover (`coverCode`) — field-level errors leave the claim open.
- Export (SUPERVISOR): `GET /api/audit/export?claimNumber=` (claim audit CSV:
  at, actor, action, before, after, rationale) + `GET /api/decisions/export?from&to`
  (closure CSV: claim, policy, product, aggregate, totals, decider, rationale,
  denial codes). Stream via `JdbcTemplate`, `text/csv`, `Content-Disposition: attachment`.
- Claimant-visible denial text = remarks (codes stay internal — wall).

**Frontend:** decision forms gain denial-reason `<select>` per rejected cover +
rationale textarea with live char count + min-length error (testids
`detail-deny-reason-{coverCode}`, `detail-rationale-count`); overview/audit panel
gains Export buttons (testids `overview-export-audit/decisions`).

**Tests:** integration (`StructuredDecisionIntegrationTest`): short-rationale 400,
codeless-reject 400, valid close persists codes, export CSV header+rows,
claimant view shows remarks but no codes. E2E: reject a cover with a code, assert
the count hint + successful closure.
**Done checklist:** [ ] 100% closures carry actor + ≥20-char rationale + codes on
rejects [ ] exports parse as CSV [ ] wall holds [ ] green suites.
**Non-goals:** appeal-letter templates, code-effectiveness reporting (leverage).

---

## S9 — GDPR + retention story (designed answer before the first EU pilot)

**Goal:** export-my-data, erasure-without-rewriting-history, and a retention
report. **Heaviest-judgment slice — stay inside the boundaries below.**

**Migration — `V24__privacy.sql`:**
```sql
CREATE TABLE privacy_request (
    id BIGSERIAL PRIMARY KEY,
    claimant_sub VARCHAR(100) NOT NULL,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('EXPORT','ERASURE')),
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED'
        CHECK (status IN ('COMPLETED')),
    handled_by VARCHAR(100) NOT NULL, handled_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_privacy_request_sub ON privacy_request (claimant_sub);
-- No PII columns are added anywhere. Retention is policy + report, not auto-delete.
```

**Backend (boundaries — do exactly this, no more):**
- `GET /api/privacy/me/export` (CLAIMANT): JSON download of own policies + claims
  + covers + attachment **metadata** (never bytes) + timeline milestones.
- `POST /api/admin/privacy/anonymize {claimantSub, rationale}` (SUPERVISOR):
  overwrites **only**: `policy.holder_name→'REDACTED'`,
  `policy.holder_email→'redacted+<sha8(sub)>@example.invalid'` (single-owner
  policies only — shared policies are 400 with an explanation),
  `claim.claimant_sub→'ANON:<sha8>'`, `claim.claimant_remarks→NULL`,
  `internal_note` bodies the subject authored → `'[redacted]'` (row kept, author
  link kept), `attachment.uploaded_by_sub→NULL` where it equals the sub.
  **Never touches:** `audit_log` (trigger-protected — don't try), payment amounts,
  decision outcomes/remarks (compliance facts), claim descriptions (loss facts).
  Writes a `privacy_request` ERASURE row + `PRIVACY_ERASURE` audit row.
- `GET /api/admin/privacy/retention-report` (SUPERVISOR): counts of closed claims
  past 6y/7y/10y windows (config `claims.retention.closed-years=7`, report-only —
  **no auto-delete this slice**).
- Timeline actors for anonymized subs render "Redacted".

**Frontend:** claimant "Download my data" button on My-claims
(testid `myclaims-export`); supervisor privacy panel with anonymize confirm +
retention table (testids `admin-privacy-anonymize/confirm/report`).

**Tests:** integration (`PrivacyIntegrationTest`): export contains own + only own;
anonymize redacts exactly the listed columns, audit intact, second run idempotent;
shared-policy 400; auth matrix. E2E: export downloads valid JSON.
**Done checklist:** [ ] export round-trips [ ] erasure preserves audit/money/decisions
[ ] report numbers correct [ ] green suites.
**Non-goals:** auto-deletion jobs, consent management, DPA paperwork (docs link only).

---

## S10 — Notifications beyond email (prefs + in-app center, SMS-ready)

**Goal:** status-check calls die: claimants get in-app movement pings + prefs;
SMS arrives later without rework. **Email outbox stays the mail spine.**

**Migration — `V25__notifications.sql`:**
```sql
CREATE TABLE notification_preference (
    claimant_sub VARCHAR(100) PRIMARY KEY,
    email_events BOOLEAN NOT NULL DEFAULT TRUE,
    inapp_events BOOLEAN NOT NULL DEFAULT TRUE,
    sms_events BOOLEAN NOT NULL DEFAULT FALSE,
    phone VARCHAR(20) NULL
);
CREATE TABLE notification (
    id BIGSERIAL PRIMARY KEY,
    claim_id BIGINT NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    claimant_sub VARCHAR(100) NOT NULL,
    channel VARCHAR(10) NOT NULL CHECK (channel IN ('INAPP')),
    event VARCHAR(40) NOT NULL,            -- FNOL_RECEIVED | ASSIGNED | NEED_INFO_SENT |
                                           -- NEED_INFO_RESPONSE | DECIDED | REOPENED | REFERRED
    title VARCHAR(160) NOT NULL, body VARCHAR(2000) NOT NULL,
    read_at TIMESTAMPTZ NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_sub ON notification (claimant_sub, created_at DESC);
```

**Backend:**
- Write INAPP rows **in the business transaction** wherever outbox emails are
  enqueued (FNOL, assignment, decision — the only three kinds `EmailOutboxWriter`
  sends today; NEED_INFO/referral/reopen claimant emails do **not** exist yet, so
  this slice also adds those outbox kinds first via `EmailOutboxWriter`, then
  mirrors each with INAPP) — same `EmailOutboxWriter`-style helper
  (`NotificationWriter`), honoring `inapp_events`.
- `GET /api/notifications/mine` (CLAIMANT, paginated envelope like R4) +
  `POST /api/notifications/{id}/read` (own only, 404 otherwise) +
  `GET/PUT /api/notifications/preferences` (own).
- `SmsSender` interface + `NoopSmsSender` (logs at info, records nothing):
  the seam for a future provider adapter; `claims.sms.enabled=false` default;
  **no provider wired this slice**.
- Bell unread count rides on the mine endpoint (`X-Total-Unread` header or
  `{content, unread}` — pick one, document in api.http).

**Frontend:** header bell with unread badge (testid `nav-notifications/count`),
notifications screen with mark-read (testids `notif-page/item/read`), prefs form
on My-claims (testids `notif-pref-email/inapp/sms/phone/save`).

**Tests:** integration (`NotificationIntegrationTest`): each event creates an
outbox row (new NEED_INFO/referral/reopen claimant kinds included) + an INAPP row
(unless opted out), read/others'-read matrix, prefs round-trip; E2E: file →
bell badge 1 → open → mark read → badge clears.
**Done checklist:** [ ] NEED_INFO/referral/reopen claimant emails exist (new outbox
kinds) [ ] every claimant event pings in-app [ ] opt-outs honored [ ] SMS
seam exists but sends nothing [ ] green suites.
**Non-goals:** push/SMS provider, email templating rework, digest mode.

---

## S11 — Locale/timezone + accessibility pass (second-customer ready)

**Goal:** no more hardcoded `₹`-concat/`yyyy-MM-dd`/server-TZ; WCAG 2.2 AA evidence.
**Do this last** — it touches shared formatters every journey pins.

**Backend + config:**
- `claims.locale.default=en-GB`, `claims.timezone.default=Europe/London`
  (`application.properties` + `.env.example`); `AgingScheduler`/`AgingService`
  evaluate in the configured zone (inject `ZoneId`, tests fix it explicitly);
  money stays NUMERIC server-side (no formatting change in JSON).

**Frontend (`format.ts` + sweep):**
- `formatMoney` → `Intl.NumberFormat(locale,{style:'currency',currency:'INR'})`;
  dates → `Intl.DateTimeFormat(locale,{day:'2-digit',month:'short',year:'numeric'})`;
  datetimes add hour/minute; aging job note in `operations.md` (schedule in tenant TZ).
- A11y pass (one keyboard-only + one screen-reader run of all journeys):
  every input labelled, wizard steps announce (`aria-current`), toasts
  `role=status`/`alert`, focus-visible everywhere, focus trap in modals/confirm,
  `prefers-reduced-motion` respected (already), contrast ≥ 4.5:1 re-audited.
  Fix list as found — no redesign, just corrections.

**Tests:** unit (formatter matrix: en-GB + one second locale e.g. de-DE renders
`1.500,00 ₹`-shape without crashing); integration (aging fires in tenant TZ with
fixed clock); E2E **pins updated**: money/date strings change shape — update the
assertions in the same slice ( journeys 4/8 `£`/`₹` literals), never loosen to
substring-match.
**Done checklist:** [ ] zero `₹`-concat/`toFixed(2)` money literals outside
`format.ts` [ ] zero hardcoded date formats [ ] keyboard-only full run passes
[ ] screen-reader run notes filed in decisions entry [ ] all suites green.
**Non-goals:** full i18n string catalog (locale-aware formats only), per-tenant
locales (single configured default — row-level comes with multi-tenancy).

---

## Build order + session map

| Session | Slice | Why here |
|---|---|---|
| 1 | S1 integrity | Unblocks real documents; tiny; no deps |
| 2 | S2 S3 | Durability; needs S1 sha semantics |
| 3 | S3 checklist | Needs S1 uploads; changes FNOL/attach |
| 4 | S4 supersede | Needs S1+S3 upload shape settled |
| 5 | S5 concurrency | Guards money paths before reopen touches them |
| 6 | S6 reopen | Needs S5 + payment-seq migration |
| 7 | S7 staff | Independent; fits after the claim machine settles |
| 8 | S8 structured | Needs stable decision paths (S5+S6) |
| 9 | S9 privacy | Needs final column knowledge (S1–S8) |
| 10 | S10 notifications | Needs all events (incl. S6 reopen) |
| 11 | S11 locale/a11y | Last — reformats strings every journey pins |

After S11 the product is pilot-complete: evidence (PDF/S3/checklist), safe
concurrency, reopen, staff ops, regulator rationale + export, GDPR answer,
notifications, locale/a11y. Remaining roadmap leverage items (tranches, full
multi-tenancy, fraud/auto-adjudication — all explicit non-goals) stay buyer-roadmap
lines, not builds.
