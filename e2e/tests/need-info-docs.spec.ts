import { expect, test } from '@playwright/test';
import { Browser, BrowserContext, Page } from '@playwright/test';

let registrationCounter = 7000;

/**
 * Per-run loss date (same scheme as staged.spec.ts/covers.spec.ts): the V2-2
 * duplicate guard is intentionally not claimant-scoped and the hermetic
 * claims_e2e DB persists across runs, so a fixed date would 409 on the
 * previous run's own filings.
 */
const runDate = new Date(Date.UTC(2020, 0, 1)
  + (Math.floor(Date.now() / 1000) % 2000) * 86_400_000);
const lossDate = runDate.toISOString().slice(0, 10);

const PHOTO = Buffer.from([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
]);

function requiredEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} is not set — copy .env.example to .env (or set it in CI).`);
  }
  return value;
}

/** Registers a fresh claimant (per-run unique username) and lands on the FNOL form. */
async function registerClaimant(page: Page): Promise<void> {
  const stamp = Date.now() + '_' + registrationCounter++;
  const username = `needinfo${stamp}`;
  const email = `${username}@example.test`;

  await page.goto('/claim/new');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();

  await page.locator('#firstName').fill('Needinfo');
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
 * True when the signed-in adjuster's queue shows exactly one row for the claim.
 * Waits for the queue fetch to settle first (rows render only once loaded()).
 */
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

/** Opens the claim as whichever adjuster currently holds it (NEED_INFO responses reassign). */
async function openClaimAsHolder(
  browser: Browser,
  claimNumber: string,
): Promise<{ ctx: BrowserContext; page: Page }> {
  for (const username of [
    'adjuster.one',
    'adjuster.two',
    'adjuster.four',
    'adjuster.three',
    'adjuster.five',
  ]) {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await signInAdjuster(page, username);
    if (await queueShows(page, claimNumber)) {
      const row = page.getByTestId('queue-row').filter({ hasText: claimNumber });
      await row.getByTestId('queue-open-claim').click();
      await expect(page.getByTestId('claim-detail-page')).toBeVisible();
      return { ctx, page };
    }
    await ctx.close();
  }
  throw new Error(`no adjuster queue held claim ${claimNumber}`);
}

/**
 * S3 NEED_INFO journey: the adjuster sends the claim back, the claimant uploads
 * the requested document, responds (claim returns UNDER_REVIEW), the holder
 * links the uploaded file to a checklist row via detail-reqdoc-{key}/link, and
 * the claimant tracker claim-reqdocs-count increments 0 of 3 -> 1 of 3.
 */
test('NEED_INFO flow links a document and the claimant tracker count increments', async ({
  browser,
}: {
  browser: Browser;
}) => {
  test.setTimeout(240_000);
  const claimantContext = await browser.newContext();
  const claimantPage = await claimantContext.newPage();
  await registerClaimant(claimantPage);

  // Staged filing on POL-10001 (HLTH-PLUS: 3 required docs) so the adjuster
  // works the REVIEW panel and the tracker carries a 3-item checklist.
  await claimantPage.getByTestId('fnol-policy-number').fill('POL-10001');
  await claimantPage.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await claimantPage.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await claimantPage.getByTestId('fnol-next').click();
  await expect(claimantPage.getByTestId('fnol-covers')).toBeVisible();
  await claimantPage.getByTestId('fnol-cover-HOSPITALIZATION').check();
  await claimantPage.getByTestId('fnol-amount-HOSPITALIZATION').fill('200000');
  await claimantPage.getByTestId('fnol-loss-date').fill(lossDate);
  await claimantPage.getByTestId('fnol-loss-location').fill('London');
  await claimantPage.getByTestId('fnol-loss-description').fill('Hospital stay; bill to follow.');
  await claimantPage.getByTestId('fnol-submit').click();

  await expect(claimantPage.getByTestId('claim-number')).toBeVisible();
  const claimNumber = ((await claimantPage.getByTestId('claim-number').textContent()) ?? '').trim();
  expect(claimNumber).toMatch(/CLM-\d{6}/);

  // The holder sees the S3 checklist rows (all PENDING) and sends the claim back.
  const first = await openClaimAsHolder(browser, claimNumber);
  const holder = first.page;
  await expect(holder.getByTestId('detail-review-panel')).toBeVisible();
  const checklistRow = holder.getByTestId(/detail-reqdoc-/).first();
  await expect(checklistRow).toBeVisible();
  const rowTestId = (await checklistRow.getAttribute('data-testid'))!;
  const docKey = rowTestId.replace('detail-reqdoc-', '');

  await holder.getByTestId('detail-review-need-info').click();
  await holder.getByTestId('detail-review-requested-items')
    .fill('Please attach the final hospital bill.');
  await holder.getByTestId('detail-review-need-info-confirm').click();
  await expect(holder.getByTestId('detail-need-info-banner')).toBeVisible();
  await first.ctx.close();

  // Claimant tracker: NEED_INFO panel + "Documents: 0 of 3 received".
  await claimantPage.goto(`/claim/${claimNumber}`);
  await expect(claimantPage.getByTestId('claim-status-page')).toBeVisible();
  await expect(claimantPage.getByTestId('claim-need-info-panel')).toBeVisible();
  await expect(claimantPage.getByTestId('claim-reqdocs-count'))
    .toHaveText('Documents: 0 of 3 received');

  // Claimant uploads the requested doc (label-only form: no docKey, so no
  // auto-link — the adjuster link below carries the count). The upload button
  // re-enables when the upload round-trip finishes.
  await claimantPage.getByTestId('claim-need-info-doc-file').setInputFiles({
    name: 'final-bill.png',
    mimeType: 'image/png',
    buffer: PHOTO,
  });
  const uploadButton = claimantPage.getByTestId('claim-need-info-doc-upload');
  await uploadButton.click();
  await expect(uploadButton).toBeEnabled();

  // Respond: the claim returns to the adjuster, still 0 of 3 until linked.
  await claimantPage.getByTestId('claim-need-info-message')
    .fill('Attached the final bill as requested.');
  await claimantPage.getByTestId('claim-need-info-send').click();
  await expect(claimantPage.getByTestId('claim-need-info-panel')).toHaveCount(0);
  await expect(claimantPage.getByTestId('claim-reqdocs-count'))
    .toHaveText('Documents: 0 of 3 received');

  // The (possibly reassigned) holder links the uploaded file to the checklist row.
  const second = await openClaimAsHolder(browser, claimNumber);
  const linker = second.page;
  const row = linker.getByTestId(`detail-reqdoc-${docKey}`);
  await expect(row).toBeVisible();
  await row.locator('select').selectOption({ index: 1 });
  await linker.getByTestId(`detail-reqdoc-${docKey}/link`).click();
  await expect(row).toContainText('Received');
  await second.ctx.close();

  // Claimant tracker count increments: "Documents: 1 of 3 received".
  await claimantPage.goto(`/claim/${claimNumber}`);
  await expect(claimantPage.getByTestId('claim-status-page')).toBeVisible();
  await expect(claimantPage.getByTestId('claim-reqdocs-count'))
    .toHaveText('Documents: 1 of 3 received');
  await expect(
    claimantPage.getByTestId('claim-reqdocs-item').filter({ hasText: 'Received' }),
  ).toHaveCount(1);
  await claimantContext.close();
});
