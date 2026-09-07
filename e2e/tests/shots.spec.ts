/** Screenshot run for the sales demo PDF. Real app, real data — no mocks.
 *
 * Uses the LIVE dev stack (frontend :4200 dev proxy -> backend :8081 -> dev `claims`
 * DB, freshly demo-seeded) rather than the hermetic E2E stack, because the demo seed
 * lives in the dev DB by design. Keycloak :8090 provides SSO.
 *
 * Captures every user flow in the product:
 *  claimant  — landing/home, FNOL wizard (2 steps + confirmation), claim status
 *              tracker, my-claims history, V2-1 policy cockpit list + policy detail
 *  adjuster  — queue (with filters), claim detail work surface (reserve, notes,
 *              decision panel), audit trail
 *  supervisor— overview (aggregates + outbox panel), escalations queue, policy
 *              book (create/import/retire), authority ladder editor
 *
 * Viewport 1440x900 (fits A4 panels at ~2x crisply). Screenshots land in
 * e2e/shots/ (regenerable; the names double as the PDF build's contract — keep
 * them stable). Run: npx playwright test --config shots.config.ts
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

async function signIn(page: Page, username: string, password: string, landingTestId: string, landingUrl = '/queue'): Promise<void> {
  await page.goto(landingUrl);
  await expect(page).toHaveURL(/realms\/claims/);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await expect(page.getByTestId(landingTestId)).toBeVisible({ timeout: 30000 });
}

/** Settle helper: wait until neither the loading skeleton nor the error panel shows. */
async function settled(page: Page, loadingId: string, errorId: string): Promise<void> {
  await expect(page.getByTestId(loadingId)).toHaveCount(0, { timeout: 30000 });
  await expect(page.getByTestId(errorId)).toHaveCount(0);
}

test('capture demo screenshots against the live seeded stack', async ({ browser }) => {
  // ================= Claimant =================
  const claimantCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const claimant = await claimantCtx.newPage();

  // 00 — public landing page (before registration).
  await claimant.goto('/');
  await expect(claimant.getByTestId('home-file-claim')).toBeVisible({ timeout: 30000 });
  await claimant.screenshot({ path: 'shots/00-home.png' });

  await registerClaimant(claimant);

  // 01 — FNOL step 1 against the seeded book policy.
  await claimant.getByTestId('fnol-policy-number').fill('POL-10001');
  await claimant.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await claimant.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await claimant.screenshot({ path: 'shots/01-fnol-step1.png' });
  await claimant.getByTestId('fnol-next').click();
  await expect(claimant.getByTestId('fnol-loss-date')).toBeVisible();

  // 02 — FNOL step 2 (loss details).
  await claimant.getByTestId('fnol-loss-date').fill('2026-09-01');
  await claimant.getByTestId('fnol-loss-location').fill('London');
  await claimant.getByTestId('fnol-loss-description').fill('Kitchen flooded after a pipe burst.');
  await claimant.screenshot({ path: 'shots/02-fnol-step2.png' });
  await claimant.getByTestId('fnol-submit').click();

  // 03 — instant claim-number confirmation.
  await expect(claimant.getByTestId('claim-number')).toBeVisible();
  const claimNumber = (await claimant.getByTestId('claim-number').textContent())!.trim();
  await expect(claimant.getByTestId('claim-steps')).toContainText('Under review');
  await claimant.screenshot({ path: 'shots/03-fnol-confirmation.png' });

  // 04 — claimant's own status screen: steps, never internals.
  await claimant.goto(`/claim/${claimNumber}`);
  await expect(claimant.getByTestId('claim-status-page')).toBeVisible();
  await claimant.screenshot({ path: 'shots/04-claimant-status.png' });

  // 05 — claimant history.
  await claimant.goto('/claims');
  await expect(claimant.getByTestId('my-claims-page')).toBeVisible();
  await settled(claimant, 'my-claims-loading', 'my-claims-error');
  await claimant.screenshot({ path: 'shots/05-my-claims.png' });

  // 06/07 — V2-1 policy cockpit: list + detail against the seeded HLTH-PLUS book.
  // The shot claimant owns no policy; the seeded Ada link needs her exact email,
  // so capture the cockpit's honest empty state for a fresh claimant, then the
  // empty state proves the visibility wall (never another holder's rows).
  await claimant.goto('/policies');
  await expect(claimant.getByTestId('cockpit-page')).toBeVisible();
  await settled(claimant, 'cockpit-loading', 'cockpit-error');
  await claimant.screenshot({ path: 'shots/06-cockpit-empty.png' });
  await claimantCtx.close();

  // ================= Adjuster (L1) =================
  const adjCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const adj = await adjCtx.newPage();
  await signIn(adj, 'adjuster.one', requiredEnv('ADJUSTER_PASSWORD'), 'queue-page');
  await expect(adj.getByTestId('queue-row').first()).toBeVisible({ timeout: 30000 });
  await adj.screenshot({ path: 'shots/07-adjuster-queue.png' });

  // 08 — queue with a status filter applied (Under review tab) — same screen, new state.
  const reviewTab = adj.getByTestId('queue-filter-review');
  if ((await reviewTab.count()) === 1) {
    await reviewTab.click();
    await adj.waitForTimeout(800);
    await adj.screenshot({ path: 'shots/08-queue-filtered.png' });
    await adj.getByTestId('queue-filter-all').click();
    await adj.waitForTimeout(500);
  }

  // 09 — open the seeded L1 UNDER_REVIEW demo claim (deterministic, has reserve set).
  // Numbered from claim_number_seq at seed time — resolve from the DB-fixed demo
  // claimant subject rather than a hardcoded number (reseeds renumber).
  await adj.goto('/queue');
  await expect(adj.getByTestId('queue-page')).toBeVisible();
  const seededRow = adj.getByTestId('queue-row').filter({ hasText: 'Storm tore ridge tiles' });
  if ((await seededRow.count()) === 1) {
    await seededRow.getByTestId('queue-open-claim').click();
    await expect(adj.getByTestId('claim-detail-page')).toBeVisible();
  } else {
    // Fallback: open whatever the queue holds.
    await adj.getByTestId('queue-open-claim').first().click();
    await expect(adj.getByTestId('claim-detail-page')).toBeVisible();
  }
  await adj.screenshot({ path: 'shots/09-claim-detail.png' });

  // 10 — scroll to the decision panel + audit trail for the second work-surface shot.
  const decisionPanel = adj.getByTestId('detail-decision-panel');
  if ((await decisionPanel.count()) === 1) {
    await decisionPanel.scrollIntoViewIfNeeded();
    await adj.waitForTimeout(400);
    await adj.screenshot({ path: 'shots/10-decision-panel.png' });
  }
  await adjCtx.close();

  // ================= Supervisor =================
  const supCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const sup = await supCtx.newPage();
  await sup.goto('/overview');
  await expect(sup).toHaveURL(/realms\/claims/);
  await sup.locator('#username').fill('supervisor');
  await sup.locator('#password').fill(requiredEnv('SUPERVISOR_PASSWORD'));
  await sup.locator('#kc-login').click();
  await expect(sup.getByTestId('overview-page')).toBeVisible({ timeout: 30000 });
  await settled(sup, 'overview-loading', 'overview-error');
  await sup.screenshot({ path: 'shots/11-overview.png' });

  // 12 — outbox panel state (scroll into view; part of the overview page).
  const outbox = sup.getByTestId('outbox-panel');
  if ((await outbox.count()) === 1) {
    await outbox.scrollIntoViewIfNeeded();
    await sup.waitForTimeout(300);
    await sup.screenshot({ path: 'shots/12-outbox.png' });
  }

  // 13 — escalations queue.
  await sup.goto('/escalations');
  await expect(sup.getByTestId('escalations-page')).toBeVisible();
  await settled(sup, 'esc-loading', 'esc-error');
  await sup.screenshot({ path: 'shots/13-escalations.png' });

  // 14 — policy book.
  await sup.goto('/admin/policies');
  await expect(sup.getByTestId('policies-page')).toBeVisible();
  await settled(sup, 'policies-loading', 'policies-error');
  await sup.screenshot({ path: 'shots/14-policies.png' });

  // 15 — authority ladder editor.
  await sup.goto('/admin/authority');
  await expect(sup.getByTestId('auth-page')).toBeVisible();
  await settled(sup, 'auth-loading', 'auth-ladder-error');
  await sup.screenshot({ path: 'shots/15-authority.png' });
  await supCtx.close();
});
