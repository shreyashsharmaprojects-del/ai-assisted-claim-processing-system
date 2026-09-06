import { defineConfig } from '@playwright/test';

/**
 * E2E journeys run against the dedicated `claims_e2e` database (see docker/db-init and
 * docs/decisions.md), never the dev `claims` database, because slice-1 journeys write
 * claims. The stack is `docker compose up -d` (Postgres + Mailpit + Keycloak).
 *
 * Playwright boots the backend itself on a DEDICATED port (8082) with env overrides
 * pointing at claims_e2e — reusing a developer's dev-claims backend is deliberately
 * impossible — and the Angular dev server with a matching proxy. Reuse of already-running
 * servers is disabled for the backend by design; reuseExistingServer is kept for the
 * frontend only on developer machines.
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
      command: 'mvn -q -f ../backend/pom.xml spring-boot:run',
      cwd: '.',
      url: 'http://localhost:8082/api/policies',
      reuseExistingServer: false,
      timeout: 180_000,
      env: {
        SERVER_PORT: '8082',
        SPRING_DATASOURCE_URL: 'jdbc:postgresql://localhost:5432/claims_e2e',
        SPRING_DATASOURCE_USERNAME: 'claims',
        SPRING_DATASOURCE_PASSWORD: 'claims',
      },
    },
    {
      command: 'npm start -- --proxy-config proxy.e2e.conf.json',
      cwd: '../frontend',
      url: 'http://localhost:4200',
      reuseExistingServer: !process.env.CI,
      timeout: 180_000,
    },
  ],
});
