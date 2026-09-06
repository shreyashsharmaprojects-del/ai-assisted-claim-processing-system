import { expect, test } from '@playwright/test';
import { Browser, Page } from '@playwright/test';

let registrationCounter = 0;

/** Registers a fresh claimant through the real Keycloak realm and returns to the FNOL form. */
async function registerClaimant(page: Page): Promise<void> {
  const stamp = Date.now() + '_' + registrationCounter++;
  const username = `claimant${stamp}`;
  const email = `${username}@example.test`;

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
  await expect(page.getByTestId('fnol-policy-number')).toBeVisible();
}

/**
 * Signs in a provisioned adjuster (username/password at Keycloak — adjusters cannot
 * self-register) and lands on the queue page.
 */
async function signInAdjuster(page: Page, username: string): Promise<void> {
  await page.goto('/queue');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill('adjuster-Pass-123');
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('queue-page')).toBeVisible();
}

/** True when the signed-in adjuster's queue shows exactly one row for the claim. */
async function queueShows(page: Page, claimNumber: string): Promise<boolean> {
  await page.goto('/queue');
  await expect(page.getByTestId('queue-page')).toBeVisible();
  await expect(page.getByTestId('queue-error')).toHaveCount(0);
  const row = page.getByTestId('queue-row').filter({ hasText: claimNumber });
  return (await row.count()) === 1;
}

/**
 * Journey 3: a claimant files an FNOL; the claim is assigned to exactly one of the two
 * seeded L1 adjusters (the least-loaded, deterministically tie-broken — asserted at the
 * integration layer) and shows up in that adjuster's queue and no one else's. The exact
 * assignee is not asserted here because the shared e2e database accumulates claims across
 * runs: "exactly one L1 adjuster holds it, never the L2 adjuster" is the stable fact.
 */
test('a filed claim lands in exactly one L1 adjuster queue and never in the L2 queue', async ({
  browser,
}) => {
  // Set up through the UI like journey 1: a fresh claimant files an FNOL against POL-10001.
  const claimantContext = await browser.newContext();
  const claimantPage = await claimantContext.newPage();
  await registerClaimant(claimantPage);

  await claimantPage.getByTestId('fnol-policy-number').fill('POL-10001');
  await claimantPage.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await claimantPage.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await claimantPage.getByTestId('fnol-loss-date').fill('2026-09-01');
  await claimantPage.getByTestId('fnol-loss-location').fill('London');
  await claimantPage.getByTestId('fnol-loss-description').fill('Kitchen flooded after a pipe burst.');
  await claimantPage.getByTestId('fnol-submit').click();

  await expect(claimantPage.getByTestId('claim-number')).toBeVisible();
  const claimNumber = (await claimantPage.getByTestId('claim-number').textContent())!.trim();
  // The claimant's process-steps screen reflects the assignment (status -> under review).
  await expect(claimantPage.getByTestId('claim-steps')).toContainText('Under review');
  await claimantContext.close();

  // Each adjuster opens their own queue in a fresh browser context (own Keycloak session).
  const oneContext = await browser.newContext();
  const onePage = await oneContext.newPage();
  await signInAdjuster(onePage, 'adjuster.one');
  const oneHolds = await queueShows(onePage, claimNumber);
  await oneContext.close();

  const twoContext = await browser.newContext();
  const twoPage = await twoContext.newPage();
  await signInAdjuster(twoPage, 'adjuster.two');
  const twoHolds = await queueShows(twoPage, claimNumber);
  await twoContext.close();

  // Exactly one L1 adjuster holds the claim — never both, never neither.
  expect(oneHolds || twoHolds, `claim ${claimNumber} must be assigned to one of the two L1 adjusters`).toBe(true);
  expect(oneHolds && twoHolds, `claim ${claimNumber} must appear in only one L1 queue`).toBe(false);

  // …and the L2 adjuster never does.
  const threeContext = await browser.newContext();
  const threePage = await threeContext.newPage();
  await signInAdjuster(threePage, 'adjuster.three');
  const threeHolds = await queueShows(threePage, claimNumber);
  await threeContext.close();
  expect(threeHolds, `the L2 queue must not contain the L1 claim ${claimNumber}`).toBe(false);
});
