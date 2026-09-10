# Phase 02 — Plan

Read `docs/requirements.md`, `rules/testing-web.md`, and `rules/security.md`. Produce `docs/plan.md` from
`templates/plan.md`.

Still no implementation code. This phase produces a document.

## Stack

Choose one, with a one-line reason each, for: frontend, backend, database, auth,
hosting, test tooling.

Prefer boring, widely-used tools with good docs. The agent writing this code has seen a
lot of Django and Rails and Next.js and very little of whatever shipped last month.
Novelty costs you real debugging time.

Where there's a genuine fork with different consequences (server-rendered vs SPA,
Postgres vs SQLite, session cookies vs JWT), present **two options with the tradeoff in
one line each** and a recommendation. Don't do this for every choice or the plan becomes
a catalogue. Two or three real forks is typical.

Respect anything the requirements pinned down. If a constraint makes the recommendation
worse, say so once and follow the constraint.

## Data model

Entities, key fields, relationships, ownership. A short table or a code block is fine.
Note anything that needs an index or a uniqueness constraint from day one, because those
are painful to retrofit once there's data.

## Surface

Routes the user visits, and the API endpoints behind them. For each endpoint: method,
path, who's allowed to call it, and what it returns. Keep it terse.

Every endpoint needs an authorization answer, even if the answer is "public". Missing
authorization is the single most common serious bug in generated web apps, and it
happens because nobody wrote down what the rule was supposed to be.

## Test strategy

Read `rules/testing-web.md` and decide, for this project:

- unit test framework and what actually deserves unit tests here
- integration tests: how the test database gets created and reset
- E2E: which tool, and **which 5 to 10 journeys** are worth covering
- whether an `api.http` request collection is worth maintaining
- what runs in CI, and what has to be green to merge

Name the specific journeys now. "We'll add E2E tests" is a wish. "E2E covers signup,
login, create project, invite teammate, delete project" is a plan.

## Slices

Break the work into vertical slices. A slice touches the database, the backend, and the
UI, and ends with something a user can do that they couldn't before. Slices that are
"build all the models" or "do the frontend" are not slices; they postpone all the
integration risk to the end, which is exactly where you don't want it.

For each slice: a name, the acceptance criteria it satisfies (copied from
`requirements.md`), and the tests it needs.

Order them so the riskiest or most uncertain thing is early. If something is going to
sink the design, find out in week one.

Slice 0 is always the walking skeleton (`03-skeleton.md`).

Keep slices small enough to finish in one session. If a slice has more than about five
acceptance criteria, split it.

## Gate

Show the user the slice list and the test strategy. Ask directly: *is anything here more
than we need for the first version?*

Do not write implementation code until they approve.

After approval, if the repo has a cartographer map (`.codebase-map/graph.json`
exists), run `intent import` so the slices enter the graph as intent nodes
before any slice code exists.
