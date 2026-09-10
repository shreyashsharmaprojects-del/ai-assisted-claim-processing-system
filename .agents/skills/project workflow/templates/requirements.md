# Requirements — [Project Name]

Status: Draft | Approved
Last updated: YYYY-MM-DD

## One-line summary

[What this is, for whom, in one sentence.]

## Users

| Role | What they're trying to do | Can they self-register? |
|---|---|---|
| | | |

## Core flows

### Flow 1 — [name]

[Walk through it, step by step, as the user experiences it.]

Acceptance criteria:
- [ ] [Testable. "Invalid credentials show an error and stay on the login page", not
      "login works".]
- [ ] 
- [ ] 

<!--
  Cartographer note (when the repo has a codebase map): each box above
  becomes a requirement node with an id derived from its text. Tag a
  criterion `[REQ-001]` at the start of its line to give it a stable id
  that survives rewording — untagged criteria re-key their node id when
  the wording changes, dropping any bindings. Tag before approval for
  anything a slice will bind.
-->

### Flow 2 — [name]

...

## Data

| Entity | Key fields | Belongs to | Notes |
|---|---|---|---|
| | | | |

Must survive a restart: [what]
Sensitive / regulated: [what]

## Accounts and access

- Login required: yes / no
- Method: [email+password / OAuth / magic link / none]
- Can users see each other's data: [rule]
- Admin role: [yes, what it can do / no]

## Non-goals

**The most important section here.** Everything on this list is code that doesn't get
written. Be specific.

- Not building: [thing] — [why not, or "later"]
- Not building: 
- Not building: 

Explicitly considered and excluded: multi-tenancy / roles beyond admin / notifications /
real-time updates / file uploads / audit log / internationalization / offline support /
mobile app / SSO
*(delete the ones that are actually in scope)*

## Constraints

- Stack we must use: 
- Must integrate with: 
- Hosting: 
- Deadline: 
- Compliance: 

## Reach

- Browsers: 
- Mobile / responsive: 
- Accessibility target: [e.g. WCAG 2.1 AA]

## Scale

- Expected users at launch: [a number]
- Expected users in a year: [a number]
- Largest realistic table size: 

## Definition of done for v1

- [ ] 
- [ ] 

## Open questions

| Question | Blocking? | Assumed answer for now |
|---|---|---|
| | | |
