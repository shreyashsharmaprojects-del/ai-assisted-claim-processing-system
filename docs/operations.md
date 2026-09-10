# Operations runbook — Claims Processing System (production hardening release)

This is the page you open at 2am. Everything here is copy-pasteable.

## Service map

| Piece | Prod location | Health gate |
|---|---|---|
| App (nginx SPA + `/api` proxy) | `frontend:80` → host `:8080` | `GET /` returns 200 + `index.html` |
| Backend (Spring Boot jar) | `backend:8080` | `GET /api/ready` → `{"status":"READY"}` |
| Postgres 16 (`claims` + `claims_e2e` in dev; `claims` only in prod) | `db:5432` | `pg_isready` (compose healthcheck) |
| Keycloak 26 (realm `claims`) | `keycloak:8080` | `GET /realms/claims/.well-known/openid-configuration` → 200 |
| Mail | `SMTP_HOST:SMTP_PORT` (Mailpit catcher in staging) | send a test FNOL, check the inbox |

Liveness vs readiness: `/api/health` = "the process is up" (load-balancer liveness).
`/api/ready` = "migrations are current AND a query succeeds" (traffic gate). Gate
deploy traffic on **readiness**, never liveness.

## Deploy

Prerequisites: Docker + a shell with the prod secrets in the environment
(`DB_USERNAME`, `DB_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`, `ADJUSTER_PASSWORD`,
`SUPERVISOR_PASSWORD`, `CLAIMANT_PASSWORD`, `KEYCLOAK_URL` = the public Keycloak URL).

```bash
# 1. Render the realm from secrets (passwords never touch git).
npm run realm:render
# Re-render EVERY time keycloak/realm-export.template.json changes — the running
# Keycloak only imports realm-export.json, and only on container CREATE (a plain
# `restart` keeps the old realm: the V2-1 staff never appeared until the container
# was removed and recreated). After user/role edits: re-render, then
# `docker compose rm -f keycloak && docker compose up -d keycloak` in dev.
# Backend↔realm identity is the Keycloak subject: app_user.keycloak_sub must equal
# the realm user's id (template UUIDs 10000000-...-000001..7). Users created via
# the Admin API get random subs and see EMPTY queues until the subs match — prefer
# the template + fresh import over hand-created users.

# 2. Point the SPA at this environment's Keycloak.
cp deploy/config.json /tmp/config.json  # then edit keycloakUrl to $KEYCLOAK_URL
# (or generate it: printf '{"keycloakUrl":"%s","keycloakRealm":"claims","keycloakClientId":"claims-frontend"}' "$KEYCLOAK_URL" > deploy/config.json)

# 3. Build + start. Flyway migrates on backend boot (V1..V8 + seeds).
docker compose -f docker-compose.prod.yml up -d --build

# 4. Gate on readiness before routing traffic.
for i in $(seq 1 30); do
  curl -sf http://localhost:8080/api/ready && break || sleep 5
done
```

Rolling back: redeploy the previous image tags (`BACKEND_IMAGE=` /
`FRONTEND_IMAGE=`). Migrations are forward-only (the audit log is immutable — no
down-migrations exist); a schema rollback means restoring the database from a backup
taken before the migration (below).

## Backup & restore

```bash
# Nightly backup (cron this on the db host):
docker compose -f docker-compose.prod.yml exec -T db \
  pg_dump -U "$DB_USERNAME" -d claims -Fc > "claims-$(date +%F).dump"
# Uploads live on the claims-uploads volume — snapshot it with the dump:
docker run --rm -v claims-prod_claims-uploads:/data -v "$PWD:/out" \
  alpine tar czf "/out/uploads-$(date +%F).tgz" -C /data .

# Restore into a fresh database:
docker compose -f docker-compose.prod.yml exec -T db \
  pg_restore -U "$DB_USERNAME" -d claims --clean < claims-YYYY-MM-DD.dump
docker run --rm -v claims-prod_claims-uploads:/data -v "$PWD:/in" \
  alpine tar xzf /in/uploads-YYYY-MM-DD.tgz -C /data
```

Restore checklist: re-point `SPRING_DATASOURCE_URL` if the DB moved, then hit
`/api/ready` before opening traffic.

### Restore-pairing gate (DB + volume together — both, or neither)

`attachment.storage_path` is a database pointer to a file on the
`claims-uploads` volume. A DB backup without the volume snapshot (or vice versa)
silently orphans evidence: every restore MUST apply the matching pair from the
same timestamp (`claims-YYYY-MM-DD.dump` + `uploads-YYYY-MM-DD.tgz`). After the
restore, confirm the pairing before opening traffic:

```bash
# Orphan check: every attachment row must resolve to a file on the volume.
# <UPLOADS_DIR> is the host path behind the claims-uploads volume.
docker compose -f docker-compose.prod.yml exec -T \
  -e PGPASSWORD="$DB_PASSWORD" db \
  psql -U "$DB_USERNAME" -d claims -t -A -c "SELECT storage_path FROM attachment;" \
  | while read -r p; do [ -e "<UPLOADS_DIR>/$p" ] || echo "MISSING: $p"; done
curl -sf http://localhost:8080/api/ready   # traffic gate, as above
```

A `MISSING` line means the pair is mismatched — stop and re-restore the correct
pair. (S2 LANDED below: `PhotoStorage` now has two implementations —
`FilesystemPhotoStorage` (default) and `S3PhotoStorage`
(`claims.storage.backend=s3`); legacy absolute-path rows resolve on the filesystem
backend only.)

## Metrics & alerting (R3)

`GET /api/metrics` (supervisor-scoped bearer token; anonymous → 401, claimant/adjuster
→ 403) returns JSON counters + live gauges — the numbers to dashboard and page on
instead of log-grep. No per-claim PII appears (claim numbers never label values).
Deliberately dependency-free JSON (not Prometheus text) so the offline build stays
hermetic; a Prometheus registry + `/actuator/prometheus` is the P1 upgrade path, mapping
each JSON key 1:1 to a gauge/counter (PromQL sketches in the table).

Shape: `{claims_fnol_total, claims_fnol_rejected_total{reason}, claims_decisions_total
{outcome}, claims_escalations_total{target}, claims_queue_depth{level},
claims_outbox_pending, claims_outbox_failed_total}`. The outbox keys report 0 until the
V10 `email_outbox` table lands (rule 1 is pending V10).

### What to alert on

| # | Signal | Threshold (tune per carrier) | JSON check (today) | PromQL (P1 prometheus) |
|---|---|---|---|---|
| 1 | Outbox FAILED growth | `claims_outbox_failed_total` increases over 15m | poll `/api/metrics`, diff `claims_outbox_failed_total` | `increase(claims_outbox_failed_total[15m]) > 0` (V10 table live) |
| 2 | 5xx rate | > 1% of responses over 5m | count `Reference:` log lines vs access log | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) > 0.01` |
| 3 | FNOL 429 spike | `claims_fnol_rejected_total{reason="rate_limited"}` jumps | diff `claims_fnol_rejected_total.rate_limited` over 5m | `increase(claims_fnol_rejected_total{reason="rate_limited"}[5m]) > 50` |
| 4 | Queue depth by level | `UNDER_REVIEW` > 200 or `ESCALATED_SUPERVISOR` > 50 | `claims_queue_depth.UNDER_REVIEW`, `.ESCALATED_SUPERVISOR` | `claims_queue_depth{level="UNDER_REVIEW"} > 200`, `claims_queue_depth{level="ESCALATED_SUPERVISOR"} > 50` |

Triage query for FAILED outbox rows (V10+): `SELECT id, claim_id, kind, to_address,
attempts, last_error FROM email_outbox WHERE status = 'FAILED' ORDER BY created_at DESC
LIMIT 50;` — `last_error` + the claim number go to the carrier; the request id is in the
error log.

## Photo storage & moving to S3 (R5 → S2)

Evidence photos live behind the `PhotoStorage` interface (`store/load/deleteClaimDir`).
Two implementations share the S1 validation gates via `PhotoValidator`
(count → content-type → size → magic bytes, byte-identical messages):
`FilesystemPhotoStorage` (bean `photoStorage` when
`claims.storage.backend=filesystem`, the default) and `S3PhotoStorage` (bean
`s3PhotoStorage`, active when `claims.storage.backend=s3` — zero-dependency
`HttpClient` + hand-rolled SigV4 PUT/GET/HEAD/DELETE). Files land at
`{baseDir}/{claimId}/{uuid}{ext}` (filesystem) or `{bucket}/{claimId}/{uuid}{ext}`
(S3), and `attachment.storage_path` holds the portable object key
(`{claimId}/{uuid}{ext}`) — the base dir (`claims.uploads.dir` /
`CLAIMS_UPLOADS_DIR`) or bucket is resolved at runtime, never stored. V11
converted legacy absolute-path rows to key form
(`regexp_replace(storage_path, '^.*([^/]+/[^/]+)$', '\1')`, null-safe;
non-absolute and short paths untouched). Downloads serve via
`photoStorage.load()` on both backends (legacy absolute paths resolve on the
filesystem backend only), and rollback-delete (`deleteClaimDir`) removes the
claim prefix/dir when the FNOL transaction rolls back.

### S3 config (all in `application.properties` + `.env.example`)

| Key | Default | Notes |
|---|---|---|
| `claims.storage.backend` | `filesystem` | `filesystem` or `s3`; flips the `photoStorage` bean |
| `claims.storage.backfill` | `false` | set `true` once for the one-shot backfill |
| `claims.s3.endpoint` | `http://localhost:9000` | MinIO dev, or the real S3 endpoint in prod |
| `claims.s3.bucket` | `claims-evidence` | auto-created by the `minio-init` compose job |
| `claims.s3.region` | `us-east-1` | SigV4 scope region |
| `claims.s3.access-key` / `secret-key` | empty | env-only (`CLAIMS_S3_*`), never committed |

Dev: `docker compose up -d` starts MinIO (S3 API `:9000`, console `:9001`);
run the backend with `CLAIMS_STORAGE_BACKEND` unset (filesystem default) or
`claims.storage.backend=s3` + the `CLAIMS_S3_*` vars for S3 mode. Prod
(`docker-compose.prod.yml`): set `CLAIMS_STORAGE_BACKEND=s3` with
`CLAIMS_S3_ENDPOINT` + bucket/region/credentials from the environment.

### Backfill runbook (one-shot, filesystem → S3)

1. Deploy with `claims.storage.backend=s3` and `claims.storage.backfill=true` once
   (or run the job explicitly) — `S3BackfillRunner` iterates every attachment row
   with a portable key, PUTs each local file, verifies the sha pin, and logs
   missing-file orphans. Legacy absolute-path rows are skipped with a warning.
2. Check the logs: `S3 backfill complete: rows=… uploaded=… verified=… orphans=…
   skipped=…`. Every orphan (`Backfill orphan: attachment … has no local file`)
   must be investigated — the DB row points at bytes that no longer exist.
3. The job never deletes local files itself: only after the orphan check is
   clean does the operator remove the old volume files.
4. Set `claims.storage.backfill=false` again (one-shot — it must not run on
   every boot).

### Restore-pairing gate (DB + bucket together — both, or neither)

`attachment.storage_path` is a database pointer to an object in the bucket (S3)
or a file on the `claims-uploads` volume (filesystem). A DB backup without the
matching bucket/volume snapshot (or vice versa) silently orphans evidence: every
restore MUST apply the matching pair from the same timestamp
(`claims-YYYY-MM-DD.dump` + `uploads-YYYY-MM-DD.tgz`, or the DB dump + a bucket
versioning snapshot as one atomic pair). After the restore, confirm the pairing
before opening traffic:

```bash
# Filesystem backend: every attachment row must resolve to a file on the volume.
# <UPLOADS_DIR> is the host path behind the claims-uploads volume.
docker compose -f docker-compose.prod.yml exec -T \
  -e PGPASSWORD="$DB_PASSWORD" db \
  psql -U "$DB_USERNAME" -d claims -t -A -c "SELECT storage_path FROM attachment;" \
  | while read -r p; do [ -e "<UPLOADS_DIR>/$p" ] || echo "MISSING: $p"; done
# S3 backend: every attachment key must HEAD in the bucket (aws cli or the
# backfill dry-run log); same MISSING discipline — stop on any miss.
curl -sf http://localhost:8080/api/ready   # traffic gate, as above
```

A `MISSING` line means the pair is mismatched — stop and re-restore the correct
pair. (S2 LANDED above: `PhotoStorage` has two implementations —
`FilesystemPhotoStorage` (bean `photoStorage`, default) and `S3PhotoStorage`
(bean `s3PhotoStorage`, active when `claims.storage.backend=s3`) — and
`attachment.storage_path` holds the portable object key `{claimId}/{uuid}{ext}`;
V11 converted legacy absolute-path rows, null-safe.)

## IP-level flood protection (R6)

nginx `limit_req` on FNOL (`frontend/nginx.conf`): `limit_req_zone
$binary_remote_addr zone=fnol:10m rate=5r/s` + `limit_req zone=fnol burst=10 nodelay` on
`location = /api/claims`. 5 req/s sustained per client IP with a 10-request burst
(delayed, not rejected): real claimants file once, multipart uploads are slow, and a
botnet of throwaway claimant accounts hits per-IP shaping before it hits the app. The
per-claimant 20/day app rule still applies inside. No Java changes; no compose changes
(the frontend image already ships `nginx.conf` — the `frontend` service needs no new
wiring). App-layer per-IP accounting (per-IP counters, adaptive bans) is the WAF's job
if this ever needs to be smarter — nginx here is coarse flood protection, not policy.

## Carrier onboarding checklist (sale #1: one realm, one stack per carrier)

Tenancy decision (see `docs/decisions.md`, R7 entry): each carrier gets a full
stack with its own Keycloak realm rendered from the template — row-level
multi-tenancy is Series-A, not this sale.

```bash
# 1. Realm render — copy keycloak/realm-export.template.json entry for the new
#    carrier (new realm name, e.g. "carrier-acme"), then render secrets in:
DB_USERNAME=... DB_PASSWORD=... KEYCLOAK_ADMIN_PASSWORD=... \
ADJUSTER_PASSWORD=... SUPERVISOR_PASSWORD=... CLAIMANT_PASSWORD=... npm run realm:render
# 2. Provision — staff Keycloak users + matching app_user rows (levels L1/L2/L3;
#    V2-1 added the adjuster_l3 realm role + L3 staff; the supervisor needs a
#    Keycloak user ONLY (no app_user row, by design).
# 3. Authority limits — PUT /api/config/authority/{HOME,AUTO} (supervisor) to the
#    carrier's ladder; examples in api.http. New rows route/classify immediately.
# 4. Import policies — POST /api/policies/import (supervisor, ≤ 500 rows/CSV,
#    header policy_number,product_code,holder_name,holder_email,coverage).
#    Unknown product codes are rejected per row with the valid list — fix the
#    authority table first if the carrier brings a new product.
# 5. Demo seed (sales walkthroughs only, dev claims DB — never prod):
npm run demo:seed    # 6 POL-DEMO-* policies + claims at each ladder rung, wall-safe
npm run demo:reset   # removes seeded rows (audit_log rows stay: append-only)
# 6. Verify gate — /api/ready → 200, an FNOL against an imported policy
#    classifies + assigns, a supervisor decision closes, the decision mail lands.
```

## Common incidents

**Users see "Your session has expired" everywhere.**
Keycloak is down or its clock drifted (token validation fails closed). Check
`docker compose -f docker-compose.prod.yml logs keycloak`, then the realm URL from the
backend container: `wget -qO- $ISSUER/.well-known/openid-configuration`.

**FNOL submissions return 429 ("Too many claims filed recently").**
Legitimate burst or a stuck retry loop: the cap is `claims.fnol.rate-limit-per-day`
(default 20/claimant/24h). Inspect the ledger:
`SELECT claimant_sub, count(*) FROM fnol_submission WHERE created_at > now() - interval '1 day' GROUP BY 1 ORDER BY 2 DESC;`
Raise the env var only deliberately — the 429 carries a `Retry-After` hint, clients
back off on their own.

**Claim stuck UNASSIGNED (no adjuster picked it up).**
No adjuster of that level is provisioned (the assigner logs a WARN with the claim
number). Provision the Keycloak user + `app_user` row (same level), or have a
supervisor reassign the claim to a staffed level.

**Aging job moved claims unexpectedly.**
The 03:00 job (tenant time — `claims.timezone.default`, default Europe/London; day
counts are calendar days in that zone) pushes 3-day-undecided claims to L2 and 5-day
to the supervisor; every
move is a `CLAIM_ESCALATED` audit row with a NULL actor and an "Aged at least N days"
rationale. Query the audit log by claim to confirm before touching anything:
`GET /api/claims/<number>/audit` (supervisor).

**A user reports "Something went wrong. (Reference: xxxx)".**
Grep the backend logs for the request id: `grep rid=xxxx app.log` (every response
echoes `X-Request-Id`, bound to `%X{rid}` in the log pattern). The full stack trace is
server-side; the user never saw internals.

**Uploads return "too large" / photos missing after a restore.**
App cap is `claims.uploads.*` (10 MB/file, 5 files); the multipart ceiling sits above
it so the error is readable. Files live on disk under `CLAIMS_UPLOADS_DIR` with paths
in `attachment.storage_path` — a DB restore without the volume restore orphans them.
Restore both together (above).

## Security notes

- Secrets live in the environment, never in git (`.env` is gitignored; CI injects
  them as job env). Rotate Keycloak bootstrap + realm passwords per environment.
- The `claims` DB user should own only the `claims` database; backups are encrypted
  at rest by your storage layer.
- Rate limiting is per-claimant (application layer, 20/day) plus nginx IP-level
  flood protection on FNOL: `limit_req_zone $binary_remote_addr zone=fnol:10m
  rate=5r/s`, `limit_req zone=fnol burst=10 nodelay` on `location = /api/claims`
  (see "IP-level flood protection" above). App-layer per-IP accounting is the WAF's
  job if this ever needs to be smarter.
- CORS: the SPA is same-origin through nginx in prod (no cross-origin API exposure).
  If you split the frontend to a CDN later, lock `Access-Control-Allow-Origin` to
  that origin — never `*` with credentials.
