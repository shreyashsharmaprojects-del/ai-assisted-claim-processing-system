import { expect, test } from '@playwright/test';
import { Browser, Page } from '@playwright/test';

let registrationCounter = 5000;

/**
 * Per-run loss date (same scheme as staged.spec.ts): the V2-2 duplicate guard is
 * intentionally not claimant-scoped and the hermetic claims_e2e DB persists across
 * runs, so a fixed date would 409 on the previous run's own filings.
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
  const username = `conflict${stamp}`;
  const email = `${username}@example.test`;

  await page.goto('/claim/new');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();

  await page.locator('#firstName').fill('Conflict');
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

/**
 * S5 (V21) E2E: the holder adjuster opens the same claim in two browser contexts.
 * Context A loads the detail view (version v); context B saves a reserve first
 * (bumping to v+1); then context A saves a reserve with its stale version → the
 * backend answers 409 CONFLICT → the detail-conflict-banner appears and the view
 * refetches to the latest (B's) reserve.
 */
test('stale reserve save in a second context shows the conflict banner', async ({
  browser,
}: {
  browser: Browser;
}) => {
  test.setTimeout(180_000);

  // File a claim on POL-10001 (L1 HOME — the journey-4 policy, reserve form
  // available to the holder immediately after filing).
  const claimantContext = await browser.newContext();
  const claimantPage = await claimantContext.newPage();
  await registerClaimant(claimantPage);
  await claimantPage.getByTestId('fnol-policy-number').fill('POL-10001');
  await claimantPage.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await claimantPage.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await claimantPage.getByTestId('fnol-next').click();
  await expect(claimantPage.getByTestId('fnol-loss-date')).toBeVisible();
  await claimantPage.getByTestId('fnol-loss-date').fill(lossDate);
  await claimantPage.getByTestId('fnol-loss-location').fill('London');
  await claimantPage.getByTestId('fnol-loss-description').fill('Conflict probe: burst pipe.');
  await claimantPage.getByTestId('fnol-submit').click();
  await expect(claimantPage.getByTestId('claim-number')).toBeVisible();
  const claimNumber = (await claimantPage.getByTestId('claim-number').textContent())!.trim();
  expect(claimNumber).toMatch(/CLM-\d{6}/);
  await claimantContext.close();

  // Find the holding L1 adjuster; keep their signed-in context as context A.
  let holderUsername: string | null = null;
  let contextA = null;
  let pageA: Page | null = null;
  for (const username of ['adjuster.one', 'adjuster.two', 'adjuster.four']) {
    const context = await browser.newContext();
    const page = await context.newPage();
    await signInAdjuster(page, username);
    await page.goto('/queue');
    await expect(page.getByTestId('queue-page')).toBeVisible();
    await expect(
      page
        .getByTestId('queue-error')
        .or(page.getByTestId('queue-empty'))
        .or(page.getByTestId('queue-row').first()),
    ).toBeVisible();
    const row = page.getByTestId('queue-row').filter({ hasText: claimNumber });
    if ((await row.count()) === 1) {
      holderUsername = username;
      contextA = context;
      pageA = page;
      break;
    }
    await context.close();
  }
  expect(holderUsername, 'an L1 adjuster holds the filed claim').not.toBeNull();

  // Context B: the same holder in a second, independent browser context.
  const contextB = await browser.newContext();
  const pageB = await contextB.newPage();
  await signInAdjuster(pageB, holderUsername!);

  // Both contexts open the same claim detail (loaded version v on both).
  await pageA!.goto(`/claims/${claimNumber}`);
  await expect(pageA!.getByTestId('claim-detail-page')).toBeVisible();
  await expect(pageA!.getByTestId('detail-reserve-input')).toBeVisible();
  await pageB.goto(`/claims/${claimNumber}`);
  await expect(pageB.getByTestId('claim-detail-page')).toBeVisible();
  await expect(pageB.getByTestId('detail-reserve-input')).toBeVisible();

  // Context B saves first: version v → v+1.
  await pageB.getByTestId('detail-reserve-input').fill('2000');
  await pageB.getByTestId('detail-reserve-save').click();
  await expect(pageB.getByTestId('detail-reserve-value')).toHaveText('₹2,000.00');

  // Context A saves with its stale version → 409 CONFLICT → banner + refetch.
  const conflictResponse = pageA!.waitForResponse(
    (response) => response.url().includes('/reserve') && response.status() === 409,
  );
  await pageA!.getByTestId('detail-reserve-input').fill('3000');
  await pageA!.getByTestId('detail-reserve-save').click();
  await conflictResponse;
  await expect(pageA!.getByTestId('detail-conflict-banner')).toBeVisible();
  await expect(pageA!.getByTestId('detail-conflict-banner')).toContainText(
    'reloaded the latest',
  );
  // The refetch shows the latest (B's) reserve, not A's stale write.
  await expect(pageA!.getByTestId('detail-reserve-value')).toHaveText('₹2,000.00');

  await contextB.close();
  await contextA!.close();
});
