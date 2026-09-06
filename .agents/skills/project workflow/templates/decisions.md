# Decisions

Short records of choices that would otherwise get re-argued, plus the things we decided
not to build. Newest first.

## Decisions

### YYYY-MM-DD — [Decision in a few words]

**Context:** [what forced a choice]
**Decision:** [what we picked]
**Why:** [the deciding reason]
**Rejected:** [alternatives, and what made them lose]
**Revisit if:** [the condition that would change this]

---

### YYYY-MM-DD — Example: session cookies over JWT

**Context:** Needed auth for slice 2.
**Decision:** Server-side sessions in a cookie.
**Why:** Single server, no mobile client, and logout that actually revokes immediately.
**Rejected:** JWT — revocation needs a denylist, which is state, which is the thing JWT
was supposed to avoid here.
**Revisit if:** a mobile client or a second backend service appears.

---

## Deferred

Things we were tempted to build and deliberately didn't. Recorded so the idea isn't lost
and doesn't have to be re-had.

### YYYY-MM-DD — [Thing]

**Considered:** [what it was]
**Why not now:** [what makes it speculative today]
**Build it when:** [the concrete trigger]

---

### YYYY-MM-DD — Example: pluggable storage backends

**Considered:** An abstract storage interface over local disk and S3.
**Why not now:** One backend exists and no second one is planned. An interface with one
implementation is a guess about a shape we can't see yet.
**Build it when:** a second storage target is actually requested.
