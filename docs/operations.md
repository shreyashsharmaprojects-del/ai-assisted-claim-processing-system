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
`SUPERVISOR_PASSWORD`, `KEYCLOAK_URL` = the public Keycloak URL).

```bash
# 1. Render the realm from secrets (passwords never touch git).
npm run realm:render

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
The 03:00 job pushes 3-day-undecided claims to L2 and 5-day to the supervisor; every
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
- Rate limiting is per-claimant (application layer). Put a WAF / reverse-proxy limit
  (e.g. nginx `limit_req`) in front of `/api/claims` for IP-level floods.
- CORS: the SPA is same-origin through nginx in prod (no cross-origin API exposure).
  If you split the frontend to a CDN later, lock `Access-Control-Allow-Origin` to
  that origin — never `*` with credentials.
