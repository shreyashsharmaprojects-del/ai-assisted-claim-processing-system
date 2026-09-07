/** Screenshot run for the sales demo PDF. Real app, real data — no mocks.
 *
 * Uses the LIVE dev stack (frontend :4200 dev proxy -> backend :8081 -> dev `claims`
 * DB, freshly demo-seeded) rather than the hermetic E2E stack, because the demo seed
 * lives in the dev DB by design. Keycloak :8090 provides SSO.
 *
 * Flow: register a fresh claimant -> file FNOL against POL-10001 -> capture claimant
 * screens -> sign in as adjuster.one (L1) -> capture queue + claim detail -> sign in
 * as supervisor -> capture overview/escalations/policies/admin screens.
 *
 * Viewport 1440x900 (fits A4 half-page panels at 2x DPR crisply). Screenshots land in
 * demo/shots/ (gitignored — they hold demo PII-ish names), and the PDF build embeds
 * them directly. Run: npx playwright test --config shots.config.ts
 */
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

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
    // CI injects real env vars instead.
  }
}
loadEnvFile();

function requiredEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set — copy .env.example to .env (or set it in CI).`);
  return value;
}

const stamp = Date.now();
const claimantUser = `shotclaim${stamp}`;
const claimantEmail = `${claimantUser}@example.test`;

async function registerClaimant(page: Page): Promise<void> {
  await page.goto('/claim/new');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();
  await page.locator('#firstName').fill('Shot');
  await page.locator('#lastName').fill('Claimant');
  await page.locator('#email').fill(claimantEmail);
  await page.locator('#username').fill(claimantUser);
  await page.locator('#password').fill('claims-Pass-123');
  await page.locator('#password-confirm').fill('claims-Pass-123');
  await page.getByRole('button', { name: 'Register' }).click();
  await expect(page.getByTestId('fnol-policy-number')).toBeVisible();
}

async function signIn(page: Page, username: string, password: string, landingTestId: string): Promise<void> {
  await page.goto('/queue');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await expect(page.getByTestId(landingTestId)).toBeVisible();
}

test('capture demo screenshots against the live seeded stack', async ({ browser }) => {
  // ---- Claimant: register + file FNOL ----
  const claimantCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const claimant = await claimantCtx.newPage();
  await registerClaimant(claimant);

  // FNOL step 1 against the seeded book policy.
  await claimant.getByTestId('fnol-policy-number').fill('POL-10001');
  await claimant.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await claimant.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await claimant.screenshot({ path: 'shots/01-fnol-step1.png' });
  await claimant.getByTestId('fnol-next').click();
  await expect(claimant.getByTestId('fnol-loss-date')).toBeVisible();

  await claimant.getByTestId('fnol-loss-date').fill('2026-09-01');
  await claimant.getByTestId('fnol-loss-location').fill('London');
  await claimant.getByTestId('fnol-loss-description').fill('Kitchen flooded after a pipe burst.');
  await claimant.screenshot({ path: 'shots/02-fnol-step2.png' });
  await claimant.getByTestId('fnol-submit').click();

  await expect(claimant.getByTestId('claim-number')).toBeVisible();
  const claimNumber = (await claimant.getByTestId('claim-number').textContent())!.trim();
  await expect(claimant.getByTestId('claim-steps')).toContainText('Under review');
  await claimant.screenshot({ path: 'shots/03-fnol-confirmation.png' });

  // Claimant's own status screen: steps, never internals.
  await claimant.goto(`/claim/${claimNumber}`);
  await expect(claimant.getByTestId('claim-status-page')).toBeVisible();
  await claimant.screenshot({ path: 'shots/04-claimant-status.png' });

  // Claimant history.
  await claimant.goto('/claims');
  await expect(claimant.getByTestId('my-claims-page')).toBeVisible();
  await claimant.screenshot({ path: 'shots/05-my-claims.png' });
  await claimantCtx.close();

  // ---- Adjuster (L1): queue + claim detail ----
  const adjCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const adj = await adjCtx.newPage();
  await signIn(adj, 'adjuster.one', requiredEnv('ADJUSTER_PASSWORD'), 'queue-page');
  await adj.screenshot({ path: 'shots/06-adjuster-queue.png' });

  // Open the seeded L1 UNDER_REVIEW demo claim (deterministic, has reserve set).
  await adj.goto('/queue');
  await expect(adj.getByTestId('queue-page')).toBeVisible();
  const seededRow = adj.getByTestId('queue-row').filter({ hasText: 'CLM-000056' });
  if ((await seededRow.count()) === 1) {
    await seededRow.getByTestId('queue-open-claim').click();
    await expect(adj.getByTestId('claim-detail-page')).toBeVisible();
  } else {
    // Fallback: open whatever the queue holds.
    await adj.getByTestId('queue-open-claim').first().click();
    await expect(adj.getByTestId('claim-detail-page')).toBeVisible();
  }
  await adj.screenshot({ path: 'shots/07-claim-detail.png' });
  await adjCtx.close();

  // ---- Supervisor: overview + escalations + policies + authority ----
  const supCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const sup = await supCtx.newPage();
  await sup.goto('/overview');
  await expect(sup).toHaveURL(/realms\/claims/);
  await sup.locator('#username').fill('supervisor');
  await sup.locator('#password').fill(requiredEnv('SUPERVISOR_PASSWORD'));
  await sup.locator('#kc-login').click();
  await expect(sup.getByTestId('overview-page')).toBeVisible();
  await sup.screenshot({ path: 'shots/08-overview.png' });

  await sup.goto('/escalations');
  await expect(sup.getByTestId('escalations-page')).toBeVisible();
  await sup.screenshot({ path: 'shots/09-escalations.png' });

  await sup.goto('/admin/policies');
  await expect(sup.getByTestId('policies-page')).toBeVisible();
  await sup.screenshot({ path: 'shots/10-policies.png' });

  await sup.goto('/admin/authority');
  await expect(sup.getByTestId('auth-page')).toBeVisible();
  await sup.screenshot({ path: 'shots/11-authority.png' });
  await supCtx.close();
});
