# Phase 01 — Requirements

Interview the user until you can describe what to build in enough detail that a
different developer could build it without asking them anything. Write the result to
`docs/requirements.md` using `templates/requirements.md`.

Write no code in this phase. Not a schema, not a folder structure, not a package.json.

## How to interview

Ask **3 to 5 questions at a time**, then wait. A wall of twenty questions gets skimmed
and half-answered, and you'll be working off the half.

Order matters: ask what changes everything else first. "Does this need accounts?"
reshapes the whole project. "What should the button say?" does not, and probably isn't
your question to ask.

When you're guessing, say so and propose a default: *"I'm assuming a single shared
workspace rather than per-user data. Correct?"* A stated assumption gets corrected. A
silent one becomes a rewrite.

Stop when new answers stop changing your understanding. Usually two or three rounds.

## Cover these

**Users and jobs** — who opens this, and what are they trying to finish? Any second
role with different permissions?

**Core flows** — walk through the two or three paths that matter, screen by screen.
These become your E2E tests later, so get them concrete.

**Data** — what entities exist, what belongs to whom, what has to survive a restart.
Anything that can never be lost, and anything sensitive enough to need care.

**Accounts and access** — do users log in? How? Can one user see another's data? Is
there an admin?

**Constraints you don't get to choose** — existing stack, language, hosting, a database
that already exists, an API you must integrate with, a deadline, a compliance rule.

**Reach** — mobile browsers? Screen reader support? Old browsers? Offline?

**Scale, honestly** — ten users or ten thousand? Say the number. It decides more
architecture than anything else in this list, and over-guessing it is the most common
way projects get slow to build and expensive to run.

**Done** — what has to be true for the user to call the first version finished?

## Non-goals are the most valuable part of this document

Ask directly: what should this deliberately not do, at least for now?

Push for specifics. Vague scope is what agents fill with speculative machinery. If the
user hasn't ruled out multi-tenancy, notifications, roles, an audit log, real-time
updates, file uploads, or i18n, ask about each one that plausibly applies. Every "no"
here is code you don't write and tests you don't maintain.

Anything the user wants "eventually" goes in Non-goals with a note, not in the build.

## Acceptance criteria

Each core flow needs criteria that can be checked by a test. Not "login should work"
but "a user with valid credentials lands on the dashboard; invalid credentials show an
error and stay on the login page; five failed attempts locks the account for 15 minutes."

If you can't imagine the test, the criterion is too vague. Rewrite it.

## Gate

Write `docs/requirements.md`, then show the user the **Non-goals** and **Open questions**
sections specifically and ask them to confirm. Those are where mistakes hide.

Do not move to planning until they say it's approved.
