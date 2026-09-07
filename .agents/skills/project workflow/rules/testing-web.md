# Testing Strategy — Web Applications

Loaded by `02-plan.md` to choose a strategy, and by `04-slice.md` to follow it.

## Three layers

**Unit** — pure logic, no database, no network. Milliseconds. Validation rules, pricing,
date handling, state machines, permission checks as functions.

This is language-agnostic: it covers frontend component logic (Angular/React/Vue) as much
as backend classes. Form validation, enabled/disabled state, route guards, and derived
display fields are all unit-testable branching. A "button never enables" bug is a
component test (Vitest/Jest + Testing Library), not an E2E test — catch it in
milliseconds, not in a browser run.

Only worth writing where there's real branching. Testing a getter or a framework's ORM
is maintenance cost with no protection.

**Integration** — real endpoint, real test database, HTTP in and out. Tenths of a second.
This is the layer that catches the most real bugs per unit of effort in a web app,
because most web bugs live at boundaries: serialization, validation, auth, transactions,
queries.

For each endpoint: success, invalid input, unauthenticated, authenticated-but-forbidden,
not-found.

**E2E (browser)** — real browser, real app, clicking like a user. Seconds each. Catches
what nothing else can: the button wired to nothing, the form that submits the wrong
shape, the route that 404s in production build only.

Expensive and flake-prone, so keep the set small and deliberate. **5 to 10 journeys for a
typical app.** Cover the paths where breakage is unacceptable. Everything else belongs at
a lower layer. E2E is not the frontend's only test layer — it covers the seams (wiring,
routing, the real browser); component logic belongs in Unit.

Name the journeys in `plan.md`. An unnamed E2E suite grows into a slow, flaky mess that
gets disabled.

## Choosing a browser tool

**Playwright** is the better default for new projects. Auto-waiting eliminates most
flakiness by construction, parallelism works out of the box, and the trace viewer shows
you a timeline of a failed run instead of a stack trace. Cypress is a reasonable
alternative if the team already knows it.

**Selenium** makes sense when: you have existing Selenium infrastructure or a Grid, you
need a browser matrix Playwright doesn't cover, or your organization has standardized on
it. It's a mature tool and it works. It just needs more explicit waiting discipline,
which is exactly where hand-written and generated tests tend to get flaky.

If you use Selenium, ban implicit sleeps outright and require `WebDriverWait` with
explicit expected conditions everywhere. That single rule removes most of the difference.

## Keeping E2E tests from rotting

**Stable selectors.** `data-testid` attributes, or accessible roles and labels. Never CSS
classes, never nth-child, never generated class names. A restyle should not break tests.

**No fixed sleeps.** Wait for a condition: an element visible, a request settled, text
present. `sleep(2)` is either slow or flaky, usually both, and it becomes flaky on CI
where machines are slower.

**Every test independent.** Each creates its own data and can run alone, in any order, in
parallel. Tests that depend on earlier tests fail in confusing cascades.

**Reset state between tests.** Truncate or roll back a transaction. A shared dirty
database produces failures that don't reproduce locally.

**Test through the UI, set up through the API.** To test "user edits a project," create
the user and project via API or fixture, then drive only the edit in the browser. Doing
setup through the UI makes tests slow and makes unrelated failures cascade.

**Zero tolerance for flakes.** A test that fails 1 in 20 runs teaches everyone to rerun CI
instead of reading it, which is how real failures get ignored. Fix it or delete it.

**Boot your own servers, or verify the one you reuse.** Don't let E2E silently reuse an
already-running dev server — a stale `ng serve`/`next dev` with an old proxy config can
masquerade as the test frontend and give a false green. Boot the test stack explicitly, or
assert the reused server is the right build.

## Test data

Factories or builders with sensible defaults and per-test overrides:

```
createUser({ role: 'admin' })
createProject({ owner: user, status: 'archived' })
```

Better than fixture files, which drift and get shared until every test depends on the
same magic row.

## API request collection

Keep an `api.http` (REST Client / IntelliJ format) or a Bruno/Hoppscotch collection with
a working example of every endpoint: real headers, real body, auth included.

Cheap to maintain, and it pays for itself as living documentation, manual smoke testing,
and onboarding material. Update it in the same slice that adds the endpoint, or it dies.

Consider a smoke script that runs the collection against a deployed environment after
release.

## CI

Every push: lint, typecheck, unit, integration. Fast, blocking.

Every push to main or every PR: E2E. Slower. Blocking for merge.

Nightly, optionally: full E2E across the browser matrix.

Nothing merges red. A tolerated red build stops being a signal within a week.

## What not to test

Framework internals. Third-party library behavior. Getters and setters. Exact copy that
changes weekly. Implementation details like "this function calls that function" — those
break on every refactor while catching nothing.

If a test would still pass with the feature broken, or would fail on a pure refactor,
it's the wrong test.

## AI features, if this app has any

Model output is statistical, so pass/fail assertions on generated text will flake
forever. Test the deterministic parts normally: prompt construction, parsing, error and
timeout handling, retries, rate limits, cost guards, what happens when the provider
returns garbage.

For the model layer itself, keep a small eval set of inputs with expected properties and
track a score over time rather than gating CI on it. Mock the provider in normal tests so
the suite is fast and free.
