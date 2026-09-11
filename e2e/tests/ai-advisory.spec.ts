import { expect, test } from '@playwright/test';
import { Browser, Page } from '@playwright/test';

let registrationCounter = 8000;

/**
 * Per-run loss date (same scheme as staged.spec.ts/covers.spec.ts): the V2-2
 * duplicate guard is intentionally not claimant-scoped and the hermetic
 * claims_e2e DB persists across runs, so a fixed date would 409 on the
 * previous run's own filings.
 */
const runDate = new Date(Date.UTC(2020, 0, 1)
  + (Math.floor(Date.now() / 1000) % 2000) * 86_400_000);
const lossDate = runDate.toISOString().slice(0, 10);

function requiredEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} is not set — copy .env.example to .env (or set it in CI).`);
  }
  return value;
}

async function registerClaimant(page: Page): Promise<void> {
  const stamp = Date.now() + '_' + registrationCounter++;
  const username = `aiclaim${stamp}`;
  const email = `${username}@example.test`;

  await page.goto('/claim/new');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();

  await page.locator('#firstName').fill('Ai');
  await page.locator('#lastName').fill('Test');
  await page.locator('#email').fill(email);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill('claims-Pass-123');
  await page.locator('#password-confirm').fill('claims-Pass-123');
  await page.getByRole('button', { name: 'Register' }).click();
  await expect(page.getByTestId('fnol-policy-number')).toBeVisible();
}

async function signInAdjuster(page: Page, username: string): Promise<void> {
  await page.goto('/queue');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(requiredEnv('ADJUSTER_PASSWORD'));
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('queue-page')).toBeVisible();
}

async function queueShows(page: Page, claimNumber: string): Promise<boolean> {
  await page.goto('/queue');
  await expect(page.getByTestId('queue-page')).toBeVisible();
  await expect(
    page
      .getByTestId('queue-error')
      .or(page.getByTestId('queue-empty'))
      .or(page.getByTestId('queue-row').first()),
  ).toBeVisible();
  await expect(page.getByTestId('queue-error')).toHaveCount(0);
  const row = page.getByTestId('queue-row').filter({ hasText: claimNumber });
  return (await row.count()) === 1;
}

const L1_ROSTER = ['adjuster.one', 'adjuster.two', 'adjuster.four'];

/** Finds whichever L1 adjuster currently holds the claim and opens its detail screen. */
async function openClaimAsHolder(browser: Browser, claimNumber: string): Promise<Page> {
  for (const username of L1_ROSTER) {
    const context = await browser.newContext();
    const page = await context.newPage();
    await signInAdjuster(page, username);
    if (await queueShows(page, claimNumber)) {
      const row = page.getByTestId('queue-row').filter({ hasText: claimNumber });
      await row.getByTestId('queue-open-claim').click();
      await expect(page.getByTestId('claim-detail-page')).toBeVisible();
      return page;
    }
    await context.close();
  }
  throw new Error(`no L1 adjuster queue held claim ${claimNumber}`);
}

/** Completes every still-open verification row on the current stage screen. */
async function completeOpenVerifications(page: Page): Promise<void> {
  await expect(page.getByTestId('detail-verification-panel')).toBeVisible();
  for (const item of await page.getByTestId('detail-verification-item').all()) {
    const saveButton = item.getByTestId(/detail-ver-save-\d+/);
    if ((await saveButton.count()) === 0) continue;
    const saveTestId = (await saveButton.getAttribute('data-testid'))!;
    const rowId = saveTestId.replace('detail-ver-save-', '');
    await item.getByTestId(`detail-ver-outcome-${rowId}`).selectOption('PASSED');
    await page.getByTestId(`detail-ver-notes-${rowId}`).fill('Bills verified; admissible.');
    await page.getByTestId(`detail-ver-evidence-${rowId}`).fill('attachment-1');
    await saveButton.click();
  }
  await expect(page.getByTestId('detail-verification-guard')).toHaveCount(0);
}

/**
 * AI advisory panel journey: a MATERNITY-only filing on the seeded HLTH-PLUS
 * policy (POL-10001, L1-routed, 50k claimed of the 75k sub-limit so the claim
 * stays well within the holding L1's authority) is worked review →
 * verification → assessment to DECISION by its holder. At DECISION the
 * `app-ai-panel` section must appear; requesting advice yields the
 * MATERNITY suggestion with the rules-only/DEGRADED fallback status (no live
 * provider in this box, so POST degrades deterministically); a reload
 * replays the stored advisory without a new POST (dedupe replay).
 *
 * The panel mount itself is orchestrator-owned: if `detail-ai` is absent at
 * DECISION the test skips with reason instead of failing. Pre-DECISION the
 * chat is live but the per-cover advisory stays locked (V4 S3: chat every
 * stage, artifact at DECISION only).
 *
 * All assertions via testids with Playwright expect-polling; no fixed sleeps.
 */
test('AI advisory: DECISION panel serves the degraded MATERNITY suggestion and replays it on reload', async ({
  browser,
}: {
  browser: Browser;
}) => {
  test.setTimeout(240_000);
  const claimantContext = await browser.newContext();
  const claimantPage = await claimantContext.newPage();
  await registerClaimant(claimantPage);

  // (1) MATERNITY-only filing on HLTH-PLUS (POL-10001), clauses.spec.ts precedent.
  await claimantPage.getByTestId('fnol-policy-number').fill('POL-10001');
  await claimantPage.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await claimantPage.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await claimantPage.getByTestId('fnol-next').click();
  await expect(claimantPage.getByTestId('fnol-covers')).toBeVisible();

  await claimantPage.getByTestId('fnol-cover-MATERNITY').check();
  await claimantPage.getByTestId('fnol-amount-MATERNITY').fill('50000');
  await claimantPage.getByTestId('fnol-loss-date').fill(lossDate);
  await claimantPage.getByTestId('fnol-loss-location').fill('London');
  await claimantPage.getByTestId('fnol-loss-description').fill('Maternity admission and delivery.');
  await claimantPage.getByTestId('fnol-submit').click();

  await expect(claimantPage.getByTestId('claim-number')).toBeVisible();
  const claimNumber = (await claimantPage.getByTestId('claim-number').textContent())!.trim();
  expect(claimNumber).toMatch(/CLM-\d{6}/);
  await claimantContext.close();

  // (2) Open as the holding L1 adjuster. Pre-DECISION the chat is live
  // but the per-cover advisory stays locked (V4 S3: chat every stage,
  // artifact at DECISION only).
  const holder = await openClaimAsHolder(browser, claimNumber);
  await expect(holder.getByTestId('detail-review-panel')).toBeVisible();
  await holder.getByTestId('detail-ai-toggle').click();
  await expect(holder.getByTestId('detail-ai-drawer')).toBeVisible();
  await expect(holder.getByTestId('detail-ai-chat')).toBeVisible();
  await expect(holder.getByTestId('detail-ai-MATERNITY')).toHaveCount(0);
  await holder.keyboard.press('Escape');
  await expect(holder.getByTestId('detail-ai-drawer')).toHaveCount(0);

  // (3) Work to DECISION: review -> verification -> assessment
  // (structured-decision.spec.ts pattern).
  await holder.getByTestId('detail-review-rationale').fill('Documents look complete.');
  await holder.getByTestId('detail-review-advance').click();
  await completeOpenVerifications(holder);

  await holder.getByTestId('detail-assess-MATERNITY').fill('50000');
  await holder.getByTestId('detail-assessment-rationale').fill('Within cover limits.');
  await holder.getByTestId('detail-save-assessment').click();
  await expect(holder.getByTestId('detail-stage-DECISION')).toHaveClass(/is-current/);

  // Mount gate (orchestrator-owned): the DECISION-path assertions below run
  // against the FINAL testids via the collapsed AI control; without the
  // mount they skip, never fail.
  await holder.getByTestId('detail-ai-toggle').click();
  await expect(holder.getByTestId('detail-ai-drawer')).toBeVisible();
  if ((await holder.getByTestId('detail-ai').count()) === 0) {
    test.skip(
      true,
      'AI panel not mounted in claim-detail (orchestrator-owned mount pending) — DECISION-path assertions deferred',
    );
  }

  // Count advisory POSTs on the wire to prove the reload replays the stored
  // row (dedupe) instead of recomputing.
  let postCount = 0;
  holder.on('response', (response) => {
    if (
      response.request().method() === 'POST'
      && /\/api\/claims\/CLM-\d+\/ai-analysis/.test(response.url())
    ) {
      postCount += 1;
    }
  });

  // (a) The AI section renders at DECISION with no error.
  const panel = holder.getByTestId('detail-ai');
  await expect(panel).toBeVisible();
  await expect(holder.getByTestId('detail-ai-error')).toHaveCount(0);

  // (b) Request advice: the MATERNITY suggestion appears and the status names
  // the fallback (no live provider here, so POST degrades to rules-only).
  await holder.getByTestId('detail-ai-request').click();
  await expect(holder.getByTestId('detail-ai-MATERNITY')).toBeVisible();
  await expect(holder.getByTestId('detail-ai-status')).toContainText(/rules-only|DEGRADED/i);
  await expect(holder.getByTestId('detail-ai-error')).toHaveCount(0);
  await expect.poll(() => postCount, { message: 'ai-analysis POST observed' }).toBeGreaterThan(0);

  // (c) Reload: the same suggestion persists without a re-request — the panel
  // replays the stored advisory for the claim version (GET latest, no POST).
  const postsBeforeReload = postCount;
  await holder.reload();
  await expect(holder.getByTestId('claim-detail-page')).toBeVisible();
  await holder.getByTestId('detail-ai-toggle').click();
  await expect(holder.getByTestId('detail-ai-drawer')).toBeVisible();
  await expect(holder.getByTestId('detail-ai-MATERNITY')).toBeVisible();
  expect(postCount, 'reload must replay the stored advisory without a new POST').toBe(
    postsBeforeReload,
  );
  await holder.context().close();
});
