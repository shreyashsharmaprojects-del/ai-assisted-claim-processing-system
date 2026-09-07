import { defineConfig } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Load ../.env into process.env (no dotenv dependency). Real environment variables (CI)
 * always win over .env values, so a deployment can inject credentials as secrets.
 */
function loadEnvFile(): void {
  try {
    const raw = readFileSync(resolve(process.cwd(), '../.env'), 'utf8');
    for (const line of raw.split('\n')) {
      const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$/);
      if (match && process.env[match[1]] === undefined) {
        process.env[match[1]] = match[2].replace(/^["']|["']$/g, '');
      }
    }
  } catch {
    // No .env file — rely on the real environment (CI) or the stack will fail loudly.
  }
}
loadEnvFile();

/**
 * E2E journeys run against the dedicated `claims_e2e` database (see docker/db-init and
 * docs/decisions.md), never the dev `claims` database, because slice-1 journeys write
 * claims. The stack is `docker compose up -d` (Postgres + Mailpit + Keycloak).
 *
 * Playwright boots the whole web stack itself — backend on :8082 (claims_e2e) plus its
 * own ng serve on :4200 with the E2E proxy config. Reuse of already-running servers is
 * deliberately disabled for BOTH: a developer's dev backend/frontend on these ports serves
 * stale code (old auth rules, missing endpoints, wrong proxy target) yet answers the
 * readiness probes, so reusing it fails journeys with confusing 403/404s — or worse,
 * false greens — instead of a loud boot error.
 */
export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  // Not fully parallel: journeys drive the same Keycloak realm (login/registration), and
  // concurrent authorization-code flows against it are flaky. Files still run in parallel.
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:4200',
    trace: 'on-first-retry',
  },
  webServer: [
    {
      // E2E backend: a dedicated spring-boot:run on :8082 (claims_e2e). reuseExistingServer
      // is FALSE by design: an earlier session's dev backend on this port serves stale
      // code (old auth rules, missing endpoints) and its /api/policies probe passes, so
      // reusing it fails journeys with confusing 403/404s instead of a loud boot error.
      command: 'mvn -q -f ../backend/pom.xml spring-boot:run',
      cwd: '.',
      url: 'http://localhost:8082/api/ready',
      reuseExistingServer: false,
      timeout: 180_000,
      stdout: 'pipe',
      stderr: 'pipe',
      env: {
        SERVER_PORT: '8082',
        SPRING_DATASOURCE_URL: 'jdbc:postgresql://localhost:5432/claims_e2e',
        DB_USERNAME: process.env.DB_USERNAME!,
        DB_PASSWORD: process.env.DB_PASSWORD!,
      },
    },
    {
      // E2E frontend: its own ng serve on :4200 with the E2E proxy (backend :8082).
      // reuseExistingServer is FALSE: a developer's ng serve on this port uses the DEV
      // proxy (backend :8081) and serves stale or wrong code — it answers the / probe,
      // so Playwright would silently run journeys against the wrong stack and fail with
      // confusing 403/404s instead of a loud boot error.
      command: 'npm start -- --proxy-config proxy.e2e.conf.json',
      cwd: '../frontend',
      url: 'http://localhost:4200',
      reuseExistingServer: false,
      timeout: 180_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
  ],
});
