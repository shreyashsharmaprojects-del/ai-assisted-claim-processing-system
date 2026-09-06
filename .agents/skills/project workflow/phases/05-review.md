# Phase 05 — Review

**Run this in a fresh session with the strong model.** A model that just wrote the code
reviews its own reasoning, not the code, and it agrees with itself. If you only follow
one thing from this workflow besides the plan gate, follow this.

Read `docs/plan.md`, `docs/requirements.md`, `rules/yagni.md`, and the diff for the
slice. Read the code as if you're inheriting it from someone who has left the company.

You are not fixing anything in this phase. You are producing a findings list.

## The scope question

Ask it literally, and answer it in writing:

> What was built that the plan didn't ask for?

Go file by file. This catches more than every upfront instruction combined, because
speculative code looks perfectly reasonable in isolation and only looks wrong next to
the spec that didn't ask for it.

Then, from `rules/yagni.md`:

- any abstraction, interface, or base class with exactly one implementation
- any configuration option, environment variable, or feature flag nobody requested
- any parameter, hook, or extension point with no current caller
- any handling for a case the requirements ruled out in Non-goals
- any caching, batching, or optimization added without a measurement

## Requirements coverage

For each acceptance criterion in this slice: name the test that covers it. If there
isn't one, that's a finding. "The code looks right" is not coverage.

Then check the reverse: does anything in the diff serve no criterion at all?

## Test quality

Tests are where review usually gets lazy, and bad tests are worse than missing ones
because they produce confidence without protection.

- Do tests assert on **behavior a user could notice**, or on internal calls and
  implementation details? The second kind breaks on every refactor and catches nothing.
- Would each test actually fail if the feature broke? Pick the two most important and
  reason it through.
- Any fixed `sleep`, any dependence on test ordering, any shared mutable state between
  tests? Each one is a future flake.
- Are error paths tested, or only the happy path?

## Correctness and security

- **Authorization on every endpoint.** Check each one against the plan's stated rule.
  This is the most common serious bug in generated web apps.
- Input validated at the boundary, on the server. Client validation is a courtesy, not
  a control.
- Queries parameterized. Output escaped.
- No secrets, keys, or credentials in the repo or in commit history.
- Errors handled where they can happen. No empty catch blocks. No error message that
  leaks a stack trace to the user.
- Anything that could lose or corrupt user data.

## Readability

Would a new developer understand this in a month? Names that say what things are,
functions that do one thing, no cleverness that needs a comment to explain. Flag comments
that describe *what* instead of *why*.

## Output

Group findings by severity:

- **Blocking** — wrong, unsafe, or loses data. Fix before the next slice.
- **Should fix** — speculative code, missing test coverage, real design problems.
- **Optional** — style, naming, small cleanups.

Each finding: file, line, what's wrong, what to do instead. Be specific enough that
fixing it needs no further discussion.

If the slice is clean, say so plainly and list what you checked. Manufacturing findings
to look thorough trains the user to ignore you.

The user decides what gets fixed. Some speculative code is a deliberate choice, and
they're the one who knows.
