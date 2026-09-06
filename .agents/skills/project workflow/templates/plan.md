# Plan — [Project Name]

Status: Draft | Approved
Last updated: YYYY-MM-DD
Based on: `requirements.md` as of YYYY-MM-DD

## Stack

| Layer | Choice | Why |
|---|---|---|
| Frontend | | |
| Backend | | |
| Database | | |
| Auth | | |
| Hosting | | |
| Unit tests | | |
| Integration tests | | |
| E2E tests | | |
| CI | | |

## Open forks

Only for choices with real consequences either way.

### [Decision, e.g. server-rendered vs SPA]

- **Option A** — [tradeoff in one line]
- **Option B** — [tradeoff in one line]
- **Recommendation:** [which, and the deciding reason]

## Data model

```
User
  id, email (unique), password_hash, created_at

Project
  id, name, owner_id → User, created_at
  index on owner_id
```

Constraints and indexes needed from day one: [list — these hurt to retrofit]

## Routes

| Path | Renders | Who can see it |
|---|---|---|
| `/` | | |

## API

| Method | Path | Auth rule | Returns |
|---|---|---|---|
| GET | `/api/projects` | owner only | list of caller's projects |

Every row needs an auth rule, even if it's "public". Blank means undecided, not open.

## Test strategy

- **Unit:** [framework] — covering [what actually has branching logic]
- **Integration:** [framework] — test DB via [how it's created and reset per test]
- **E2E:** [tool] — journeys covered:
  1. 
  2. 
  3. 
- **API collection:** `api.http` maintained / not maintained
- **CI gates:** [what must be green to merge]

## Slices

Each slice is vertical: database + backend + UI, ending in something a user can do.

### Slice 0 — Walking skeleton
See `03-skeleton.md`. Done when all three test layers run green in CI.

### Slice 1 — [name]
- Satisfies: [acceptance criteria from requirements.md]
- Tests: [which layers, which cases]
- Risk / unknowns: 

### Slice 2 — [name]
...

Ordering note: [what's early because it's risky or uncertain]

## Out of scope for this plan

[Restate the Non-goals that were most tempting to build anyway.]
