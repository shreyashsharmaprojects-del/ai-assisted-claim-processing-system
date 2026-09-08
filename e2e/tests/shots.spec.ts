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
// Per-run loss date (past, unique per day-bucket): the duplicate-FNOL guard
// rejects same policy + date + cover set within 24h, and the dev DB persists.
const shotLossDate = new Date(Date.UTC(2020, 0, 1) + (Math.floor(stamp / 1000) % 2000) * 86400000)
  .toISOString()
  .slice(0, 10);

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
  await expect(claimant.getByTestId('fnol-covers')).toBeVisible();

  // 02 — FNOL step 2 with the multi-cover picker (HLTH-PLUS 5-cover set).
  // Check two covers with amounts: the filed claim lands with cover splits.
  await claimant.getByTestId('fnol-cover-HOSPITALIZATION').check();
  await claimant.getByTestId('fnol-amount-HOSPITALIZATION').fill('300000');
  await claimant.getByTestId('fnol-cover-DAYCARE').check();
  await claimant.getByTestId('fnol-amount-DAYCARE').fill('40000');
  await claimant.getByTestId('fnol-loss-date').fill(shotLossDate);
  await claimant.getByTestId('fnol-loss-location').fill('London');
  await claimant.getByTestId('fnol-loss-description').fill('Gallbladder surgery with two daycare follow-ups.');
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

  // ================= Adjuster staged flow (V2 showcase rows) =================
  // The demo seed pins one multi-cover claim per stage with a fixed assignee, so
  // each screenshot below is deterministic (no live transitions — the seed does
  // the acting, the shots show the state).

  // 11 — REVIEW triage as L1 Priya: stepper on Review, per-cover claimed amounts
  // with sub-limit flags, advance/reject/send-back actions.
  const revCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const rev = await revCtx.newPage();
  await signIn(rev, 'adjuster.one', requiredEnv('ADJUSTER_PASSWORD'), 'queue-page');
  await rev.goto('/queue');
  await expect(rev.getByTestId('queue-page')).toBeVisible();
  const reviewRow = rev.getByTestId('queue-row').filter({ hasText: 'Gallbladder surgery' });
  await expect(reviewRow).toHaveCount(1);
  await reviewRow.getByTestId('queue-open-claim').click();
  await expect(rev.getByTestId('claim-detail-page')).toBeVisible();
  await expect(rev.getByTestId('detail-review-panel')).toBeVisible();
  await rev.screenshot({ path: 'shots/11-review-triage.png' });
  await revCtx.close();

  // 12 — VERIFICATION as L1 Aisha: stepper advanced, verification history with
  // the completed DIGITAL record, assessment gate.
  const verCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const ver = await verCtx.newPage();
  await signIn(ver, 'adjuster.four', requiredEnv('ADJUSTER_PASSWORD'), 'queue-page');
  await ver.goto('/queue');
  await expect(ver.getByTestId('queue-page')).toBeVisible();
  const verRow = ver.getByTestId('queue-row').filter({ hasText: 'Knee arthroscopy' });
  await expect(verRow).toHaveCount(1);
  await verRow.getByTestId('queue-open-claim').click();
  await expect(ver.getByTestId('claim-detail-page')).toBeVisible();
  await expect(ver.getByTestId('detail-verification-panel')).toBeVisible();
  await ver.screenshot({ path: 'shots/12-verification.png' });
  await verCtx.close();

  // 13 — DECISION gate as L3 Meera: saved above-authority proposals with the
  // authority hint and the explicit refer-upwards box (nothing auto-moves).
  const gateCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const gate = await gateCtx.newPage();
  await signIn(gate, 'adjuster.six', requiredEnv('ADJUSTER_PASSWORD'), 'queue-page');
  await gate.goto('/queue');
  await expect(gate.getByTestId('queue-page')).toBeVisible();
  const gateRow = gate.getByTestId('queue-row').filter({ hasText: 'Liver transplant' });
  await expect(gateRow).toHaveCount(1);
  await gateRow.getByTestId('queue-open-claim').click();
  await expect(gate.getByTestId('claim-detail-page')).toBeVisible();
  await expect(gate.getByTestId('detail-gate-banner')).toBeVisible();
  await gate.getByTestId('detail-gate-banner').scrollIntoViewIfNeeded();
  await gate.waitForTimeout(400);
  await gate.screenshot({ path: 'shots/13-authority-gate.png' });
  await gateCtx.close();

  // 14 — PARTIALLY_APPROVED closure: per-cover outcomes (one approved, one
  // rejected) with the single net-payable payment. Closed claims leave the work
  // queue, so resolve the seeded number from the supervisor outbox row (each
  // outbox row carries its claim number; the demo marker address is unique),
  // then open it as the deciding L2 — the wall permits the decider on closure.
  const supNumCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const supNum = await supNumCtx.newPage();
  await supNum.goto('/overview');
  await expect(supNum).toHaveURL(/realms\/claims/);
  await supNum.locator('#username').fill('supervisor');
  await supNum.locator('#password').fill(requiredEnv('SUPERVISOR_PASSWORD'));
  await supNum.locator('#kc-login').click();
  await expect(supNum.getByTestId('overview-page')).toBeVisible({ timeout: 30000 });
  await settled(supNum, 'overview-loading', 'overview-error');
  const closedRow = supNum.getByTestId('outbox-row').filter({ hasText: 'demo.kabir@example.test' });
  await expect(closedRow).toHaveCount(1);
  const partNumber = ((await closedRow.getByTestId('outbox-claim').textContent()) ?? '').trim();
  await supNumCtx.close();

  const partCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const part = await partCtx.newPage();
  await signIn(part, 'adjuster.five', requiredEnv('ADJUSTER_PASSWORD'), 'queue-page');
  await part.goto(`/claims/${partNumber}`);
  await expect(part.getByTestId('claim-detail-page')).toBeVisible();
  await expect(part.getByTestId('detail-decision-closed')).toBeVisible();
  await part.getByTestId('detail-decision-closed').scrollIntoViewIfNeeded();
  await part.waitForTimeout(400);
  await part.screenshot({ path: 'shots/14-partial-approval.png' });
  await partCtx.close();

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
  await sup.screenshot({ path: 'shots/15-overview.png' });

  // 16 — outbox panel state (scroll into view; part of the overview page).
  const outbox = sup.getByTestId('outbox-panel');
  if ((await outbox.count()) === 1) {
    await outbox.scrollIntoViewIfNeeded();
    await sup.waitForTimeout(300);
    await sup.screenshot({ path: 'shots/16-outbox.png' });
  }

  // 17 — escalations queue.
  await sup.goto('/escalations');
  await expect(sup.getByTestId('escalations-page')).toBeVisible();
  await settled(sup, 'esc-loading', 'esc-error');
  await sup.screenshot({ path: 'shots/17-escalations.png' });

  // 20 — policy book.
  await sup.goto('/admin/policies');
  await expect(sup.getByTestId('policies-page')).toBeVisible();
  await settled(sup, 'policies-loading', 'policies-error');
  await sup.screenshot({ path: 'shots/20-policies.png' });

  // 21 — authority ladder editor.
  await sup.goto('/admin/authority');
  await expect(sup.getByTestId('auth-page')).toBeVisible();
  await settled(sup, 'auth-loading', 'auth-ladder-error');
  await sup.screenshot({ path: 'shots/21-authority.png' });
  await supCtx.close();

  // ================= Claimant cockpit (linked holder) =================
  // 18/19 — Ada owns POL-10001 by holder email, so registering with exactly
  // that address links her cockpit: cover list with limits/claimed/remaining,
  // then the HLTH-PLUS policy detail.
  const adaCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const ada = await adaCtx.newPage();
  await ada.goto('/claim/new');
  await expect(ada).toHaveURL(/realms\/claims/);
  await ada.getByRole('link', { name: 'Register' }).click();
  await expect(ada.locator('#firstName')).toBeVisible();
  await ada.locator('#firstName').fill('Ada');
  await ada.locator('#lastName').fill('Cockpit');
  await ada.locator('#email').fill('ada.lovelace@example.test');
  await ada.locator('#username').fill(`shotada${Date.now()}`);
  await ada.locator('#password').fill('claims-Pass-123');
  await ada.locator('#password-confirm').fill('claims-Pass-123');
  await ada.getByRole('button', { name: 'Register' }).click();
  await expect(ada.getByTestId('fnol-policy-number')).toBeVisible();
  await ada.goto('/policies');
  await expect(ada.getByTestId('cockpit-page')).toBeVisible();
  await settled(ada, 'cockpit-loading', 'cockpit-error');
  await ada.screenshot({ path: 'shots/18-cockpit.png' });
  const adaPolicy = ada.getByTestId('cockpit-open').first();
  if ((await adaPolicy.count()) === 1) {
    await adaPolicy.click();
    await expect(ada.getByTestId('policy-detail-page')).toBeVisible();
    await ada.waitForTimeout(500);
    await ada.screenshot({ path: 'shots/19-cockpit-policy.png' });
  }
  await adaCtx.close();
});
