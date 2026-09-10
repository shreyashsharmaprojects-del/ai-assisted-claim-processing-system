import { expect, test } from '@playwright/test';
import { Page } from '@playwright/test';

let registrationCounter = 5000;

/**
 * Per-run loss date (same scheme as staged.spec.ts): the V2-2 duplicate guard is
 * intentionally not claimant-scoped and the hermetic claims_e2e DB persists across
 * runs, so a fixed date would 409 on the previous run's own filings.
 */
const runDate = new Date(Date.UTC(2020, 0, 1)
  + (Math.floor(Date.now() / 1000) % 2000) * 86_400_000);
const lossDate = runDate.toISOString().slice(0, 10);

/** Registers a fresh claimant through the real Keycloak realm and lands on the FNOL form. */
async function registerClaimant(page: Page): Promise<void> {
  const stamp = Date.now() + '_' + registrationCounter++;
  const username = `notif${stamp}`;
  const email = `${username}@example.test`;

  await page.goto('/claim/new');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();

  await page.locator('#firstName').fill('Notif');
  await page.locator('#lastName').fill('Test');
  await page.locator('#email').fill(email);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill('claims-Pass-123');
  await page.locator('#password-confirm').fill('claims-Pass-123');
  await page.getByRole('button', { name: 'Register' }).click();
  await expect(page.getByTestId('fnol-policy-number')).toBeVisible();
}

/**
 * S10 E2E: file a minimal FNOL → the header bell badge shows unread
 * (FNOL_RECEIVED + ASSIGNED may make it 2, so assert visible + [1-9]) →
 * open the notifications center → mark every unread item read → the badge
 * clears (nav-notifications-count disappears, polled via expect).
 */
test('filing raises a bell badge that clears after opening and marking read', async ({
  page,
}) => {
  test.setTimeout(180_000);
  await registerClaimant(page);

  // Minimal FNOL on the seeded policy POL-10001 (no attachments needed).
  await page.getByTestId('fnol-policy-number').fill('POL-10001');
  await page.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await page.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await page.getByTestId('fnol-next').click();
  await expect(page.getByTestId('fnol-loss-date')).toBeVisible();
  await page.getByTestId('fnol-loss-date').fill(lossDate);
  await page.getByTestId('fnol-loss-location').fill('London');
  await page.getByTestId('fnol-loss-description').fill('Pipe burst, kitchen flooded.');
  await page.getByTestId('fnol-submit').click();
  await expect(page.getByTestId('claim-number')).toBeVisible();

  // The bell refreshes on NavigationEnd, so navigate to trigger the refresh,
  // then poll for the badge (expect retries until the unread fetch lands).
  await page.goto('/claims');
  await expect(page.getByTestId('my-claims-page')).toBeVisible();
  const badge = page.getByTestId('nav-notifications-count');
  await expect(badge).toBeVisible();
  await expect(badge).toHaveText(/[1-9]/);

  // Open the notification center: at least one item renders.
  await page.getByTestId('nav-notifications').click();
  await expect(page.getByTestId('notif-page')).toBeVisible();
  const items = page.getByTestId(/notif-item-\d+/);
  await expect(items.first()).toBeVisible();

  // Mark every unread item read (each button vanishes once its row is read;
  // the mark-read guard serializes clicks, so wait for the count to drop).
  const readButtons = page.getByTestId(/notif-read-\d+/);
  for (let i = 0; i < 10; i++) {
    const remaining = await readButtons.count();
    if (remaining === 0) {
      break;
    }
    await readButtons.first().click();
    await expect(readButtons).toHaveCount(remaining - 1);
  }
  await expect(readButtons).toHaveCount(0);

  // With nothing unread left, the header badge clears (element unmounts).
  await expect(page.getByTestId('nav-notifications-count')).toHaveCount(0);
});
