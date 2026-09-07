"""ClaimFlow demo PDF — simple walkthrough: one screenshot per page, big and clear.

Generates demo/ClaimFlow-Demo.pdf from e2e/shots/*.png (captured by
e2e/tests/shots.spec.ts via `npx playwright test --config shots.config.ts`
from e2e/ after `npm run demo:seed`).

Layout per page: small header, title + one-line subtitle, screenshot as
large as the page allows, short explanation bullets underneath.
"""
import os

from PIL import Image as PILImage
from reportlab.lib.colors import HexColor
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.pdfbase.pdfmetrics import registerFont, stringWidth
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen.canvas import Canvas

# Embedded DejaVu so the rupee sign renders on any viewer (base-14
# Helvetica leaves U+20B9 to viewer fallback — tofu on bare Linux readers).
FONT_DIR = "/usr/share/fonts/truetype/dejavu"
registerFont(TTFont("Body", f"{FONT_DIR}/DejaVuSans.ttf"))
registerFont(TTFont("Body-Bold", f"{FONT_DIR}/DejaVuSans-Bold.ttf"))
F, FB = "Body", "Body-Bold"

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ClaimFlow-Demo.pdf")
SHOTS = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "e2e", "shots"))

BLUE = HexColor("#0056B3")
INK = HexColor("#0F172A")
MUTED = HexColor("#64748B")
LINE = HexColor("#E2E8F0")

W, H = A4
MARGIN = 15 * mm
CONTENT_W = W - 2 * MARGIN
TOP = H - 15 * mm
BOTTOM = 18 * mm  # footer lives below this


def wrap(text, size, max_w):
    """Greedy word-wrap into lines that fit max_w."""
    words, lines, cur = text.split(), [], ""
    for word in words:
        trial = f"{cur} {word}".strip()
        if not cur or stringWidth(trial, F, size) <= max_w:
            cur = trial
        else:
            lines.append(cur)
            cur = word
    if cur:
        lines.append(cur)
    return lines


# (shot file, title, subtitle, bullets)
PAGES = [
    ("00-home.png", "1 · Public home page",
     "The front door — no login needed to start.",
     ["File a claim and Track a claim are the two actions a visitor sees.",
      "Everything behind this page requires sign-in through Keycloak SSO."]),

    ("01-fnol-step1.png", "2 · File a claim — step 1: who is covered",
     "The claimant identifies their policy.",
     ["Policy number, holder name and email are entered here.",
      "The system checks the policy exists and is active before letting you continue."]),

    ("02-fnol-step2.png", "3 · File a claim — step 2: what happened",
     "The loss itself, in plain language.",
     ["Loss date, location and a free-text description of the incident.",
      "Submitting creates the claim instantly — no adjuster touch needed yet."]),

    ("03-fnol-confirmation.png", "4 · Instant claim number",
     "Proof the claim exists, the second it is filed.",
     ["A CLM- reference number is issued immediately.",
      "The status steps show the claim starting at Under review."]),

    ("04-claimant-status.png", "5 · Track your claim",
     "What the claimant sees when they come back later.",
     ["Step-by-step progress (submitted, under review, decided) in plain words.",
      "Claimants only ever see their own claim — anyone else's number shows a 404 page."]),

    ("05-my-claims.png", "6 · My claims history",
     "Every claim this person has ever filed, in one list.",
     ["Newest first, each row linking to its tracking page.",
      "Filing a second claim takes the same two-step path as the first."]),

    ("06-cockpit-empty.png", "7 · Policy cockpit — empty state",
     "A brand-new claimant owns no policy yet, and the screen says so honestly.",
     ["When this claimant holds a policy, this page lists each cover with its limit, claimed and remaining benefit.",
      "The empty state itself proves the visibility wall: nobody ever sees another holder's policies."]),

    ("07-adjuster-queue.png", "8 · Adjuster work queue",
     "Where an adjuster's day starts.",
     ["Only claims assigned to the signed-in adjuster appear here — never a colleague's.",
      "New claims auto-assign to the least-loaded qualified adjuster."]),

    ("08-queue-filtered.png", "9 · Queue filter — Under review",
     "Same queue, narrowed to one status.",
     ["One-click tabs split the queue by claim status.",
      "Filtering never leaks unassigned claims; it only narrows your own list."]),

    ("09-claim-detail.png", "10 · Claim work surface — facts",
     "Everything the adjuster needs to assess the claim.",
     ["Policy, claimant, loss details and the reserve (expected payout) they set.",
      "Opening a claim assigned to someone else shows 404, not an error about permissions."]),

    ("10-decision-panel.png", "11 · Decision panel + audit trail",
     "Where the adjuster approves, partially approves or rejects.",
     ["Decisions above the adjuster's authority limit are blocked and must be escalated.",
      "Every action lands in an append-only audit trail that nobody can edit or delete."]),

    ("11-overview.png", "12 · Supervisor overview",
     "The whole book of business at a glance.",
     ["Aggregates: open claims, exposures, approvals, rejections, aging.",
      "Numbers always reconcile with the queue rows beneath them."]),

    ("12-outbox.png", "13 · Notification outbox",
     "Proof that claimants were told what happened.",
     ["Every status change queues a notification; SENT rows confirm delivery.",
      "The outbox pattern means no email is ever lost if mail sending fails."]),

    ("13-escalations.png", "14 · Escalations queue",
     "Claims that need a more senior hand.",
     ["Over-authority decisions and SLA breaches land here for L2/L3 and supervisors.",
      "Escalation preserves the full history — nothing is re-entered."]),

    ("14-policies.png", "15 · Policy book (admin)",
     "The policies claims are filed against.",
     ["Create, import from CSV (with per-row error reporting) and retire policies.",
      "Retired or expired policies can never take a new claim."]),

    ("15-authority.png", "16 · Authority ladder (admin)",
     "Who may approve how much — configured, not hard-coded.",
     ["L1 / L2 / L3 / supervisor limits in INR, editable by supervisors.",
      "SLA targets per stage live here too; breaches route to escalations."]),
]


def build():
    missing = [shot for shot, *_ in PAGES if not os.path.isfile(os.path.join(SHOTS, shot))]
    if missing:
        raise SystemExit(f"missing screenshots: {missing} — run shots.spec.ts first")
    total = len(PAGES) + 1  # cover + shots

    c = Canvas(OUT, pagesize=A4)

    # ---- cover ----
    c.setFillColor(BLUE)
    c.setFont(FB, 30)
    c.drawCentredString(W / 2, H / 2 + 40, "ClaimFlow")
    c.setFillColor(MUTED)
    c.setFont(F, 13)
    c.drawCentredString(W / 2, H / 2 + 8, "Insurance claim processing — visual walkthrough")
    c.setFont(F, 10)
    c.drawCentredString(W / 2, H / 2 - 20, "16 screenshots from the real running product, in the order a user meets them")
    c.setFont(F, 9)
    c.drawCentredString(W / 2, H / 2 - 44, "Claimant files and tracks  →  adjuster assesses and decides  →  supervisor oversees")
    c.showPage()

    # ---- one page per screenshot ----
    for i, (shot, title, subtitle, bullets) in enumerate(PAGES, start=2):
        # header
        c.setFillColor(BLUE)
        c.setFont(FB, 9)
        c.drawString(MARGIN, TOP, "ClaimFlow — demo walkthrough")
        c.setFillColor(MUTED)
        c.setFont(F, 9)
        c.drawRightString(W - MARGIN, TOP, f"{i} / {total}")
        c.setStrokeColor(LINE)
        c.setLineWidth(0.6)
        c.line(MARGIN, TOP - 8, W - MARGIN, TOP - 8)

        y = TOP - 34
        c.setFillColor(INK)
        c.setFont(FB, 16)
        c.drawString(MARGIN, y, title)
        y -= 18
        c.setFillColor(MUTED)
        c.setFont(F, 10)
        c.drawString(MARGIN, y, subtitle)
        y -= 12

        # wrap bullets first so we know how much vertical room the image gets
        groups = [wrap(b, 9.5, CONTENT_W - 16) for b in bullets]
        text_h = sum(len(g) for g in groups) * 13 + 10
        img_top, img_bottom = y, BOTTOM + text_h

        img = PILImage.open(os.path.join(SHOTS, shot))
        iw, ih = img.size
        scale = min(CONTENT_W / iw, (img_top - img_bottom) / ih)
        dw, dh = iw * scale, ih * scale
        c.drawImage(os.path.join(SHOTS, shot), MARGIN + (CONTENT_W - dw) / 2, img_bottom,
                    dw, dh, preserveAspectRatio=True, anchor="c")

        # bullets
        y = img_bottom - 12
        c.setFont(F, 9.5)
        c.setFillColor(MUTED)
        for g in groups:
            for j, line in enumerate(g):
                c.drawString(MARGIN, y, ("•  " if j == 0 else "     ") + line)
                y -= 13
            y -= 2

        # footer
        c.setFillColor(MUTED)
        c.setFont(F, 8)
        c.drawCentredString(W / 2, 12 * mm, f"ClaimFlow demo  •  page {i} of {total}")
        c.showPage()

    c.save()
    print(f"wrote {OUT} ({total} pages)")


if __name__ == "__main__":
    build()
