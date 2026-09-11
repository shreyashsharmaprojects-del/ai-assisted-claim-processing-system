import { defineConfig } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/** Load ../.env into process.env (real environment variables always win). */
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
    // No .env file — rely on the real environment.
  }
}
loadEnvFile();

/**
 * New-capability screenshot config: runs ONLY tests/new-shots.spec.ts against
 * the LIVE dev stack (frontend :4200 dev proxy -> backend :8081 -> dev
 * `claims` DB). The :4200 ng serve hot-reloads workspace changes, so the
 * session's new code (AI drawer, clauses, policy modal) is served there.
 * No webServer entries — reusing the running stack is the entire point.
 * Run AFTER the base shots run:
 *   npx playwright test --config new-shots.config.ts
 */
export default defineConfig({
  testDir: './tests',
  testMatch: /new-shots\.spec\.ts/,
  timeout: 180_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:4200',
    screenshot: 'off',
  },
});
