import { expect, test } from '@playwright/test';
import { Page } from '@playwright/test';

const PHOTO = Buffer.from([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
]);

let registrationCounter = 0;

/** Registers a fresh claimant through the real Keycloak realm and returns to the FNOL form. */
async function registerClaimant(page: Page): Promise<void> {
  const stamp = Date.now() + '_' + registrationCounter++;
  const username = `claimant${stamp}`;
  const email = `${username}@example.test`;

  // File a claim is protected -> redirects to Keycloak.
  await page.goto('/claim/new');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();

  await page.locator('#firstName').fill('Ada');
  await page.locator('#lastName').fill('Test');
  await page.locator('#email').fill(email);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill('claims-Pass-123');
  await page.locator('#password-confirm').fill('claims-Pass-123');
  await page.getByRole('button', { name: 'Register' }).click();

  // Back on the app, on the FNOL form.
  await expect(page.getByTestId('fnol-policy-number')).toBeVisible();
}

/**
 * Journey 1: a claimant signs up through Keycloak, files an FNOL with a photo against the
 * seeded policy POL-10001, and immediately sees their claim number. Runs against the
 * dedicated claims_e2e database with the real compose Keycloak realm.
 */
test('claimant registers, files an FNOL with a photo and sees the claim number', async ({ page }) => {
  await registerClaimant(page);

  await page.getByTestId('fnol-policy-number').fill('POL-10001');
  await page.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await page.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await page.getByTestId('fnol-loss-date').fill('2026-09-01');
  await page.getByTestId('fnol-loss-location').fill('London');
  await page.getByTestId('fnol-loss-description').fill('Kitchen flooded after a pipe burst.');
  await page.getByTestId('fnol-remarks').fill('Happened overnight.');
  await page.getByTestId('fnol-photos').setInputFiles({
    name: 'kitchen.png',
    mimeType: 'image/png',
    buffer: PHOTO,
  });

  await page.getByTestId('fnol-submit').click();

  // The claim number is returned immediately, with a plain statement of what happens next.
  await expect(page.getByTestId('claim-number')).toBeVisible();
  await expect(page.getByTestId('claim-number')).toHaveText(/CLM-\d{6}/);
  await expect(page.getByTestId('fnol-error')).toHaveCount(0);
  await expect(page.getByTestId('claim-steps')).toContainText('FNOL received');
});

/**
 * Criterion-4 UI half: a rejected FNOL stays on the form with the server's error visible —
 * nothing navigates away and no false claim number is shown.
 */
test('a rejected FNOL keeps the form on screen with the server error', async ({ page }) => {
  await registerClaimant(page);

  // Valid form input, but holder details that do not match the seeded policy -> server 404.
  await page.getByTestId('fnol-policy-number').fill('POL-10001');
  await page.getByTestId('fnol-holder-name').fill('Someone Else');
  await page.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await page.getByTestId('fnol-loss-date').fill('2026-09-01');
  await page.getByTestId('fnol-loss-location').fill('London');
  await page.getByTestId('fnol-loss-description').fill('Storm damage');

  await page.getByTestId('fnol-submit').click();

  await expect(page.getByTestId('fnol-error')).toBeVisible();
  await expect(page.getByTestId('fnol-error')).toContainText('could not match');
  // The form is still there to be corrected, and no claim number was fabricated.
  await expect(page.getByTestId('fnol-policy-number')).toBeVisible();
  await expect(page.getByTestId('claim-number')).toHaveCount(0);
});
