# Phase 03 — Walking Skeleton

Read `docs/plan.md` and `rules/yagni.md`.

Build the thinnest possible thing that runs end to end, with the full test rig around
it, before any feature exists.

This phase exists because of one reliable failure: if the browser test harness isn't
built now, it never gets built. At slice eight there's always something more urgent, and
by then setting it up means retrofitting selectors and fixtures across eight features.
The cost is small now and grows every slice.

## Done means all of these are true

- [ ] One real page renders in a browser
- [ ] It shows data that came from the actual database through the actual backend, not
      a hardcoded string
- [ ] Database migrations run from empty to current with one command
- [ ] One unit test passes
- [ ] One integration test hits a real endpoint against a real test database and passes
- [ ] One E2E test opens a browser, loads the page, asserts the data is visible, and passes
- [ ] `README.md` documents: install, run dev server, run migrations, run each test layer
- [ ] CI runs all three test layers on push and is green
- [ ] The whole thing starts from a fresh clone with the documented commands

Verify the last one by actually doing it. Fresh clone, follow your own README, run the
commands. Setup instructions that were never executed are usually wrong.

## Keep it thin

The page can be unstyled. The data can be one seeded row. The endpoint can return one
field.

Do not build: a component library, an abstract base repository, a config system, an
error-handling framework, a logging abstraction, a Docker Compose stack with six
services, or auth. Those come when a slice needs them.

If you're deciding between two structures and can't tell which is right yet, pick the
simpler one and note the question in `decisions.md`. You'll know more after slice two
than you do now.

## Output

Update `progress.md`: skeleton done, commands to run everything, next slice.

Tell the user how to run it and how to run the tests, then stop. Let them see it work
before you build on it.
