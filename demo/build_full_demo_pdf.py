"""OpenClaimFlow COMPLETE demo PDF — core journey + every platform capability (S1-S11).

Builds on demo/build_pdf.py: reuses its 42 screenshot pages (the claimant →
adjuster → supervisor journey), then appends a second part with one page per
plan-v3 slice (S1 evidence integrity … S11 locale/a11y) plus a closing system-map
appendix. Capability pages carry no screenshot — they describe the feature, how
to demo it live (route, testids, API), and which suite proves it.

Output: demo/OpenClaimFlow-Complete-Demo.pdf (the original OpenClaimFlow-Demo.pdf is
left untouched).

Usage:  python3 demo/build_full_demo_pdf.py   (needs e2e/shots/*.png present)
"""
import os
import sys

from PIL import Image as PILImage
from reportlab.lib.colors import HexColor
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.pdfbase.pdfmetrics import registerFont, stringWidth
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen.canvas import Canvas

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_pdf as base  # noqa: E402  (reuses PAGES, palette, wrap)

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "OpenClaimFlow-Complete-Demo.pdf")

FONT_DIR = "/usr/share/fonts/truetype/dejavu"
registerFont(TTFont("Mono", f"{FONT_DIR}/DejaVuSansMono.ttf"))
registerFont(TTFont("Mono-Bold", f"{FONT_DIR}/DejaVuSans-Bold.ttf"))
MO = "Mono"

BLUE, INK, MUTED, LINE = base.BLUE, base.INK, base.MUTED, base.LINE
W, H = A4
MARGIN, CONTENT_W, TOP, BOTTOM = base.MARGIN, base.CONTENT_W, base.TOP, base.BOTTOM


def wrap_font(text, font, size, max_w):
    words, lines, cur = text.split(), [], ""
    for word in words:
        trial = f"{cur} {word}".strip()
        if not cur or stringWidth(trial, font, size) <= max_w:
            cur = trial
        else:
            lines.append(cur)
            cur = word
    if cur:
        lines.append(cur)
    return lines


# (code, title, subtitle, what_it_is bullets, try_it_live mono lines, proved_by)
CAPABILITIES = [
    ("S1 · Evidence integrity",
     "PDF uploads with tamper-evident integrity",
     "Photos and PDFs are validated, hashed, and measured on the way in.",
     ["FNOL accepts PDFs as well as photos; magic-byte checks reject renamed executables.",
      "Every attachment stores a SHA-256 hash plus size (migration V18) — corruption or tampering is detectable.",
      "Oversized or wrong-type files are refused with a plain-words error before anything saves."],
     ["Try: File a claim → step 2 → attach a PDF alongside photos",
      "API: POST /api/claims (multipart) → attachment sha256 + size_bytes"],
     "Proved by: PhotoValidationTest (12) + AttachmentIntegrityIntegrationTest (5) — backend 241/241 at S1."),

    ("S2 · Object storage seam",
     "Filesystem today, S3 tomorrow — zero code change",
     "Evidence bytes sit behind a storage interface with two backends.",
     ["Default is the local filesystem; setting CLAIMS_S3_* env vars switches to any S3-compatible store.",
      "Ships with MinIO for local dev (:9001 console) and a backfill runner that copies existing files over.",
      "No new dependencies — signing is hand-rolled SigV4 over the JDK HTTP client."],
     ["Try: docker compose up (MinIO) → set CLAIMS_S3_ENDPOINT → restart backend",
      "Docs: docs/operations.md (S3 page)"],
     "Proved by: S3StorageIntegrationTest (5, stubbed HTTP) — backend 246/246, pom.xml diff empty."),

    ("S3 · Required-documents checklist",
     "No more NEED_INFO ping-pong",
     "Each product declares the documents a claim needs; the claim tracks them.",
     ["Health policies demand the itemised bill and discharge summary; auto demands the estimate and RC copy.",
      "The adjuster links an uploaded file to a checklist item — or waives it with a written rationale.",
      "The claimant's tracker shows the same list, so both sides see what is missing. (Migration V19.)"],
     ["Try: claim detail → checklist (testids claim-reqdocs/count/item)",
      "API: GET/POST /api/claims/{n}/required-documents…/{checkId}/link|waive"],
     "Proved by: RequiredDocumentsIntegrationTest (14) + e2e/need-info-docs.spec.ts — backend 260/260."),

    ("S4 · Document versioning",
     "Supersede, don't delete",
     "A corrected bill replaces the old one without rewriting history.",
     ["Each attachment carries a document type and an optional replaces-link (migration V20).",
      "The timeline reads '(supersedes: <old name>)' — the audit trail shows the correction, not a cover-up.",
      "Every version stays downloadable; the newest is simply the current truth."],
     ["Try: claim detail → attachment → Replace (testids detail-attach-doctype/replaces)",
      "Timeline pin: detail-timeline-supersedes"],
     "Proved by: AttachmentSupersedeIntegrationTest (6) — backend 266/266."),

    ("S5 · Concurrency safety",
     "Two editors, no silent overwrite",
     "Optimistic locking means the second saver is told, never silently merged.",
     ["Every claim carries a version number (migration V21); each write sends the version it read.",
      "A stale write gets HTTP 409 CONFLICT and a banner naming who saved first and what changed.",
      "The adjuster reloads, re-applies their edit, and saves again — nothing is lost."],
     ["Try: open one claim in two browsers → save in both → conflict banner",
      "Pin: detail-conflict-banner · API: 409 {error: CONFLICT}"],
     "Proved by: ClaimConcurrencyIntegrationTest (3) + e2e/conflict.spec.ts — backend 269/269."),

    ("S6 · Claim reopen",
     "Supervisor-only second chance, fully audited",
     "A closed claim can come back — with a reason, a new payment sequence, and a paper trail.",
     ["Only supervisors see the Reopen action; it demands a written rationale.",
      "Payments are sequenced per claim (UNIQUE(claim_id, seq), migration V22) — a reopened payout is payment #2, never a duplicate #1.",
      "The timeline notes 'Reopened — <rationale>' and the claimant is notified like any other decision."],
     ["Try: closed claim → Reopen (testids detail-reopen-toggle/rationale/confirm)",
      "API: POST /api/claims/{n}/reopen (SUPERVISOR)"],
     "Proved by: reopen E2E (live close → reopen → timeline asserts) — backend 275/275."),

    ("S7 · Staff management",
     "Offboard in one click — queues never strand",
     "Activating or deactivating an adjuster is a supervisor self-service action.",
     ["The staff page lists every adjuster with level, load, and an active toggle.",
      "Deactivated staff vanish from auto-assignment instantly; their open claims can be reassigned mid-flight.",
      "No code change, no restart, no orphaned queue. (No migration — active flag predates the slice.)"],
     ["Try: /admin/staff → toggle (testids staff-page/row-{id}/toggle/confirm)",
      "API: PUT /api/staff/{id}/active"],
     "Proved by: StaffAdminIntegrationTest (4) + e2e/staff.spec.ts — backend 279/279."),

    ("S8 · Regulator-ready decisions",
     "Structured denial codes + one-click CSV export",
     "Every rejection carries a machine-readable reason; the whole audit trail exports.",
     ["Seven denial codes (NOT_COVERED, EXCLUDED_PER_CLAUSE, ABOVE_SUB_LIMIT_EXHAUSTED, "
      "INSUFFICIENT_EVIDENCE, DUPLICATE_PRE_EXISTING, FRAUD_SUSPECTED_REFERRAL, OTHER).",
      "Rejection remarks stay human (≥ 20 chars) — the claimant sees remarks, never the code.",
      "Supervisors export the audit log and the decision register as CSV straight from the overview. (Migration V23.)"],
     ["Try: cover decision → reason picker (detail-deny-reason-{code})",
      "API: GET /api/audit/export + /api/decisions/export (text/csv)"],
     "Proved by: StructuredDecisionIntegrationTest (5) + e2e/structured-decision.spec.ts — 284/284."),

    ("S9 · Privacy (GDPR)",
     "Self-export, anonymize, retention report",
     "A designed answer to the first EU pilot's questionnaire, before it is asked.",
     ["Any claimant can download every byte held about them (My claims → Export).",
      "Supervisors anonymize a data subject on request (name, email, phone, documents scrubbed; claim facts kept).",
      "The retention report lists closed claims by age bucket against the 7-year policy. (Migration V24.)"],
     ["Try: /admin/privacy (testids admin-privacy-anonymize/confirm/report)",
      "API: GET /api/privacy/me/export · POST /api/admin/privacy/anonymize"],
     "Proved by: PrivacyIntegrationTest (5×3) + e2e/privacy.spec.ts — backend 289/289."),

    ("S10 · Notifications center",
     "Bell, preferences, and an outbox that never loses mail",
     "Claimants are told what happened — in-app, by email, or by SMS.",
     ["A bell with an unread count sits in the nav; the notification center lists every event with read state.",
      "Each user picks email / in-app / SMS per event type (plus phone number) — preferences are honoured server-side.",
      "Every notification also lands in the transactional outbox, so a mail-server hiccup retries instead of losing. (Migration V25.)"],
     ["Try: bell (nav-notifications/count) → /notifications (notif-page/item/read)",
      "Prefs: notif-pref-email/inapp/sms/phone/save"],
     "Proved by: NotificationIntegrationTest (4) + e2e/notifications.spec.ts — backend 293/293."),

    ("S11 · Locale & accessibility",
     "Second-customer ready: INR, en-GB, keyboard-first",
     "Money, dates, and day-boundaries follow the tenant — and the app works without a mouse.",
     ["All money renders as ₹1,500.00 via shared Intl formatters; dates are en-GB; SLA/aging cut at Europe/London midnights.",
      "Full keyboard run passes (38/38 controls): focus trap in dialogs, roles and labels for screen readers.",
      "No migration — pure frontend + scheduler/zone config (claims.locale/timezone.default)."],
     ["Try: keyboard-only FNOL → queue → decision; switch tenant timezone in .env",
      "Pin: queue.spec asserts the exact string ₹1,500.00"],
     "Proved by: aging TZ + day-count tests — backend 295/295, frontend build green."),
]


def header(c, i, total, section):
    c.setFillColor(BLUE)
    c.setFont(base.FB, 9)
    c.drawString(MARGIN, TOP, section)
    c.setFillColor(MUTED)
    c.setFont(base.F, 9)
    c.drawRightString(W - MARGIN, TOP, f"{i} / {total}")
    c.setStrokeColor(LINE)
    c.setLineWidth(0.6)
    c.line(MARGIN, TOP - 8, W - MARGIN, TOP - 8)


def footer(c, i, total):
    c.setFillColor(MUTED)
    c.setFont(base.F, 8)
    c.drawCentredString(W / 2, 12 * mm, f"OpenClaimFlow complete demo  •  page {i} of {total}")


def divider(c, i, total, title, subtitle, bullets):
    header(c, i, total, "OpenClaimFlow — complete demo")
    y = TOP - 60
    c.setFillColor(BLUE)
    c.setFont(base.FB, 26)
    c.drawString(MARGIN, y, title)
    y -= 28
    c.setFillColor(MUTED)
    c.setFont(base.F, 12)
    for line in wrap_font(subtitle, base.F, 12, CONTENT_W):
        c.drawString(MARGIN, y, line)
        y -= 17
    y -= 14
    c.setFont(base.F, 10.5)
    c.setFillColor(MUTED)
    for b in bullets:
        for j, line in enumerate(wrap_font(b, base.F, 10.5, CONTENT_W - 16)):
            c.drawString(MARGIN, y, ("•  " if j == 0 else "     ") + line)
            y -= 15
        y -= 4
    footer(c, i, total)
    c.showPage()


def capability_page(c, i, total, code_title, subtitle, whats, try_lines, proved):
    header(c, i, total, "OpenClaimFlow — complete demo · platform capabilities")
    y = TOP - 34
    c.setFillColor(INK)
    c.setFont(base.FB, 16)
    for line in wrap_font(code_title, base.FB, 16, CONTENT_W):
        c.drawString(MARGIN, y, line)
        y -= 20
    c.setFillColor(MUTED)
    c.setFont(base.F, 10)
    for line in wrap_font(subtitle, base.F, 10, CONTENT_W):
        c.drawString(MARGIN, y, line)
        y -= 14
    y -= 6
    # What it is
    c.setFillColor(BLUE)
    c.setFont(base.FB, 11)
    c.drawString(MARGIN, y, "What it is")
    y -= 17
    c.setFillColor(INK)
    c.setFont(base.F, 10)
    for b in whats:
        for j, line in enumerate(wrap_font(b, base.F, 10, CONTENT_W - 16)):
            c.drawString(MARGIN, y, ("•  " if j == 0 else "     ") + line)
            y -= 14
        y -= 3
    y -= 6
    # Try-it-live box
    c.setFillColor(BLUE)
    c.setFont(base.FB, 11)
    c.drawString(MARGIN, y, "Try it live")
    y -= 16
    box_top = y + 8
    c.setFont(MO, 8.5)
    for t in try_lines:
        for line in wrap_font(t, MO, 8.5, CONTENT_W - 20):
            y -= 12
            c.setFillColor(INK)
            c.drawString(MARGIN + 10, y, line)
    y -= 8
    c.setStrokeColor(LINE)
    c.setLineWidth(0.6)
    c.rect(MARGIN, y, CONTENT_W, box_top - y, stroke=1, fill=0)
    y -= 20
    # Proved by
    c.setFillColor(MUTED)
    c.setFont(base.F, 9.5)
    c.setFont(base.FB, 9.5)
    c.drawString(MARGIN, y, "Proof: ")
    pw = c.stringWidth("Proof: ", base.FB, 9.5)
    c.setFont(base.F, 9.5)
    first = True
    for line in wrap_font(proved, base.F, 9.5, CONTENT_W - pw - 4):
        c.drawString(MARGIN + (pw if first else 0), y, line)
        y -= 13
        first = False
    footer(c, i, total)
    c.showPage()


def appendix(c, i, total):
    header(c, i, total, "OpenClaimFlow — complete demo · appendix")
    y = TOP - 34
    c.setFillColor(INK)
    c.setFont(base.FB, 16)
    c.drawString(MARGIN, y, "Appendix · system map")
    y -= 24
    sections = [
        ("Stack", ["Angular SPA (frontend/) → Spring Boot 3 / Java 21 (backend/) → PostgreSQL 16 + Flyway (V1–V25).",
                   "Keycloak OIDC (:8090, realm claims) · Mailpit SMTP :1025 / UI :8025 · MinIO :9001 (dev S3).",
                   "Ports: frontend :4200 · backend :8081 · backend+E2E hermetic pair :8082/:4200, DB claims_e2e."]),
        ("Demo accounts (dev defaults)", ["Claimant ada.lovelace / claims-Pass-123 — POL-10001 HLTH-PLUS, ₹10,00,000 SI.",
                   "Adjusters adjuster.one/.two (L1, ₹1,00,000) · adjuster.three (L2, ₹4,00,000) / adjuster-Pass-123.",
                   "Supervisor supervisor / supervisor-Pass-123 — cross-book views, reassign, reopen, exports."]),
        ("Authority ladder", ["L1 ₹1,00,000 < L2 ₹4,00,000 < L3 < supervisor — per product, editable at /admin/authority.",
                   "Inside limit closes + pays; above limit saves a proposal and refers upward."]),
        ("Key endpoints", ["POST /api/claims (multipart FNOL) · GET /api/claims/mine · GET /api/claims/{n}/full",
                   "POST …/review|verifications|assessment|send-back|cover-decision|refer|reopen|decision",
                   "GET /api/queue · /api/dashboard · /api/escalations · /api/outbox · /api/notifications/mine",
                   "GET /api/audit/export · /api/decisions/export · /api/privacy/me/export · PUT /api/staff/{id}/active"]),
        ("Proof totals", ["Backend 295/295 green · frontend build green · hermetic Playwright E2E green "
                   "(fnol, covers, queue, staged, conflict, reopen, staff, structured-decision, privacy, notifications, need-info-docs)."]),
    ]
    for title, lines in sections:
        c.setFillColor(BLUE)
        c.setFont(base.FB, 11)
        c.drawString(MARGIN, y, title)
        y -= 16
        c.setFillColor(INK)
        c.setFont(base.F, 9.5)
        for b in lines:
            for j, line in enumerate(wrap_font(b, base.F, 9.5, CONTENT_W - 16)):
                c.drawString(MARGIN, y, ("•  " if j == 0 else "     ") + line)
                y -= 13
            y -= 2
        y -= 6
    footer(c, i, total)
    c.showPage()


def build():
    missing = [shot for shot, *_ in base.PAGES
               if not os.path.isfile(os.path.join(base.SHOTS, shot))]
    if missing:
        raise SystemExit(f"missing screenshots: {missing} — run shots.spec.ts first")
    n_shots = len(base.PAGES)
    # cover + shots + divider + capabilities + appendix
    total = 1 + n_shots + 1 + len(CAPABILITIES) + 1
    c = Canvas(OUT, pagesize=A4)
    n = 1

    # ---- cover ----
    c.setFillColor(BLUE)
    c.setFont(base.FB, 30)
    c.drawCentredString(W / 2, H / 2 + 52, "OpenClaimFlow")
    c.setFillColor(MUTED)
    c.setFont(base.F, 13)
    c.drawCentredString(W / 2, H / 2 + 18, "The complete demo — every role, every capability")
    c.setFont(base.F, 10)
    c.drawCentredString(W / 2, H / 2 - 10,
                        f"Part A: {n_shots} screenshots from the running product, in user order")
    c.drawCentredString(W / 2, H / 2 - 28,
                        f"Part B: {len(CAPABILITIES)} platform capabilities (evidence → locale/a11y) + system map")
    c.setFont(base.F, 9)
    c.drawCentredString(W / 2, H / 2 - 52,
                        "Claimant files and tracks  →  adjuster assesses and decides  →  supervisor oversees")
    # role legend
    c.setFont(base.F, 9)
    for k, label in enumerate(["Claimant — file + track", "Adjuster — assess + decide",
                                       "Supervisor — oversee + administer"]):
        c.setFillColor([HexColor("#0EA5E9"), HexColor("#8B5CF6"), HexColor("#059669")][k])
        c.circle(W / 2 - 150, H / 2 - 76 - k * 16, 4, stroke=0, fill=1)
        c.setFillColor(MUTED)
        c.drawString(W / 2 - 141, H / 2 - 73 - k * 16, label)
    footer(c, n, total)
    c.showPage()
    n += 1

    # ---- Part A: one page per screenshot (same renderer as build_pdf) ----
    for (shot, title, subtitle, bullets) in base.PAGES:
        header(c, n, total, "OpenClaimFlow — complete demo · A. the journey")
        y = TOP - 34
        c.setFillColor(INK)
        c.setFont(base.FB, 16)
        c.drawString(MARGIN, y, title)
        y -= 18
        c.setFillColor(MUTED)
        c.setFont(base.F, 10)
        c.drawString(MARGIN, y, subtitle)
        y -= 12
        groups = [base.wrap(b, 9.5, CONTENT_W - 16) for b in bullets]
        text_h = sum(len(g) for g in groups) * 13 + 10
        img_top, img_bottom = y, BOTTOM + text_h
        img = PILImage.open(os.path.join(base.SHOTS, shot))
        iw, ih = img.size
        scale = min(CONTENT_W / iw, (img_top - img_bottom) / ih)
        dw, dh = iw * scale, ih * scale
        c.drawImage(os.path.join(base.SHOTS, shot), MARGIN + (CONTENT_W - dw) / 2,
                    img_bottom, dw, dh, preserveAspectRatio=True, anchor="c")
        y = img_bottom - 12
        c.setFont(base.F, 9.5)
        c.setFillColor(MUTED)
        for g in groups:
            for j, line in enumerate(g):
                c.drawString(MARGIN, y, ("•  " if j == 0 else "     ") + line)
                y -= 13
            y -= 2
        footer(c, n, total)
        c.showPage()
        n += 1

    # ---- divider ----
    divider(c, n, total, "Part B — platform", "Under the screens: the eleven capabilities that make it production-grade.",
            ["Each page: what the capability is, how to demo it live, and the suite that proves it.",
             "No extra screenshots needed — these are best shown live in the running app."])
    n += 1

    # ---- Part B ----
    for cap in CAPABILITIES:
        capability_page(c, n, total, f"{cap[0]} — {cap[1]}", *cap[2:])
        n += 1

    appendix(c, n, total)
    c.save()
    print(f"wrote {OUT} ({total} pages)")


if __name__ == "__main__":
    build()
