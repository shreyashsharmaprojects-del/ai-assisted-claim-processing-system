# YAGNI Rules

Append this to every implementation and review phase.

"Follow YAGNI" doesn't change behavior, because every piece of speculative code looks
justified while you're writing it. Checkable rules do change behavior.

## Rules

**Build only what the current slice's acceptance criteria require.** Not the adjacent
thing. Not the obvious next step. Not the case that will "definitely come up."

**Rule of three.** Duplicate freely. Extract an abstraction at the third occurrence, when
you can see what actually varies. Abstractions built from two examples usually guess
wrong about the third, and a wrong abstraction is harder to remove than duplication is
to live with.

**No interface, base class, or protocol with one implementation.** Add it when the second
implementation exists.

**No configuration nobody asked for.** No option, flag, env var, or setting that has one
value and no request behind it. Hardcode it. Changing a constant later is easy.

**No parameter or hook with no caller.** No `options={}` "for extensibility". No plugin
system. No event bus for events nobody subscribes to.

**No performance work without a measurement.** No cache, no batching, no denormalization,
no index beyond what correctness requires, until something is measurably slow. Caches in
particular add invalidation bugs that cost more than the latency they save.

**No infrastructure for scale you don't have.** No queue, no worker, no separate service,
no read replica until the load exists. Reread the scale number in `requirements.md` before
reaching for any of these.

**No generic when specific will do.** Write `sendPasswordResetEmail` rather than a
templated notification dispatch layer.

**Delete, don't comment out.** Version control remembers. Commented code is noise that
never gets cleaned up.

**Handle errors that can happen here.** Not every conceivable failure. Real, reachable
failure modes for this feature.

## The pressure valve

When you're convinced something extra is genuinely needed, don't build it and don't
silently drop it. Write it in `decisions.md`:

```
## Deferred
- YYYY-MM-DD — Considered: pluggable storage backends.
  Why not now: one backend, no second one planned.
  Build it when: a second storage target is actually requested.
```

This is what makes the rules livable. The idea is recorded, findable, and costs nothing
to carry. If the need is real, it'll come back with a concrete case attached, and then
you'll know what to build instead of guessing.

## Where this doesn't apply

YAGNI is about *speculative features and abstractions*. It is not a reason to skip:

- tests
- input validation and authorization
- error handling on reachable paths
- accessibility
- migrations
- clear naming

Those are part of doing the current work correctly, not preparation for imagined future
work. Cutting them isn't simplicity, it's debt.
