---
name: web-app-workflow
description: Phase-based workflow for building web applications end to end — requirements gathering, architecture planning, walking skeleton, vertical feature slices, code review, and pre-ship hardening, with YAGNI enforcement and a browser/integration/unit test strategy. Use this skill whenever the user wants to start a new web app, backend-plus-UI project, or dashboard; whenever they ask to plan, scope, or architect a software project; whenever they ask for a feature to be added to an existing project that follows this workflow; and whenever docs/plan.md or docs/progress.md exists in the workspace. Also use it when the user mentions requirements, slices, acceptance criteria, walking skeleton, or asks for a code review of recent work. Prefer this skill over ad-hoc implementation for anything larger than a one-line fix.
---

# Web Application Workflow

A phase-based process for building web applications. Each phase reads files written by
earlier phases and writes its own. State lives on disk, not in the conversation, so work
survives context loss and session restarts.

## Before anything else

Check whether `docs/progress.md` exists in the workspace.

- **It exists** — read it, plus `docs/plan.md`. Report where the project stands and which
  slice is next. Do not start work until the user says what they want.
- **It doesn't** — this is a new project or an unmanaged one. Read `phases/00-triage.md`
  and classify the request.

## Phases

Read the phase file in full before acting on it. Each one has requirements that aren't
obvious from its name.

| Phase | File | Produces |
|---|---|---|
| Triage | `phases/00-triage.md` | how much process this request needs |
| Requirements | `phases/01-requirements.md` | `docs/requirements.md` |
| Plan | `phases/02-plan.md` | `docs/plan.md` |
| Skeleton | `phases/03-skeleton.md` | running app, three test layers, green CI |
| Slice | `phases/04-slice.md` | one vertical feature, tested |
| Review | `phases/05-review.md` | findings list |
| Harden | `phases/06-harden.md` | pre-ship fixes |

`04` and `05` alternate until the slice list in `docs/plan.md` is finished.

## Rules to load

- `rules/yagni.md` — read before phases 03, 04, and 05. Non-optional for those phases.
- `rules/testing-web.md` — read during phase 02 to choose a strategy, and during 04 to
  follow it.

## Templates

`templates/requirements.md`, `plan.md`, `progress.md`, `decisions.md`. Copy the relevant
one into `docs/` and fill it in rather than inventing a structure.

## The two gates

**Do not write implementation code until the user has approved `docs/plan.md`.** Not a
schema, not a scaffold, not a package.json. If the user asks for code before the plan is
approved, say the plan comes first and offer to write it now. This gate is the reason the
workflow exists.

**Do not review your own work in the same session that produced it.** After finishing a
slice, tell the user that review belongs in a fresh session and stop. Reviewing code you
just wrote produces agreement, not findings.

## Stopping

Stop and hand back to the user at the end of every phase. Do not chain phases together.
The user approving each step is what makes this a workflow rather than a long
uninterrupted generation, and each gate is a chance to change direction cheaply.
