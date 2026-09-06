# Phase 06 — Harden Before Ship

Run once, when the slice list is done and before real users touch this. Strong model.

Read `docs/requirements.md`, `docs/plan.md`, `docs/progress.md`.

Work through the checklist, note what's wrong, and fix it in small commits with tests
where tests make sense.

This is a pass over the *whole* app, not one slice. Things that look fine per-slice
(inconsistent error handling, a page that never got a loading state) only become visible
here.

## Every screen

- [ ] Loading state. No blank flash, no layout jump when data arrives.
- [ ] Empty state that tells a new user what to do next, not a bare "no results".
- [ ] Error state, with a way to retry.
- [ ] A form submitted twice quickly doesn't double-create.
- [ ] Long text, long names, and 200 rows don't break the layout.

## Access control

- [ ] Every endpoint enforces its rule from the plan. Test it as the wrong user, not
      just as the right one.
- [ ] Guessing another user's record ID gets a 403 or 404, not their data. Try it.
- [ ] Nothing sensitive is decided only in the frontend.
- [ ] Session or token expiry, logout, and password reset all behave.

## Security basics

- [ ] Server-side validation on every input.
- [ ] Queries parameterized; user content escaped on render.
- [ ] CSRF protection on state-changing requests; CORS not left wide open.
- [ ] Secrets in environment variables, never committed. Check the git history, not just
      the working tree.
- [ ] Dependency audit run; known-vulnerable packages updated.
- [ ] Rate limiting on login and anything that sends mail or costs money.
- [ ] Errors shown to users say what happened without exposing internals.

## Accessibility

- [ ] Every flow completable by keyboard alone. Try it.
- [ ] Visible focus indicators.
- [ ] Form inputs have real labels; errors are associated with their field.
- [ ] Images have alt text; icon-only buttons have accessible names.
- [ ] Contrast meets WCAG AA.
- [ ] Headings nest properly.

An automated checker (axe, Lighthouse) catches maybe half of this. Do the keyboard pass
by hand.

## Reach and performance

- [ ] Works at the viewport sizes the requirements named.
- [ ] Works in the browsers the requirements named.
- [ ] No obviously unbounded query. Check for N+1s on any list page.
- [ ] Pagination anywhere a list can grow without limit.
- [ ] Page weight sane; large images not shipped at full resolution.

Fix what's slow *and measured*. Don't optimize on suspicion.

## Operations

- [ ] Errors are logged somewhere you can read after deploy.
- [ ] Logs contain no passwords, tokens, or personal data.
- [ ] A health check endpoint exists.
- [ ] Config comes from the environment; `.env.example` lists every variable.
- [ ] Migrations run cleanly against a copy of production-shaped data.
- [ ] Backups exist, and a restore has been tried at least once.
- [ ] There is a way to roll back a bad deploy.

## Documentation

- [ ] README: what this is, setup, run, test, deploy.
- [ ] `decisions.md` current, including the Deferred list.
- [ ] `requirements.md` matches what actually got built. Where it doesn't, correct the
      document.

## Output

Report as: fixed, needs a decision from you, deliberately accepted with reasons.

Then check the Non-goals list one last time. If something on it got built anyway, say so
now.
