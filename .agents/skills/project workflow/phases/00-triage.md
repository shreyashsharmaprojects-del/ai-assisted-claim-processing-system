# Phase 00 — Triage

Decide how much process this request needs. A five-phase workflow for a typo is how
workflows get abandoned.

If `docs/` exists, read `progress.md` first so you know where the project stands.

## Classify

**Trivial** — typo, copy change, version bump, obvious one-line fix.
→ Just do it. Run the test suite. Done. No documents.

**Small** — bounded change to something that already exists. New field on a form, a bug
with a known cause, an added filter on an existing endpoint.
→ Skip to `04-slice.md`. Write the test, make the change, run the full suite. If it
touches an architectural decision, add a line to `decisions.md`.

**Feature** — new user-visible capability in an existing project.
→ Append a slice to `plan.md` with acceptance criteria, get it approved, then `04-slice.md`.
No need to redo requirements.

**Project** — new build, or a change large enough to invalidate the current plan
(new integration, auth model change, new persistence layer).
→ Start at `01-requirements.md`.

## When you're unsure

Say which two categories it sits between and why, then ask. Guessing high wastes the
user's time; guessing low means work gets built with no acceptance criteria and no
record of why.

Two signals that something is bigger than it looks: it needs a new dependency, or you
can't describe how to test it in one sentence. Either one bumps it up a category.

## Output

State the category and the next file to load. One or two sentences. Then stop and wait,
unless the category is Trivial.
