/** New-capability screenshots for the demo PDF (session additions).
 *
 * Covers what the base shots.spec.ts run predates:
 *  43 — AI assistant chat (drawer, live Q&A on the claim workspace)
 *  44 — policy clauses panel (scoped wording, expanded row)
 *  45 — policy page in a modal (whole policy detail in place, File-claim hidden for staff)
 *
 * Same live-stack contract as shots.spec.ts: fresh :4210 frontend (has the new
 * code) -> backend :8081 -> dev `claims` DB. Files one throwaway MATERNITY
 * claim ("Shot demo:" marker — cleaned by `npm run demo:reset`), reuses the
 * demo seed for everything else. Viewport 1440x900.
 *
 * Run from e2e/:  npx playwright test --config new-shots.config.ts
 */
import { expect, test } from '@playwright/test';
import type { Browser, Page } from '@playwright/test';
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
const shotLossDate = new Date(Date.UTC(2020, 0, 1) + (Math.floor(stamp / 1000) % 2000) * 86400000)
  .toISOString()
  .slice(0, 10);

async function registerClaimant(page: Page, prefix: string): Promise<void> {
  const user = `${prefix}${stamp}`;
  // Force the Keycloak bounce via a guarded route (fresh :4210 frontend uses
  // silent SSO — public pages render without redirecting).
  await page.goto('/claims');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();
  await page.locator('#firstName').fill('Shot');
  await page.locator('#lastName').fill('Claimant');
  await page.locator('#email').fill(`${user}@example.test`);
  await page.locator('#username').fill(user);
  await page.locator('#password').fill('claims-Pass-123');
  await page.locator('#password-confirm').fill('claims-Pass-123');
  await page.getByRole('button', { name: 'Register' }).click();
  // Post-registration lands back on the entry URL (/claims) — walk to FNOL.
  await page.goto('/claim/new');
  await expect(page.getByTestId('fnol-policy-number')).toBeVisible({ timeout: 30000 });
}

async function signIn(page: Page, username: string, password: string, landingTestId: string): Promise<void> {
  await page.goto('/queue');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await expect(page.getByTestId(landingTestId)).toBeVisible({ timeout: 30000 });
}

const L1_ROSTER = ['adjuster.one', 'adjuster.two', 'adjuster.four'];

async function openClaimAsHolder(browser: Browser, claimNumber: string): Promise<Page> {
  for (const username of L1_ROSTER) {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    const page = await ctx.newPage();
    await signIn(page, username, requiredEnv('ADJUSTER_PASSWORD'), 'queue-page');
    await page.goto('/queue');
    await expect(page.getByTestId('queue-page')).toBeVisible();
    const row = page.getByTestId('queue-row').filter({ hasText: claimNumber });
    try {
      await expect(row).toHaveCount(1, { timeout: 8000 });
      await row.getByTestId('queue-open-claim').click();
      await expect(page.getByTestId('claim-detail-page')).toBeVisible();
      return page;
    } catch {
      await ctx.close();
    }
  }
  throw new Error(`live claim row not found in any L1 queue: ${claimNumber}`);
}

test('capture new-capability screenshots for the demo PDF', async ({ browser }) => {
  const claimantCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const claimant = await claimantCtx.newPage();
  await registerClaimant(claimant, 'shotnew');

  // One throwaway MATERNITY claim on the seeded HLTH-PLUS policy (clause +
  // chat assertions need MATERNITY wording in scope).
  await claimant.getByTestId('fnol-policy-number').fill('POL-10001');
  await claimant.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await claimant.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await claimant.getByTestId('fnol-next').click();
  await expect(claimant.getByTestId('fnol-covers')).toBeVisible();
  await claimant.getByTestId('fnol-cover-MATERNITY').check();
  await claimant.getByTestId('fnol-amount-MATERNITY').fill('50000');
  await claimant.getByTestId('fnol-loss-date').fill(shotLossDate);
  await claimant.getByTestId('fnol-loss-location').fill('London');
  await claimant.getByTestId('fnol-loss-description').fill('Shot demo: maternity case for the new-capability pages.');
  await claimant.getByTestId('fnol-submit').click();
  await expect(claimant.getByTestId('claim-number')).toBeVisible();
  const claimNumber = (await claimant.getByTestId('claim-number').textContent())!.trim();
  await claimantCtx.close();

  const holder = await openClaimAsHolder(browser, claimNumber);
  await expect(holder.getByTestId('detail-review-panel')).toBeVisible();

  // 44 — clauses panel (Reference section tab): panel loads (product row
  // 1.1 proves it), then the MATERNITY sub-limit row expanded.
  await holder.getByTestId('detail-section-reference').click();
  await expect(holder.getByTestId('detail-clauses')).toBeVisible();
  await expect(holder.getByTestId('detail-clauses-error')).toHaveCount(0);
  await expect(holder.getByTestId('detail-clause-1.1')).toBeVisible({ timeout: 30000 });
  const maternityRow = holder.getByTestId('detail-clause-4.5');
  await expect(maternityRow).toBeVisible({ timeout: 30000 });
  const toggle = maternityRow.getByTestId('detail-clause-toggle');
  if ((await toggle.textContent())?.includes('Show wording')) {
    await toggle.scrollIntoViewIfNeeded();
    await toggle.click();
  }
  await holder.getByTestId('detail-clauses').scrollIntoViewIfNeeded();
  await holder.waitForTimeout(400);
  await holder.screenshot({ path: 'shots/44-clauses.png' });

  // 43 — AI chat drawer: open, ask about the waiting period, shot the answered thread.
  await holder.getByTestId('detail-ai-toggle').click();
  await expect(holder.getByTestId('detail-ai-drawer')).toBeVisible();
  await holder.getByTestId('detail-ai-input').fill('Does the waiting period apply here?');
  await holder.getByTestId('detail-ai-send').click();
  await expect(holder.getByTestId('detail-ai-msg-1'), 'assistant answer renders').toBeVisible({
    timeout: 90000,
  });
  await expect(holder.getByTestId('detail-ai-chat-status')).toContainText(/Live|Fallback/i, {
    timeout: 90000,
  });
  await holder.waitForTimeout(400);
  await holder.screenshot({ path: 'shots/43-ai-chat.png' });

  // 45 — policy modal: whole policy page in place from the workspace link.
  // Close the AI drawer first so the modal owns the overlay.
  await holder.keyboard.press('Escape');
  await holder.getByTestId('detail-policy-link').click();
  await expect(holder.getByTestId('detail-policy-modal')).toBeVisible();
  await expect(holder.getByTestId('policy-detail-number')).toBeVisible({ timeout: 30000 });
  // Staff sees the page but no File-claim action.
  await expect(holder.getByTestId('policy-detail-file')).toHaveCount(0);
  await holder.waitForTimeout(500);
  await holder.screenshot({ path: 'shots/45-policy-modal.png' });

  await holder.context().close();
});
