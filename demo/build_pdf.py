"""Technical sales demo PDF for the Claim Processing System.

Generates demo/ClaimFlow-Demo.pdf — a visual, showable walkthrough:
cover, pipeline diagram, per-actor screens (described as wireframe panels),
live-demo script, trust/engineering page, roadmap/pricing-close page.
Pure reportlab vector drawing (no screenshots needed).
"""
import os

from reportlab.lib.colors import HexColor, white, black
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.pdfgen.canvas import Canvas

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ClaimFlow-Demo.pdf")

# --- brand (matches app: corporate blue #0056B3 on cool-grey canvas) ---
BLUE = HexColor("#0056B3")
BLUE_DARK = HexColor("#003E82")
BLUE_TINT = HexColor("#E8F0FA")
INK = HexColor("#0F172A")
MUTED = HexColor("#64748B")
FAINT = HexColor("#94A3B8")
CANVAS_BG = HexColor("#F1F5F9")
CARD = white
LINE = HexColor("#E2E8F0")
GREEN = HexColor("#15803D")
GREEN_BG = HexColor("#ECFDF5")
AMBER = HexColor("#B45309")
AMBER_BG = HexColor("#FFFBEB")
RED = HexColor("#B91C1C")
RED_BG = HexColor("#FEF2F2")
SLATE_BG = HexColor("#F8FAFC")

W, H = A4
MARGIN = 18 * mm


def pill(c, x, y, w, h, text, fg, bg, size=7.5):
    c.setFillColor(bg)
    c.setStrokeColor(fg)
    c.setLineWidth(0.6)
    c.roundRect(x, y, w, h, h / 2, fill=1, stroke=1)
    c.setFillColor(fg)
    c.setFont("Helvetica-Bold", size)
    c.drawCentredString(x + w / 2, y + h / 2 - size / 2.8, text)


def card(c, x, y, w, h, radius=6):
    c.setFillColor(CARD)
    c.setStrokeColor(LINE)
    c.setLineWidth(0.7)
    c.roundRect(x, y, w, h, radius, fill=1, stroke=1)


def h1(c, y, text):
    c.setFillColor(INK)
    c.setFont("Helvetica-Bold", 21)
    c.drawString(MARGIN, y, text)
    return y - 8


def h2(c, y, text):
    c.setFillColor(INK)
    c.setFont("Helvetica-Bold", 13)
    c.drawString(MARGIN, y, text)
    return y - 6


def body(c, y, text, size=9.5, color=MUTED, leading=13):
    c.setFillColor(color)
    c.setFont("Helvetica", size)
    for line in text.split("\n"):
        c.drawString(MARGIN, y, line)
        y -= leading
    return y


def bullets(c, y, items, size=9.5, leading=13.5, bullet="•  "):
    c.setFont("Helvetica", size)
    for bold_head, rest in items:
        c.setFillColor(BLUE)
        c.drawString(MARGIN, y, bullet)
        x = MARGIN + 10
        if bold_head:
            c.setFillColor(INK)
            c.setFont("Helvetica-Bold", size)
            c.drawString(x, y, bold_head)
            x += c.stringWidth(bold_head, "Helvetica-Bold", size) + 3
        c.setFillColor(MUTED)
        c.setFont("Helvetica", size)
        c.drawString(x, y, rest)
        y -= leading
    return y


def footer(c, num, total, light=False):
    c.setFillColor(HexColor("#BFD7F2") if light else FAINT)
    c.setFont("Helvetica", 8)
    c.drawString(MARGIN, 12 * mm, "ClaimFlow  •  Technical sales demo  •  Confidential")
    c.drawRightString(W - MARGIN, 12 * mm, f"{num} / {total}")


def topbar(c):
    c.setFillColor(BLUE)
    c.rect(0, H - 14 * mm, W, 14 * mm, fill=1, stroke=0)
    c.setFillColor(white)
    c.setFont("Helvetica-Bold", 11)
    c.drawString(MARGIN, H - 9 * mm, "ClaimFlow")
    c.setFont("Helvetica", 9)
    c.drawRightString(W - MARGIN, H - 9 * mm, "Claims Processing System  •  Live demo deck")


TOTAL = 8


def p1_cover(c):
    c.setFillColor(BLUE)
    c.rect(0, 0, W, H, fill=1, stroke=0)
    c.setFillColor(HexColor("#0B2A4A"))
    c.rect(0, 0, W, 62 * mm, fill=1, stroke=0)
    c.setFillColor(white)
    c.setFont("Helvetica-Bold", 34)
    c.drawString(MARGIN, H - 70 * mm, "From first notice")
    c.drawString(MARGIN, H - 82 * mm, "to closed claim.")
    c.setFillColor(HexColor("#BFD7F2"))
    c.setFont("Helvetica", 12)
    c.drawString(MARGIN, H - 94 * mm, "A live, working claims pipeline — claimant to adjuster to supervisor,")
    c.drawString(MARGIN, H - 100 * mm, "with the authority gate, the visibility wall, and the audit trail enforcing every step.")
    # stat chips
    y = H - 118 * mm
    for label, value in [("Backend tests", "182 green"), ("E2E journeys", "15"), ("Migrations", "V1-V11"),
                         ("Decision emails lost", "0 — outbox")]:
        c.setFillColor(white)
        c.roundRect(MARGIN, y - 2, 118, 20, 5, fill=1, stroke=0)
        c.setFillColor(BLUE)
        c.setFont("Helvetica-Bold", 10)
        c.drawString(MARGIN + 8, y + 8, value)
        c.setFillColor(HexColor("#334155"))
        c.setFont("Helvetica", 8)
        c.drawString(MARGIN + 8, y + 1, label)
        MARGIN_X = MARGIN
        globals()["MARGIN"] = MARGIN_X + 124
    globals()["MARGIN"] = 18 * mm
    c.setFillColor(HexColor("#BFD7F2"))
    c.setFont("Helvetica", 9.5)
    c.drawString(MARGIN, 78 * mm, "This deck is a show-script: each page is a 2-minute live demo beat.")
    c.drawString(MARGIN, 72 * mm, "Run it with npm run demo:seed, then follow the red DEMO cues.")
    c.setFillColor(white)
    c.setFont("Helvetica-Bold", 10)
    c.drawString(MARGIN, 30 * mm, "CONFIDENTIAL  •  Prepared for carrier evaluation")
    footer(c, 1, TOTAL, light=True)


def pipeline(c, y, steps):
    """Horizontal chevron pipeline across the content width."""
    x0 = MARGIN
    total_w = W - 2 * MARGIN
    n = len(steps)
    gap = 4
    sw = (total_w - gap * (n - 1)) / n
    cy = y - 16
    for i, (label, sub, col) in enumerate(steps):
        x = x0 + i * (sw + gap)
        c.setFillColor(col)
        c.setStrokeColor(col)
        c.roundRect(x, cy - 14, sw, 30, 5, fill=1, stroke=0)
        c.setFillColor(white)
        c.setFont("Helvetica-Bold", 8.5)
        c.drawCentredString(x + sw / 2, cy + 5, label)
        c.setFont("Helvetica", 7)
        c.drawCentredString(x + sw / 2, cy - 6, sub)
        if i < n - 1:
            c.setFillColor(BLUE)
            c.setFont("Helvetica-Bold", 11)
            c.drawCentredString(x + sw + gap / 2, cy - 3, "›")
    return cy - 22


def p2_pipeline(c):
    topbar(c)
    y = H - 26 * mm
    y = h1(c, y, "One claim, one pipeline, zero spreadsheets")
    y = body(c, y - 4, "Every claim walks the same enforced path. No side channels, no email-and-hope.", leading=13)
    y -= 4
    y = pipeline(c, y, [
        ("FNOL", "claimant files", BLUE),
        ("TRIAGE", "L1 / L2 route", BLUE),
        ("WORK", "reserve + notes", BLUE),
        ("DECIDE", "gate enforced", AMBER),
        ("CLOSE", "pay + notify", GREEN),
    ])
    y = h2(c, y - 2, "The three guarantees (say these out loud)")
    y = bullets(c, y - 4, [
        ("Authority gate — ", "L1 < L2 < supervisor. Above-limit approvals escalate, never grant."),
        ("Visibility wall — ", "claimants never see reserve, notes, assignee, or coverage. DTO-layer, tested."),
        ("Audit immutability — ", "append-only log (DB trigger); every decision has actor + rationale."),
    ])
    y -= 4
    card(c, MARGIN, y - 34, W - 2 * MARGIN, 34)
    c.setFillColor(RED)
    c.setFont("Helvetica-Bold", 9)
    c.drawString(MARGIN + 8, y - 12, "DEMO CUE  •  30 seconds")
    c.setFillColor(INK)
    c.setFont("Helvetica", 9)
    c.drawString(MARGIN + 8, y - 22, "Open the supervisor Overview: 8 live aggregates. That is the whole book at a glance.")
    footer(c, 2, TOTAL)


def screen_mock(c, x, y, w, h, title, rows, pills=None):
    """Generic app-screen wireframe: top bar, sidebar dots, banner, table rows."""
    card(c, x, y, w, h, radius=7)
    # top bar
    c.setFillColor(SLATE_BG)
    c.roundRect(x + 1, y + h - 20, w - 2, 19, 4, fill=1, stroke=0)
    c.setFillColor(BLUE)
    c.setFont("Helvetica-Bold", 7.5)
    c.drawString(x + 8, y + h - 13, "ClaimFlow")
    c.setFillColor(MUTED)
    c.setFont("Helvetica", 6.5)
    c.drawRightString(x + w - 8, y + h - 13, title)
    # sidebar
    c.setFillColor(BLUE_TINT)
    c.roundRect(x + 6, y + 8, 34, h - 34, 4, fill=1, stroke=0)
    for i in range(4):
        c.setFillColor(BLUE if i == 1 else FAINT)
        c.roundRect(x + 10, y + h - 40 - i * 12, 26, 7, 3, fill=1, stroke=0)
    # banner
    c.setFillColor(BLUE)
    c.roundRect(x + 46, y + h - 44, w - 54, 22, 4, fill=1, stroke=0)
    c.setFillColor(white)
    c.setFont("Helvetica-Bold", 8)
    c.drawString(x + 52, y + h - 30, title)
    # rows
    ry = y + h - 56
    for i, (rid, stat, lvl) in enumerate(rows):
        if ry < y + 26:
            break
        c.setStrokeColor(LINE)
        c.setLineWidth(0.5)
        c.line(x + 46, ry, x + w - 8, ry)
        c.setFillColor(INK)
        c.setFont("Helvetica-Bold", 7)
        c.drawString(x + 50, ry - 9, rid)
        c.setFillColor(MUTED)
        c.setFont("Helvetica", 6.5)
        c.drawString(x + 110, ry - 9, lvl)
        scol = GREEN if stat in ("APPROVED", "ACTIVE", "SENT") else (AMBER if stat in ("UNDER_REVIEW", "PENDING") else (RED if stat in ("DENIED", "FAILED") else BLUE_DARK))
        sbg = GREEN_BG if scol == GREEN else (AMBER_BG if scol == AMBER else (RED_BG if scol == RED else BLUE_TINT))
        pill(c, x + w - 62, ry - 13, 52, 10, stat, scol, sbg, size=6)
        ry -= 15
    if pills:
        for i, (t, fg, bg) in enumerate(pills):
            pill(c, x + 50 + i * 62, y + 10, 58, 11, t, fg, bg, size=6)


def p3_claimant(c):
    topbar(c)
    y = H - 26 * mm
    y = h1(c, y, "Claimant: file in minutes, track without calling")
    screen_mock(c, MARGIN, y - 108, W - 2 * MARGIN, 102, "File a claim  •  Track CLM-000041",
                [("CLM-000041", "UNDER_REVIEW", "HOME  •  step 2 of 4"),
                 ("CLM-000038", "APPROVED", "AUTO  •  £1,500.00 paid"),
                 ("CLM-000035", "DENIED", "with remarks")],
                pills=[("Steps, not secrets", BLUE, BLUE_TINT), ("Amount only if won", GREEN, GREEN_BG)])
    y -= 112
    y = bullets(c, y, [
        ("2-step FNOL wizard — ", "policy lookup + holder check, photos (5 x 10 MB), claim number instantly."),
        ("Status screen shows steps — ", "never reserve, notes, assignee, or coverage (wall holds on closed claims too)."),
        ("My claims history — ", "newest first; decision amount or denial remarks inline."),
        ("Emails that can't get lost — ", "FNOL / assignment / decision via outbox with retry (R2)."),
    ])
    y -= 2
    c.setFillColor(RED)
    c.setFont("Helvetica-Bold", 9)
    c.drawString(MARGIN, y, "DEMO CUE  •  file a live FNOL against POL-10001, watch the claim number + Mailpit email.")
    footer(c, 3, TOTAL)


def p4_adjuster(c):
    topbar(c)
    y = H - 26 * mm
    y = h1(c, y, "Adjuster: a queue that works top-down")
    screen_mock(c, MARGIN, y - 108, W - 2 * MARGIN, 102, "My queue  •  adjuster.one (L1)",
                [("CLM-000041", "UNDER_REVIEW", "oldest first"),
                 ("CLM-000044", "UNDER_REVIEW", "server search"),
                 ("CLM-000047", "UNASSIGNED", "load-balanced")],
                pills=[("Least-loaded routing", BLUE, BLUE_TINT), ("25 / page, search server-side", BLUE, BLUE_TINT)])
    y -= 112
    y = bullets(c, y, [
        ("Role-driven queue — ", "own claims for adjusters, team view for supervisors; paginated, searchable."),
        ("Claim detail — ", "coverage, reserve form, internal notes, photo downloads, decision panel."),
        ("Reserve is a free estimate — ", "only the indemnity payment is authority-gated."),
        ("Above-limit? — ", "one click escalates to L2 or supervisor. No self-approval, ever."),
    ])
    y -= 2
    c.setFillColor(RED)
    c.setFont("Helvetica-Bold", 9)
    c.drawString(MARGIN, y, "DEMO CUE  •  set a reserve, add a note, then approve £1,500 — claim closes + payment recorded.")
    footer(c, 4, TOTAL)


def p5_gate(c):
    topbar(c)
    y = H - 26 * mm
    y = h1(c, y, "The authority gate: the moment buyers lean in")
    # gate diagram: amount vs ladder
    card(c, MARGIN, y - 84, W - 2 * MARGIN, 78, radius=7)
    bx = MARGIN + 10
    by = y - 68
    # ladder bars
    for i, (lvl, amt, col) in enumerate([("L1", "<= £2,500", GREEN), ("L2", "<= £10,000", BLUE), ("Supervisor", "unlimited", HexColor("#6D28D9"))]):
        c.setFillColor(col)
        c.roundRect(bx, by + i * 19, 112, 15, 4, fill=1, stroke=0)
        c.setFillColor(white)
        c.setFont("Helvetica-Bold", 8)
        c.drawString(bx + 8, by + i * 19 + 5, f"{lvl}   {amt}")
    # arrows + outcomes
    c.setFillColor(INK)
    c.setFont("Helvetica-Bold", 8)
    c.drawString(bx + 126, by + 44, "£1,500 as L1  ->  APPROVED, payment recorded, CLOSED")
    c.drawString(bx + 126, by + 25, "£8,000 as L1  ->  ESCALATED to L2 (least-loaded)")
    c.drawString(bx + 126, by + 6, "£12,000 / 5 days old  ->  ESCALATED_SUPERVISOR")
    c.setFillColor(MUTED)
    c.setFont("Helvetica", 7.5)
    c.drawString(bx, by - 12, "Limits live per product code: the next decision uses the new ladder immediately (no cache).")
    y -= 90
    y = bullets(c, y, [
        ("Try to break it — ", "approve £12,000 as L1: blocked, escalated, audit row names you."),
        ("Aging ladder — ", "3 days -> L2, 5 days -> supervisor; claimant sees the escalation."),
        ("Single-payment atomicity — ", "decision + payment + closure in one transaction (row-locked)."),
    ])
    y -= 2
    c.setFillColor(RED)
    c.setFont("Helvetica-Bold", 9)
    c.drawString(MARGIN, y, "DEMO CUE  •  attempt the above-limit approval. The block IS the feature — narrate it.")
    footer(c, 5, TOTAL)


def p6_supervisor(c):
    topbar(c)
    y = H - 26 * mm
    y = h1(c, y, "Supervisor: run the book, prove compliance")
    screen_mock(c, MARGIN, y - 108, W - 2 * MARGIN, 102, "Overview  •  Escalations  •  Policies  •  Outbox",
                [("ESCALATED x3", "UNDER_REVIEW", "waiting on you"),
                 ("OUTBOX failed x0", "SENT", "all delivered"),
                 ("POL-E2E-01", "ACTIVE", "imported book")],
                pills=[("Reassign", BLUE, BLUE_TINT), ("Audit export", BLUE, BLUE_TINT), ("Authority editor", BLUE, BLUE_TINT)])
    y -= 112
    y = bullets(c, y, [
        ("Overview — ", "8 aggregates: open, unassigned, escalations, aging pressure, approved this month."),
        ("Policy book — ", "create + CSV import (per-row errors) + retire. Load a carrier's book live on the call."),
        ("Email outbox — ", "every notice PENDING -> SENT, failures retried; prove notification in diligence."),
        ("Audit trail — ", "immutable (DB trigger), per-claim view + export for the regulator."),
    ])
    y -= 2
    c.setFillColor(RED)
    c.setFont("Helvetica-Bold", 9)
    c.drawString(MARGIN, y, "DEMO CUE  •  import the 3-row CSV (1 bad row) — the error row is the trust moment.")
    footer(c, 6, TOTAL)


def p7_engineering(c):
    topbar(c)
    y = H - 26 * mm
    y = h1(c, y, "Engineered to survive diligence")
    y = bullets(c, y - 2, [
        ("182 backend tests green — ", "gate matrix, wall-shape tests, trigger test, Mailpit delivery pins."),
        ("15 E2E journeys, hermetic — ", "own backend :8082 + frontend :4200; boots its own world on any laptop."),
        ("Migrations V1-V11, append-only — ", "Flyway checksums load-bearing; V1-V8 never edited."),
        ("Ops runbook + api.http — ", "restore-pairing gate, alert table, onboarding checklist, example per endpoint."),
        ("Infra you already have — ", "Docker + Postgres + Keycloak + Mailpit. Realm-per-carrier tenancy."),
        ("No lock-in surprises — ", "photo-storage interface (S3 path needs no schema change), OIDC-standard auth."),
    ])
    y -= 6
    card(c, MARGIN, y - 40, W - 2 * MARGIN, 40)
    c.setFillColor(INK)
    c.setFont("Helvetica-Bold", 9.5)
    c.drawString(MARGIN + 8, y - 14, "Answer before they ask: 404-not-403 (existence never leaks)  •  rationale required  •  closed is terminal")
    c.setFillColor(MUTED)
    c.setFont("Helvetica", 8.5)
    c.drawString(MARGIN + 8, y - 25, "Single-payment atomicity  •  per-claimant + IP flood guards  •  request-id tracing with user-safe references")
    footer(c, 7, TOTAL)


def p8_close(c):
    c.setFillColor(BLUE)
    c.rect(0, 0, W, H, fill=1, stroke=0)
    c.setFillColor(white)
    c.setFont("Helvetica-Bold", 24)
    c.drawString(MARGIN, H - 60 * mm, "Pilot in weeks,")
    c.drawString(MARGIN, H - 71 * mm, "not quarters.")
    c.setFillColor(HexColor("#BFD7F2"))
    c.setFont("Helvetica", 11)
    c.drawString(MARGIN, H - 83 * mm, "Dedicated realm + database per carrier. Your policies imported on the first call.")
    c.drawString(MARGIN, H - 89 * mm, "Your adjusters working their own queue the same afternoon.")
    y = H - 104 * mm
    for title, desc in [("Week 1 — your book, live", "Onboarding checklist: realm -> staff -> authority ladder -> CSV import -> demo seed."),
                        ("Weeks 2-5 — paid pilot", "Reopen/appeal, staff roster, notification prefs, audit export, a11y evidence."),
                        ("After customer two — scale", "Tranches, row-level tenancy, SMS, coverage rules — priced roadmap, not surprises.")]:
        c.setFillColor(white)
        c.roundRect(MARGIN, y - 24, W - 2 * MARGIN, 30, 6, fill=1, stroke=0)
        c.setFillColor(BLUE)
        c.setFont("Helvetica-Bold", 10)
        c.drawString(MARGIN + 8, y - 6, title)
        c.setFillColor(HexColor("#334155"))
        c.setFont("Helvetica", 8.5)
        c.drawString(MARGIN + 8, y - 16, desc)
        y -= 36
    c.setFillColor(white)
    c.setFont("Helvetica-Bold", 12)
    c.drawString(MARGIN, 34 * mm, "Next step: name the pilot book. We import it together — live.")
    c.setFont("Helvetica", 9)
    c.setFillColor(HexColor("#BFD7F2"))
    c.drawString(MARGIN, 27 * mm, "ClaimFlow  •  demo/ClaimFlow-Demo.pdf  •  Companion: README + docs/operations.md")
    footer(c, 8, TOTAL, light=True)


def build():
    c = Canvas(OUT, pagesize=A4)
    c.setTitle("ClaimFlow — Technical Sales Demo")
    c.setAuthor("ClaimFlow")
    for fn in (p1_cover, p2_pipeline, p3_claimant, p4_adjuster, p5_gate, p6_supervisor, p7_engineering, p8_close):
        # grey canvas backdrop for inner pages
        if fn is not p1_cover and fn is not p8_close:
            c.setFillColor(CANVAS_BG)
            c.rect(0, 0, W, H, fill=1, stroke=0)
        fn(c)
        c.showPage()
    c.save()
    print(f"wrote {OUT} ({os.path.getsize(OUT)} bytes)")


if __name__ == "__main__":
    build()
