# Claims Processing — Product Concept

*The underlying purpose is to have a realistic project to run the workflow files against.
But a half-committed concept is useless, so this is written straight, as a product
concept for a small claims handling tool. Personal auto, single carrier.*

---

## The problem

Claims is the moment of truth in insurance. It's the only time most policyholders
actually experience the product they've been paying for, and it's where retention is won
or lost. It's also, in a lot of small books, the least tooled part of the operation.

The pattern this addresses looks like this. A loss gets reported by phone or email. It
lands in a shared inbox. Someone copies it into a spreadsheet. An adjuster picks it up,
works it across email threads and a folder of photos, decides, and tells someone in
accounts to cut a payment. The policyholder, meanwhile, has no idea what's happening and
calls twice a week to ask.

Three costs come out of that:

**Leakage.** Payments go out without a clear record of who authorized them or on what
basis. Authority limits exist on paper but aren't enforced anywhere in the process.

**Regulatory exposure.** Fair claims practice rules assume you can show timely,
documented, explainable decisions. A spreadsheet and an email thread is a bad answer to
a market conduct exam.

**Service cost and churn.** Status-check calls are pure cost, and the silence between
FNOL and decision is where policyholders start shopping.

## What it is

A single system that carries a claim from first notice to closure, with three things the
current process doesn't have: an enforced authority gate on payments, a hard boundary
between what the carrier sees and what the claimant sees, and a status the claimant can
check without calling anyone.

Deliberately small. It does not replace a policy admin system, and it does not try to
adjudicate anything automatically.

## Who it's for

**The claimant.** Has had a bad day and wants to know two things: is this covered, and
when do I get paid. Their pain is silence. They are not a sophisticated user and will
touch this maybe twice in their life.

**The adjuster.** Handles a caseload, not a claim. Their pain is knowing what to work
next and not losing context when they come back to a file three days later. They need
somewhere to put the messy internal reasoning that they absolutely do not want the
claimant reading.

**The supervisor.** Owns the outcomes for a team. Their pain is visibility: which claims
are stuck, which are about to breach a service commitment, and every payment above the
line they're personally accountable for.

Note the asymmetry. The claimant is an occasional visitor. The adjuster lives in this
tool all day. Where those two conflict, the adjuster wins on the internal side and the
claimant wins on clarity.

## The journey

**Day 0 — the loss is reported.** The policyholder submits a first notice against their
policy: what happened, when, where, photos. They immediately get a claim number and a
plain statement of what happens next. This alone removes the first wave of status calls.

**Day 0 to 1 — assignment.** The claim lands in an unassigned queue. A supervisor or a
rule assigns it to an adjuster. From the claimant's side, the status simply moves to
under review.

**Day 1 to 3 — the adjuster works it.** They verify coverage against the seeded policy,
read the description and photos, set a reserve, and write internal notes as they go. None
of this is visible to the claimant. The claimant sees only that the claim is being
reviewed and who to contact.

**Day 3 to 5 — the decision.** The adjuster reaches an indemnity figure. If it's within
their authority limit, they approve it and the claim moves toward payment. If it's above
their limit, the system escalates it rather than letting them approve it anyway. An
adjuster cannot approve their own escalation.

**Escalation.** The supervisor sees the file, the reserve, the notes, and the proposed
figure, and decides. The claim then rejoins the normal path.

**Closure.** A payment is recorded and the claim closes. The claimant sees the decision,
the approved amount, and, on a denial, the reason. They never see the reserve or the
internal notes.

Underneath all of it, every status change and every decision is logged with actor and
timestamp. That log is the product feature that nobody asks for and the compliance team
will care about most.

## The four moments that carry the value

Most of this system is ordinary CRUD. Four moments are where it earns its existence.

**The claim number at FNOL.** The cheapest trust-building moment in the whole product. A
number and a clear next step, immediately.

**The queue.** The adjuster's home screen. If they have to think about what to work next,
the tool has failed at its main job.

**The authority gate.** The point where a business rule stops being a policy document and
becomes something the system actually enforces. This is the difference between having an
authority structure and having one that holds.

**The visibility wall.** The reason carriers are nervous about claimant self-service at
all. One reserve figure leaking into a claimant-facing screen is a bad day. Get this
right and the rest of the self-service story becomes possible.

## What success looks like

Things a PM would actually track, in rough priority:

- **Cycle time from FNOL to decision.** The headline number. Everything else is in
  service of this.
- **Status-check contacts per claim.** Direct read on whether the transparency is
  working. Should fall sharply.
- **Escalation rate.** Diagnostic rather than a target. Very high means authority limits
  are set too low and supervisors have become a bottleneck. Near zero means they're too
  high and the gate isn't doing anything.
- **Reopened claims after decision.** Proxy for decision quality and for whether
  adjusters had what they needed the first time.
- **Decisions with a recorded actor and rationale.** Should be 100%. Anything less is an
  audit finding waiting to happen.

## What it isn't

Naming these matters as much as naming the features, because each one is a direction
someone will push in during the first month.

- Not a policy admin system. Policies are seeded and read-only here.
- Not an underwriting or rating tool.
- No automated adjudication, no fraud scoring, no document reading. Every decision is
  made by a person.
- No integrations with external systems in this version.
- Real money movement is out. A payment is a recorded fact, not a transaction.


