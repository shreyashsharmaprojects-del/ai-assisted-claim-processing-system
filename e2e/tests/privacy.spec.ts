import { expect, test } from '@playwright/test';
import type { Browser, Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';

let registrationCounter = 5000;

/**
 * Per-run loss date (same scheme as staged.spec.ts/covers.spec.ts): the V2-2
 * duplicate guard is intentionally not claimant-scoped and the hermetic
 * claims_e2e DB persists across runs, so a fixed date would 409 on the
 * previous run's own filings. This spec files coverless (selections null, so
 * the guard is skipped) but keeps the scheme for hermeticity anyway.
 */
const runDate = new Date(Date.UTC(2020, 0, 1)
  + (Math.floor(Date.now() / 1000) % 2000) * 86_400_000);
const lossDate = runDate.toISOString().slice(0, 10);

async function registerClaimant(page: Page): Promise<void> {
  const stamp = Date.now() + '_' + registrationCounter++;
  const username = `priv${stamp}`;
  const email = `${username}@example.test`;

  await page.goto('/claim/new');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();

  await page.locator('#firstName').fill('Privacy');
  await page.locator('#lastName').fill('Test');
  await page.locator('#email').fill(email);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill('claims-Pass-123');
  await page.locator('#password-confirm').fill('claims-Pass-123');
  await page.getByRole('button', { name: 'Register' }).click();
  await expect(page.getByTestId('fnol-policy-number')).toBeVisible();
}

/** Minimal staged FNOL: step 1 against seeded POL-10001, coverless step 2. */
async function fileMinimalClaim(page: Page): Promise<string> {
  await page.getByTestId('fnol-policy-number').fill('POL-10001');
  await page.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await page.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await page.getByTestId('fnol-next').click();
  await expect(page.getByTestId('fnol-loss-date')).toBeVisible();

  await page.getByTestId('fnol-loss-date').fill(lossDate);
  await page.getByTestId('fnol-loss-location').fill('London');
  await page.getByTestId('fnol-loss-description').fill('Export privacy probe claim.');
  await page.getByTestId('fnol-submit').click();

  await expect(page.getByTestId('claim-number')).toBeVisible();
  const claimNumber = ((await page.getByTestId('claim-number').textContent()) ?? '').trim();
  expect(claimNumber).toMatch(/CLM-\d{6}/);
  return claimNumber;
}

/**
 * S9 E2E: a claimant's "Download my data" export is valid JSON containing
 * their own claims and no other claimant's data.
 */
test('my-data export downloads valid JSON with only the caller\u2019s claims', async ({
  browser,
}: {
  browser: Browser;
}) => {
  test.setTimeout(180_000);

  // Second fixture claim from ANOTHER claimant: must be absent from the export.
  const contextA = await browser.newContext();
  const pageA = await contextA.newPage();
  await registerClaimant(pageA);
  const otherClaim = await fileMinimalClaim(pageA);
  await contextA.close();

  // Fresh claimant files two claims, then exports from My-claims.
  const contextB = await browser.newContext();
  const pageB = await contextB.newPage();
  await registerClaimant(pageB);
  const ownFirst = await fileMinimalClaim(pageB);
  await pageB.goto('/claim/new');
  await expect(pageB.getByTestId('fnol-policy-number')).toBeVisible();
  const ownSecond = await fileMinimalClaim(pageB);

  await pageB.goto('/claims');
  await expect(pageB.getByTestId('my-claims-page')).toBeVisible();
  await expect(
    pageB.getByTestId('my-claims-row').filter({ hasText: ownFirst }),
  ).toHaveCount(1);
  await expect(
    pageB.getByTestId('my-claims-row').filter({ hasText: ownSecond }),
  ).toHaveCount(1);

  const downloadPromise = pageB.waitForEvent('download');
  await pageB.getByTestId('myclaims-export').click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/\.json$/);

  const filePath = await download.path();
  expect(filePath).toBeTruthy();
  const raw = await readFile(filePath!, 'utf8');
  const payload = JSON.parse(raw) as { claims: Array<{ claimNumber: string }> };

  // Valid JSON with exactly the caller's own claims present…
  expect(Array.isArray(payload.claims)).toBe(true);
  expect(payload.claims).toHaveLength(2);
  const numbers = payload.claims.map((c) => c.claimNumber);
  expect(numbers).toContain(ownFirst);
  expect(numbers).toContain(ownSecond);
  // …and no trace of the other claimant's claim anywhere in the download.
  expect(raw).not.toContain(otherClaim);

  await contextB.close();
});
