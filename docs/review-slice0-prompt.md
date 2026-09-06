# Slice 0 (Walking Skeleton) — Code Review Prompt

You are reviewing code you did NOT write, as if inheriting it from someone who has left
the company. Read the actual files listed below — do not review this summary. You are not
fixing anything; you are producing a findings list. Be specific enough that fixing each
finding needs no further discussion. If the slice is clean, say so plainly and list what
you checked — do not manufacture findings to look thorough.

## Context

- Product: Claims Processing System (claims adjudication: FNOL, adjuster queues,
  authority-gated decisions, supervisor escalations). Requirements and plan are approved;
  the two load-bearing domain rules are the **authority gate** and the **visibility wall**.
- This review covers **Slice 0 — the walking skeleton only** (no features): a public
  Angular page showing one seeded policy row read from PostgreSQL through Spring Boot,
  with the full three-layer test rig (unit / integration / E2E), migrations, README, and
  CI authored. Everything else in the plan is out of scope by design.
- Repo root: `/home/yash/Documents/AI Projects/Claim Processing System`
  (there is **no git repo yet** — no `.git`, no diff; the entire tree below is "the diff").

## Read first

1. `docs/plan.md` — especially the Stack, Resolved forks, Data model, API, Test strategy,
   and the Slice 0 definition ("See `03-skeleton.md`. Done when Spring Boot + Angular +
   Postgres + Keycloak are wired and all three test layers run green in CI").
2. `.agents/skills/project workflow/phases/03-skeleton.md` — the 9-item "Done" checklist
   the slice was built against, plus its "Keep it thin" list (no auth, no component
   library, no config system, no extra compose services, etc.).
3. `.agents/skills/project workflow/rules/yagni.md` — review against these rules verbatim.
4. `docs/decisions.md` — dated entries for this session's calls, including three
   corrections you must NOT re-litigate as findings:
   - C1: `audit_log` **table** in slice 0; the append-only **writer service** lands in
     slice 1 with the first write ("claim created"), not slice 4.
   - C2: **Keycloak deliberately absent** in slice 0 (auth + Keycloak arrive in slice 1);
     docker-compose is Postgres-only.
   - C3: integration tests use **Testcontainers when Docker is available**, else fall back
     to a **dedicated** `TEST_DB_URL` database (default `localhost:5432/claims_test`) —
     never the dev DB — because tests truncate. The canonical dev path is Docker.
5. `docs/requirements.md` (skim: non-goals matter for YAGNI checks).

## What slice 0 built (files to review)

- Backend (Spring Boot 4.1.1, Java 21 target): `backend/pom.xml`,
  `backend/src/main/java/com/claims/ClaimsBackendApplication.java`,
  `backend/src/main/java/com/claims/policy/` — `Policy` (entity), `PolicySummary` (record
  DTO), `PolicyRepository`, `PolicyViewMapper` (sort + DTO shaping), `PolicyController`
  (`GET /api/policies`, public);
  `backend/src/main/resources/application.properties` (dev datasource → `claims`,
  `server.port=8081`, ddl-auto none, open-in-view false);
  `backend/src/main/resources/db/migration/V1__create_policy_and_audit_log.sql`,
  `V2__seed_policy.sql`.
- Backend tests: `backend/src/test/java/com/claims/TestcontainersConfiguration.java`
  (conditional Testcontainers-vs-env-URL datasource — check the C3 design carefully),
  `ClaimsBackendApplicationTests` (context smoke), `TestClaimsBackendApplication`
  (IDE convenience main), `backend/src/test/java/com/claims/policy/PolicyViewMapperTest`
  (4 unit tests), `PolicyApiIntegrationTest` (1 real-HTTP integration test).
- Frontend (Angular 22): `frontend/src/app/app.ts` + `app.html` + `app.css`
  (policy-list page with `data-testid` hooks), `app.config.ts` (provideHttpClient),
  `frontend/proxy.conf.json` (`/api` → `:8081`), `frontend/angular.json` (serve
  proxyConfig), `frontend/package.json`, `frontend/tsconfig*.json`, `frontend/src/index.html`.
- E2E (Playwright): `e2e/package.json`, `e2e/playwright.config.ts` (webServer boots
  backend + frontend), `e2e/tests/skeleton.spec.ts`.
- Repo plumbing: `docker-compose.yml` (Postgres 16 only), `README.md`,
  `package.json` (root convenience scripts), `.gitignore`,
  `.github/workflows/ci.yml` (backend test job with Testcontainers, frontend build job,
  e2e job with Postgres service container).

## Environment facts (context for judgment, not defects)

- The app runs on port **8081** because port 8080 on the dev machine is occupied by an
  unrelated Tomcat. This is deliberate and consistent across backend/frontend-proxy/E2E.
- Integration tests were verified locally against a downloaded PostgreSQL 16.15
  (`claims_test`); the Testcontainers path could not be executed in this sandbox (no
  Docker daemon) — assess the code path and CI, don't require it to have been run here.
- Testcontainers is **2.0.5**, where `PostgreSQLContainer` is no longer generic.
- Lint/format CI gating is deferred to slice 1 (no ESLint config); current CI gates are
  compile/build + the three test layers. Flag if you think this is wrong for slice 0.
- `docs/progress.md` documents state, test counts (unit 4, integration 2, E2E 1 — all
  green), and what to verify next. Claims of green were verified by execution.

## Checklist

1. **Scope** — answer literally, file by file: *what was built that the slice didn't ask
   for?* Then the YAGNI items: any abstraction/interface/base class with one
   implementation; any config option/env var nobody requested; any parameter/hook with no
   caller; any handling for a case requirements ruled out; any cache/optimization without
   measurement; any dead code (e.g. `TestClaimsBackendApplication`, the context smoke
   test, unused generated scaffold).
2. **Requirements coverage** — for each of the 9 "Done" items in `03-skeleton.md`, name
   the file/test that satisfies it. If none, that's a finding. Reverse: does anything in
   the tree serve no slice-0 criterion?
3. **Test quality** — do the tests assert user-visible behavior or implementation
   details? Would each fail if the feature broke (walk the two most important)? Any fixed
   sleeps, ordering dependence, shared mutable state (look hard at the static container in
   `TestcontainersConfiguration` and the shared `claims_test` DB)? Are error paths tested?
   Is the structural DTO test (no email/coverage in `PolicySummary`) meaningful or
   tautological?
4. **Correctness & security** — no auth is expected in slice 0, so scope is: is the read
   path correct (sorting, mapping, serialization)? Queries parameterized (JPA)? Any
   secrets in the repo (look at compose, properties, CI)? Any error path that leaks stack
   traces? Anything that could corrupt data — including the C3 question: could the test
   fallback ever truncate the dev `claims` database, and is the migration/seed idempotent
   for repeated test runs?
5. **Readability** — names, single-purpose functions, comments that say *why* not *what*.
6. **README & fresh-clone honesty** — do the documented commands match the actual repo
   (ports, artifact names, Boot 4 starter names, wrapper vs system mvn)? Could a fresh
   clone following README reach green without undocumented steps?

## Output format

Group findings by severity:

- **Blocking** — wrong, unsafe, or loses data. Fix before the next slice.
- **Should fix** — speculative code, missing test coverage, real design problems.
- **Optional** — style, naming, small cleanups.

Each finding: file, line, what's wrong, what to do instead. Then one short verdict
paragraph. The user decides what gets fixed — you only produce the list.
