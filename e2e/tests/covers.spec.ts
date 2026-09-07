import { expect, test } from '@playwright/test';
import { Page } from '@playwright/test';

let registrationCounter = 5000;

/**
 * Per-run loss date, unique to the second: base 2020-01-01 plus
 * (epochSeconds mod 2000) days — always past, always within the 10-year window,
 * and two suite runs can only collide if started in the same second (impossible:
 * a run takes a minute). Needed because the V2-2 duplicate guard (same policy +
 * loss date + cover set < 24h) is intentionally NOT claimant-scoped, and the
 * hermetic claims_e2e DB persists across runs — a fixed date trips on the previous
 * run's own filings. All three tests share one date (their cover sets differ);
 * the duplicate test reuses it on purpose.
 */
const runDate = new Date(Date.UTC(2020, 0, 1)
  + (Math.floor(Date.now() / 1000) % 2000) * 86_400_000);
const lossDate = runDate.toISOString().slice(0, 10);

/** Registers a fresh claimant through the real Keycloak realm and returns to the FNOL form. */
async function registerClaimant(page: Page): Promise<void> {
  const stamp = Date.now() + '_' + registrationCounter++;
  const username = `coverclaim${stamp}`;
  const email = `${username}@example.test`;

  await page.goto('/claim/new');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();

  await page.locator('#firstName').fill('Cover');
  await page.locator('#lastName').fill('Test');
  await page.locator('#email').fill(email);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill('claims-Pass-123');
  await page.locator('#password-confirm').fill('claims-Pass-123');
  await page.getByRole('button', { name: 'Register' }).click();
  await expect(page.getByTestId('fnol-policy-number')).toBeVisible();
}

/** Step 1 against the seeded HLTH-PLUS policy (5 covers), lands on step 2. */
async function step1Plus(page: Page): Promise<void> {
  await page.getByTestId('fnol-policy-number').fill('POL-10001');
  await page.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await page.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await page.getByTestId('fnol-next').click();
  await expect(page.getByTestId('fnol-loss-date')).toBeVisible();
}

/** Journey 1: multi-cover filing shows the picker, files, and confirms with covers + total. */
test('multi-cover FNOL: pick two covers with amounts, see them on confirmation', async ({
  page,
}) => {
  await registerClaimant(page);
  await step1Plus(page);

  // The picker loads the 5 HLTH-PLUS covers after step 1.
  await expect(page.getByTestId('fnol-covers')).toBeVisible();
  await page.getByTestId('fnol-cover-HOSPITALIZATION').check();
  await page.getByTestId('fnol-amount-HOSPITALIZATION').fill('200000');
  await page.getByTestId('fnol-cover-OPD').check();
  await page.getByTestId('fnol-amount-OPD').fill('40000');

  await page.getByTestId('fnol-loss-date').fill(lossDate);
  await page.getByTestId('fnol-loss-location').fill('London');
  await page.getByTestId('fnol-loss-description').fill('Hospital stay plus follow-up visits.');
  await page.getByTestId('fnol-submit').click();

  await expect(page.getByTestId('claim-number')).toBeVisible();
  await expect(page.getByTestId('claim-number')).toHaveText(/CLM-\d{6}/);
  // Above-limit OPD (40k vs 30k sub-limit) files fine but is flagged.
  await expect(page.getByTestId('claim-covers')).toContainText('HOSPITALIZATION');
  await expect(page.getByTestId('claim-covers')).toContainText('OPD');
  await expect(page.getByTestId('claim-total')).toContainText('240000');
});

/** Journey 2: the tracker shows per-cover outcomes and no internal fields, on screen or wire. */
test('tracker shows filed covers with no internal money fields', async ({ page }) => {
  const leaked: string[] = [];
  page.on('response', async (response) => {
    if (response.request().method() === 'GET' && /\/api\/claims\/CLM-\d+$/.test(response.url())) {
      const body = await response.text();
      for (const field of [
        'assessedAmount',
        'approvedAmount',
        'netPayable',
        'reserveAmount',
        'assignedTo',
      ]) {
        if (body.includes(`"${field}"`)) {
          leaked.push(field);
        }
      }
    }
  });

  await registerClaimant(page);
  await step1Plus(page);

  await expect(page.getByTestId('fnol-covers')).toBeVisible();
  await page.getByTestId('fnol-cover-HOSPITALIZATION').check();
  await page.getByTestId('fnol-amount-HOSPITALIZATION').fill('200000');
  await page.getByTestId('fnol-loss-date').fill(lossDate);
  await page.getByTestId('fnol-loss-location').fill('London');
  await page.getByTestId('fnol-loss-description').fill('Hospital stay.');
  await page.getByTestId('fnol-submit').click();

  await expect(page.getByTestId('claim-number')).toBeVisible();
  await page.getByTestId('track-claim').click();

  await expect(page.getByTestId('claim-status-page')).toBeVisible();
  await expect(page.getByTestId('status-covers')).toContainText('HOSPITALIZATION');
  expect(leaked, `internal fields leaked on the claimant wire: ${leaked.join(', ')}`).toEqual([]);
});

/** Duplicate: refiling the same covers within 24h returns the existing number. */
test('duplicate multi-cover filing returns the existing claim number', async ({ page }) => {
  await registerClaimant(page);
  await step1Plus(page);

  await expect(page.getByTestId('fnol-covers')).toBeVisible();
  await page.getByTestId('fnol-cover-OPD').check();
  await page.getByTestId('fnol-amount-OPD').fill('5000');
  await page.getByTestId('fnol-loss-date').fill(lossDate);
  await page.getByTestId('fnol-loss-location').fill('London');
  await page.getByTestId('fnol-loss-description').fill('Follow-up visits.');
  await page.getByTestId('fnol-submit').click();

  await expect(page.getByTestId('claim-number')).toBeVisible();
  const first = ((await page.getByTestId('claim-number').textContent()) ?? '').trim();
  expect(first).toMatch(/CLM-\d{6}/);

  // File the identical cover set again: the server answers 409 with the same number.
  await page.goto('/claim/new');
  await expect(page.getByTestId('fnol-policy-number')).toBeVisible();
  await step1Plus(page);
  await expect(page.getByTestId('fnol-covers')).toBeVisible();
  await page.getByTestId('fnol-cover-OPD').check();
  await page.getByTestId('fnol-amount-OPD').fill('9000');
  await page.getByTestId('fnol-loss-date').fill(lossDate);
  await page.getByTestId('fnol-loss-location').fill('London');
  await page.getByTestId('fnol-loss-description').fill('Follow-up visits again.');
  await page.getByTestId('fnol-submit').click();

  await expect(page.getByTestId('fnol-error')).toBeVisible();
  await expect(page.getByTestId('fnol-error')).toContainText(first);
  await expect(page.getByTestId('claim-number')).toHaveCount(0);
});
