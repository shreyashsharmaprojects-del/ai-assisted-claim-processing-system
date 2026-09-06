# Phase 04 — Build One Slice

Read `docs/plan.md`, `docs/progress.md`, `rules/yagni.md`, `rules/testing-web.md`.

One slice per run. Finish it completely rather than starting three.

## 1. Restate

Before touching anything, write out:

- the slice name
- its acceptance criteria, copied from the plan
- the files you expect to touch
- the tests you'll write

If the plan is unclear or wrong here, stop and say so. Discovering mid-slice that the
plan was wrong is normal and fine. Quietly building something different from the plan is
not, because the plan is what review checks against.

## 2. Tests first

Write failing tests before implementation:

- **E2E** for the user-visible journey, if this slice adds one to the named list
- **Integration** for each new endpoint: success, validation failure, unauthorized,
  not-found
- **Unit** for logic with real branching, edge cases, or arithmetic

Run them. Confirm they fail for the right reason. A test that passes before the feature
exists is testing nothing, and it will keep passing after the feature breaks.

Skip levels that don't apply. A slice that only changes copy needs no unit test. Adding
test layers because the template mentions them is its own kind of waste.

## 3. Implement

The smallest thing that makes the tests pass and satisfies the acceptance criteria.

Follow `rules/yagni.md`. The short version: build what the slice asks for, nothing
adjacent, nothing "while I'm in here."

Vertical, not horizontal. Database, backend, and UI for this one feature. Don't add
fields for the next slice's feature while you're in the schema.

Real errors, not just happy paths. Failed requests need a message the user can act on.
Loading and empty states are part of the feature, not polish.

## 4. Verify

- The full suite passes, not just this slice's tests. Breaking an earlier test is
  the most important signal you get all session.
- Every acceptance criterion has at least one test that would fail if it regressed.
  Check them one by one against the list you wrote in step 1.
- Lint and typecheck clean.
- No `TODO`, no stub, no commented-out block. If something is genuinely deferred it goes
  in `decisions.md` under Deferred, where it can be found, not in a comment where it
  can't.

## 5. Record

Update `docs/progress.md`: slice done, what changed, anything surprising, next slice.

Add to `docs/decisions.md` if you made a call the plan didn't cover, or deliberately
didn't build something you were tempted to build.

Update `api.http` if the project keeps one and this slice added endpoints.

## 6. Report

Tell the user: what now works, how to see it, test counts before and after, anything you
hit that they should know about.

Then stop. Don't roll into the next slice. Review comes first, and the user may want to
change direction based on what they just saw.
