"""OpenClaimFlow demo PDF — the full product walkthrough: one screenshot per page.

Generates demo/OpenClaimFlow-Demo.pdf from e2e/shots/*.png (captured by
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

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "OpenClaimFlow-Demo.pdf")
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
    # ---- Claimant: filing, guards, confirmation ----
    ("00-home.png", "1 · Public home page",
     "The front door — no login needed to start.",
     ["File a claim and Track a claim are the two actions a visitor sees.",
      "Everything behind this page requires sign-in through Keycloak SSO."]),

    ("01-fnol-step1.png", "2 · File a claim — step 1: who is covered",
     "The claimant identifies their policy.",
     ["Policy number, holder name and email are entered here.",
      "The system checks the policy exists and is active before letting you continue."]),

    ("02-fnol-retired-error.png", "3 · Guard rail — retired policies cannot take claims",
     "A filing against a retired policy stops here, with no existence signal.",
     ["The error names no policy internals — a stranger probing numbers learns nothing.",
      "Retired policies stay readable for history, but the FNOL door stays shut."]),

    ("03-fnol-step2.png", "4 · File a claim — step 2: what happened + covers",
     "The loss itself, plus one amount per opted cover.",
     ["Health-family policies offer a cover picker: tick Hospitalization, Daycare… and enter each claimed amount.",
      "Sub-limits never block filing — the OPD figure above its 30k sub-limit files fine and is flagged for the adjuster instead."]),

    ("04-fnol-confirmation.png", "5 · Instant claim number",
     "Proof the claim exists, the second it is filed.",
     ["A CLM- reference number is issued immediately, with the filed covers echoed back.",
      "Filing also queues the confirmation notification and routes the claim to the least-loaded qualified adjuster."]),

    ("05-fnol-duplicate.png", "6 · Guard rail — duplicate filings return the original number",
     "Same policy + loss date + cover set within 24h is one claim, not two.",
     ["The screen names the existing CLM- number instead of creating a twin (HTTP 409).",
      "Amounts are ignored on the refile — a correction pointer, not a new claim."]),

    # ---- Claimant: tracker states ----
    ("06-claimant-status.png", "7 · Track your claim — open",
     "What the claimant sees when they come back later.",
     ["Step-by-step progress in plain words, with the filed covers and claimed total.",
      "Assessed amounts, reserves, assignees and notes never cross the visibility wall."]),

    ("07-my-claims.png", "8 · My claims history",
     "Every claim this person has ever filed, in one list.",
     ["Newest first, each row linking to its tracking page; search and status filters narrow it.",
      "A claimant who lost the email reopens the claim from here instead of guessing a number."]),

    ("08-cockpit-empty.png", "9 · Policy cockpit — empty state",
     "A brand-new claimant owns no policy yet, and the screen says so honestly.",
     ["The empty state itself proves the visibility wall: nobody ever sees another holder's policies.",
      "Once this claimant holds a policy, this page lists each cover with its limit, claimed and remaining (see pages 42–43)."]),

    ("09-tracker-open.png", "10 · Tracker state — open, under review",
     "A live filing, minutes old: the claimant watches it move.",
     ["The tracker shows FNOL received and under review — the claimant never sees queue internals.",
      "This exact claim is rejected live on page 12 and split-closed on page 13: one filing, three states."]),

    ("10-tracker-need-info.png", "11 · Tracker state — sent back for more information",
     "The adjuster asked the claimant a question live, and the claim waits on them.",
     ["The tracker shows exactly what was requested — the itemised final bill and discharge summary.",
      "NEED_INFO is a parking state, not a queue: nobody's SLA burns while the claimant holds the pen."]),

    ("11-tracker-denied.png", "12 · Tracker state — denied, with reasons",
     "A live review-reject: a claim the claimant did not win — told plainly, with the rationale.",
     ["The decision remarks appear verbatim: spectacles excluded under the OPD wording.",
      "Rejection at review closes immediately — no verification, no assessment, no theatre."]),

    ("12-tracker-partial.png", "13 · Tracker state — partially approved",
     "A live split close: one cover pays, another is rejected with reasons.",
     ["Per-cover outcomes — hospital approved, daycare rejected — plus the net payable total.",
      "The history row counts this as approved: partial payment is payment."]),

    ("13-myclaims-approved.png", "14 · History filter — Approved",
     "One click narrows the history to claims that paid out.",
     ["The partially-approved claim appears here with its amount — partial counts as approved.",
      "Filters are client-side saved views; the server still returns the full history."]),

    # ---- Adjuster: queue ----
    ("14-adjuster-queue.png", "15 · Adjuster work queue",
     "Where an adjuster's day starts.",
     ["Only claims assigned to the signed-in adjuster appear — never a colleague's.",
      "Age and SLA columns read the filing clock: On track, Due soon, Breaching."]),

    ("15-queue-review-tab.png", "16 · Queue tab — Under review",
     "Same queue, narrowed to one status.",
     ["One-click tabs split the queue by status; the counts update with the rows.",
      "Filtering never leaks unassigned claims; it only narrows your own list."]),

    ("16-queue-breaching-tab.png", "17 · Queue tab — Breaching SLA",
     "The claims whose clock is running hottest, isolated in one view.",
     ["3–4 days files as Due soon, 5+ as Breaching; the banner stat tracks the same rule.",
      "Breach pressure is also what feeds the supervisor's aging overview (page 35)."]),

    ("17-queue-search.png", "18 · Queue search + sort",
     "Find one claim in a full book: number, policy, location, description.",
     ["Search narrows as you type across claim, policy and loss text.",
      "Oldest-first / newest-first sort stacks the most urgent filing on top."]),

    ("18-claim-detail.png", "19 · Claim work surface — facts",
     "Everything the adjuster needs to assess the claim.",
     ["Policy, claimant, loss details, the Review → Verification → Decision stepper, and the triage panel.",
      "Opening a claim assigned to someone else shows 404, not a permissions lecture."]),

    ("19-reserve-notes.png", "20 · Reserve, notes, evidence",
     "The adjuster's working tools — all internal, all audited.",
     ["The reserve is the expected-cost estimate; internal notes carry the reasoning; photos and the coverage JSON sit beside them.",
      "None of this crosses the claimant wall — the tracker never mentions a reserve."]),

    # ---- Adjuster: staged flow, live ----
    ("21-review-triage.png", "21 · Staged flow, step 1 — Review triage (live)",
     "A live multi-cover claim at Review: validity first, money later.",
     ["Each filed cover shows claimed vs its sub-limit, with an Above-limit flag where the claimant asked for more than the cover allows.",
      "The adjuster advanced this claim to verification with a recorded rationale — pages 22–27 follow the same claim."]),

    ("22-verification.png", "22 · Staged flow, step 2 — Verification, recorded live",
     "The digital check opened and completed during the demo run.",
     ["Outcome, notes, evidence refs, actor, timestamps — recorded on the claim, not in email.",
      "Assessment stays locked until the latest verification is COMPLETE — the stepper shows Verification as current."]),

    ("23-sendback-form.png", "23 · Send-back — asking the claimant (live)",
     "A second live claim, parked from Review: the adjuster states exactly what is missing.",
     ["Requested items are free text — here, the itemised final bill and discharge summary.",
      "Send-back works from Review and from Verification; the prior stage is remembered for the return."]),

    ("24-need-info-banner.png", "24 · Parked with the claimant (live)",
     "The claim now waits on the claimant, not on an adjuster.",
     ["The banner reads the request back; every action stays locked until the claimant responds.",
      "On response the claim resumes exactly where it parked, and the claimant's tracker reads page 11."]),

    ("25-assessment.png", "25 · Assessment — per-cover figures (live)",
     "After verification passed, the adjuster priced each cover of the same live claim.",
     ["Assessed must stay within claimed, within the sub-limit, and within the remaining sum insured — the server enforces all three.",
      "The totals line does the visible math: assessed total against the authority limit, before any decision exists."]),

    ("26-cover-decision.png", "26 · Cover decision grid — split within authority (live)",
     "Approve, reject, or split — per cover, with reasons on every line.",
     ["Hospital approved at 70k, daycare rejected with remarks — inside the L1 100k limit, so this closes PARTIALLY_APPROVED.",
      "Above the limit the same screen saves proposals instead of closing — page 31 shows that stop sign."]),

    ("27-closed-partial.png", "27 · Closed — split, payment queued",
     "The live claim decided and the money moving on the approved cover.",
     ["Status CLOSED, decision PARTIALLY_APPROVED, one payment for the net total — the single-payment invariant on a split.",
      "The decision notification queues in the outbox (page 36); the claimant's tracker flips to page 13."]),

    ("28-audit-trail.png", "28 · Audit trail + reassign (supervisor view)",
     "Who did what, when, and why — append-only, nobody can edit or delete.",
     ["Advance, verification, assessment, split decision on the live claim: each entry carries actor, timestamp and rationale.",
      "Supervisors also see the Reassign panel here: move the claim to the least-loaded adjuster of another level, mid-flight."]),

    # ---- Adjuster: seeded states ----
    ("29-verification-history.png", "29 · Verification history (seeded)",
     "A claim mid-flight: COMPLETE digital record on file, assessment still to come.",
     ["The history shows type, outcome, notes and evidence refs — the working record, not a summary.",
      "This is the exact state the live run passed through on page 22; here it is inspectable at leisure."]),

    ("30-authority-gate.png", "30 · The authority gate (seeded)",
     "The visible stop sign: proposals above your limit stay on the claim, open.",
     ["The gate banner reads the maths aloud — 900k proposed against a 400k personal limit — nothing auto-moves.",
      "Refer upwards is explicit: a named senior, or auto-pick a qualified one; rejection stays ungated."]),

    ("31-partial-approval.png", "31 · Mixed outcome — partially approved (seeded)",
     "One cover pays, another is rejected with reasons; the claim closes split.",
     ["Per-cover outcomes with deductibles and net payables, closed as PARTIALLY_APPROVED with a single payment.",
      "The claimant sees exactly this shape on their tracker (page 13) — minus every internal figure."]),

    ("32-legacy-decision.png", "32 · Legacy single-figure decision",
     "The fast path for simple no-cover claims: one amount, one rationale.",
     ["Within-limit approvals close the claim and queue payment + notification; above-limit is blocked and escalates.",
      "Untouched by the staged rebuild — byte-identical behaviour, including auto-escalation."]),

    ("33-not-found.png", "33 · Guard rail — 404, not 403",
     "A claim number that is not yours reads as not-found.",
     ["No existence signal, no permission lecture — the wall holds adjuster-to-adjuster and claimant-to-claimant alike.",
      "Supervisors alone see across the book, and only from their own surfaces."]),

    # ---- Supervisor ----
    ("34-overview.png", "34 · Supervisor overview",
     "The whole book of business at a glance.",
     ["Aggregates: open claims, exposures, approvals, rejections, aging — reconciling with the queues beneath.",
      "The seeded six-day-old escalation is what makes the aging pressure read honestly."]),

    ("35-outbox.png", "35 · Notification outbox",
     "Proof that claimants were told what happened.",
     ["Every status change queues a notification; SENT rows confirm delivery, FAILED rows retry.",
      "The outbox pattern means no email is ever lost if mail sending fails."]),

    ("36-outbox-failed.png", "36 · Outbox — failed delivery, retried",
     "A notification that bounced, isolated in one view with its Retry action.",
     ["The seeded failure carries its error text and attempt count — diagnosable at a glance.",
      "Retry re-queues the same payload; success flips the row to SENT without duplicating the email."]),

    ("37-escalations.png", "37 · Escalations queue",
     "Claims that need a more senior hand.",
     ["Over-authority referrals and SLA breaches land here for L2/L3 and supervisors.",
      "Escalation preserves the full history — nothing is re-entered, the decider reads the whole trail."]),

    ("38-policies.png", "38 · Policy book (admin)",
     "The policies claims are filed against.",
     ["Create, import from CSV with per-row error reporting, and retire policies.",
      "Retired or expired policies can never take a new claim (page 3 showed the door shut)."]),

    ("39-policy-create.png", "39 · Policy create form",
     "New business enters the book here — validated before it saves.",
     ["Product, holder, sum insured and per-cover sub-limits are captured up front; covers define every later filing.",
      "CSV import handles bulk loads; the preview names exactly which rows fail and why."]),

    ("40-authority.png", "40 · Authority ladder (admin)",
     "Who may approve how much — configured, not hard-coded.",
     ["L1 / L2 / L3 / supervisor limits in INR, editable by supervisors per product.",
      "Route level and approval total decide which rung a claim lands on; SLA targets live alongside."]),

    # ---- Claimant cockpit (linked holder) ----
    ("41-cockpit.png", "41 · Policy cockpit — linked holder",
     "Ada owns POL-10001, so her cockpit lists her covers with live benefit math.",
     ["Each cover shows its sub-limit, what is claimed so far, and what remains — derived, never stored.",
      "One exhausted cover never hides the others; remaining benefit is per-cover, always visible."]),

    ("42-cockpit-policy.png", "42 · Policy detail — the fine print, readable",
     "The policy behind the covers: rating, clauses, validity.",
     ["Sum insured, room-rent caps, waiting periods and covered/excluded wording in plain view.",
      "A File-claim shortcut starts an FNOL pre-linked to this policy."]),

    # ---- Adjuster workspace additions (V4 session) ----
    ("43-ai-chat.png", "43 · AI assistant — answers on the workspace (live)",
     "A drawer on the claim: the adjuster asks, the assistant answers from the claim's own covers and clauses.",
     ["Asked whether the waiting period applies — the answer cites the maternity wording with a Live/Fallback serving path.",
      "Chat is conversational and always available; the per-cover advisory artifact stays gated until Decision."]),

    ("44-clauses.png", "44 · Policy clauses — the wording behind the covers",
     "Every clause in scope for this claim's product and covers, readable without leaving the workspace.",
     ["Product-level rows plus the MATERNITY sub-limit row (Rs 75,000 — twin deliveries count as one event), expandable to full wording.",
      "Scoping is server-side: covers the claim does not carry never appear — no HOSPITALIZATION rows on a maternity claim."]),

    ("45-policy-modal.png", "45 · Policy in place — whole page in a modal",
     "The adjuster opens the full policy behind a claim without losing the workspace.",
     ["Cover summary, covers with remaining limits, rating parameters and covered/excluded wording — the same page claimants see.",
      "File-a-claim is hidden for staff: the workspace reads the policy, it never files from it."]),
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
    c.drawCentredString(W / 2, H / 2 + 40, "OpenClaimFlow")
    c.setFillColor(MUTED)
    c.setFont(F, 13)
    c.drawCentredString(W / 2, H / 2 + 8, "Insurance claim processing — visual walkthrough")
    c.setFont(F, 10)
    c.drawCentredString(W / 2, H / 2 - 20, "46 screenshots from the real running product, in the order a user meets them")
    c.setFont(F, 9)
    c.drawCentredString(W / 2, H / 2 - 44, "Claimant files and tracks  →  adjuster assesses and decides  →  supervisor oversees")
    c.showPage()

    # ---- one page per screenshot ----
    for i, (shot, title, subtitle, bullets) in enumerate(PAGES, start=2):
        # header
        c.setFillColor(BLUE)
        c.setFont(FB, 9)
        c.drawString(MARGIN, TOP, "OpenClaimFlow — demo walkthrough")
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
        # Air between heading block and screenshot so the title reads first.
        y -= 28

        # wrap bullets first so we know how much vertical room the image gets
        groups = [wrap(b, 9.5, CONTENT_W - 16) for b in bullets]
        text_h = sum(len(g) for g in groups) * 13 + 10
        img_top, img_bottom = y, BOTTOM + text_h + 20

        img = PILImage.open(os.path.join(SHOTS, shot))
        iw, ih = img.size
        scale = min(CONTENT_W / iw, (img_top - img_bottom) / ih)
        dw, dh = iw * scale, ih * scale
        c.drawImage(os.path.join(SHOTS, shot), MARGIN + (CONTENT_W - dw) / 2, img_bottom,
                    dw, dh, preserveAspectRatio=True, anchor="c")

        # bullets sit below the screenshot with breathing room, not fused to it.
        y = img_bottom - 20
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
        c.drawCentredString(W / 2, 12 * mm, f"OpenClaimFlow demo  •  page {i} of {total}")
        c.showPage()

    c.save()
    print(f"wrote {OUT} ({total} pages)")


if __name__ == "__main__":
    build()
