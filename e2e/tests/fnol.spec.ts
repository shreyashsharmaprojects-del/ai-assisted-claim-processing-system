import { expect, test } from '@playwright/test';
import { Browser, Page } from '@playwright/test';

const PHOTO = Buffer.from([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
]);

/** Minimal 1-page valid %PDF fixture (S1: tiny PDF alongside the photo). */
const TINY_PDF = Buffer.from(
  '%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n',
  'utf8',
);

let registrationCounter = 0;

/**
 * Per-run loss date (same scheme as staged.spec.ts): the duplicate guard is
 * intentionally not claimant-scoped and the claims_e2e DB persists across runs,
 * so a fixed date would 409 on the previous run's own filings.
 */
const runDate = new Date(Date.UTC(2020, 0, 1)
  + (Math.floor(Date.now() / 1000) % 2000) * 86_400_000);
const lossDate = runDate.toISOString().slice(0, 10);

/** Registers a fresh claimant through the real Keycloak realm and returns to the FNOL form. */
async function registerClaimant(page: Page): Promise<void> {
  const stamp = Date.now() + '_' + registrationCounter++;
  const username = `claimant${stamp}`;
  const email = `${username}@example.test`;

  // File a claim is protected -> the guard sends the user to Keycloak for sign-in.
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

  // Back on the app, on step 1 of the FNOL form.
  await expect(page.getByTestId('fnol-policy-number')).toBeVisible();
}

/** Provisioned-realm passwords come from the environment (see .env.example -> .env). */
function requiredEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} is not set — copy .env.example to .env (or set it in CI).`);
  }
  return value;
}

/**
 * Signs in a provisioned adjuster (username/password at Keycloak — adjusters cannot
 * self-register) and lands on the queue page.
 */
async function signInAdjuster(page: Page, username: string): Promise<void> {
  await page.goto('/queue');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(requiredEnv('ADJUSTER_PASSWORD'));
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('queue-page')).toBeVisible();
}

/**
 * True when the signed-in adjuster's queue shows exactly one row for the claim. Waits
 * for the queue fetch to settle first: rows render only once loaded() is true, so
 * counting rows immediately after the page shell appears can race the render under
 * parallel load and report a claim that is present as missing (observed on the
 * shared claims_e2e DB).
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

/** Signs in the L1 adjuster who holds the claim and opens its detail screen. */
async function openClaimAsHolder(browser: Browser, claimNumber: string): Promise<Page> {
  for (const username of ['adjuster.one', 'adjuster.two', 'adjuster.four']) {
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

/** Completes FNOL step 1 (policy) and lands on step 2 (loss details). */
async function completeFnolStep1(page: Page): Promise<void> {
  await page.getByTestId('fnol-policy-number').fill('POL-10001');
  await page.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await page.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await page.getByTestId('fnol-next').click();
  await expect(page.getByTestId('fnol-loss-date')).toBeVisible();
}

/**
 * Journey 1: a claimant signs up through Keycloak, files an FNOL with a photo and a
 * tiny PDF against the seeded policy POL-10001, and immediately sees their claim
 * number. An adjuster then opens the claim from their queue: the PDF shows as a
 * timeline document row and downloads with its filename preserved (S1 E2E).
 */
test('claimant registers, files an FNOL with a photo and PDF and sees the claim number', async ({
  page,
  browser,
}) => {
  await registerClaimant(page);
  await completeFnolStep1(page);

  await page.getByTestId('fnol-loss-date').fill(lossDate);
  await page.getByTestId('fnol-loss-location').fill('London');
  await page.getByTestId('fnol-loss-description').fill('Kitchen flooded after a pipe burst.');
  await page.getByTestId('fnol-remarks').fill('Happened overnight.');
  await page.getByTestId('fnol-photos').setInputFiles([
    {
      name: 'kitchen.png',
      mimeType: 'image/png',
      buffer: PHOTO,
    },
    {
      name: 'discharge.pdf',
      mimeType: 'application/pdf',
      buffer: TINY_PDF,
    },
  ]);

  await page.getByTestId('fnol-submit').click();

  // The claim number is returned immediately, with a plain statement of what happens next.
  await expect(page.getByTestId('claim-number')).toBeVisible();
  const claimNumber = ((await page.getByTestId('claim-number').textContent()) ?? '').trim();
  await expect(page.getByTestId('claim-number')).toHaveText(/CLM-\d{6}/);
  await expect(page.getByTestId('fnol-error')).toHaveCount(0);
  await expect(page.getByTestId('claim-steps')).toContainText('FNOL received');

  // The adjuster who holds the claim sees the PDF as a timeline document row and
  // downloads it with its filename preserved.
  const holder = await openClaimAsHolder(browser, claimNumber);
  await expect(holder.getByTestId('detail-timeline-list')).toContainText('discharge.pdf');
  const downloadPromise = holder.waitForEvent('download');
  await holder.getByTestId('detail-attachment').filter({ hasText: 'discharge.pdf' }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe('discharge.pdf');
  await holder.context().close();
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
  await page.getByTestId('fnol-next').click();
  await expect(page.getByTestId('fnol-loss-date')).toBeVisible();
  await page.getByTestId('fnol-loss-date').fill(lossDate);
  await page.getByTestId('fnol-loss-location').fill('London');
  await page.getByTestId('fnol-loss-description').fill('Storm damage');

  await page.getByTestId('fnol-submit').click();

  await expect(page.getByTestId('fnol-error')).toBeVisible();
  await expect(page.getByTestId('fnol-error')).toContainText('could not match');
  // The form is still there to be corrected, and no claim number was fabricated.
  await expect(page.getByTestId('fnol-loss-description')).toBeVisible();
  await expect(page.getByTestId('claim-number')).toHaveCount(0);
});

/**
 * Journey 2: the claimant opens their claim status screen. Reserve and internal notes must
 * be absent from the screen AND from the /api/claims/{claimNumber} response on the wire —
 * the visibility wall is a structural property of the API, not a UI hiding.
 */
test('claimant status screen shows no reserve or internal notes, on screen or wire', async ({
  page,
}) => {
  // Capture the claim-status response body to assert the wall at the wire level too.
  const leaked: string[] = [];
  page.on('response', async (response) => {
    if (response.request().method() === 'GET' && /\/api\/claims\/CLM-\d+$/.test(response.url())) {
      const body = await response.text();
      for (const field of ['reserveAmount', 'reserve', 'notes', 'assignedTo', 'policyNumber', 'coverage']) {
        if (body.includes(`"${field}"`)) {
          leaked.push(field);
        }
      }
    }
  });

  await registerClaimant(page);
  await page.getByTestId('fnol-policy-number').fill('POL-10001');
  await page.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await page.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await page.getByTestId('fnol-next').click();
  await expect(page.getByTestId('fnol-loss-date')).toBeVisible();
  await page.getByTestId('fnol-loss-date').fill(lossDate);
  await page.getByTestId('fnol-loss-location').fill('London');
  await page.getByTestId('fnol-loss-description').fill('Kitchen flooded after a pipe burst.');
  await page.getByTestId('fnol-submit').click();

  await expect(page.getByTestId('claim-number')).toBeVisible();
  await page.getByTestId('track-claim').click();

  // The status screen renders the public steps…
  await expect(page.getByTestId('claim-status-page')).toBeVisible();
  await expect(page.getByTestId('claim-status-steps')).toContainText('Under review');
  // …and nothing internal: no reserve section, no notes, no coverage/policy detail.
  await expect(page.getByTestId('claim-status-page')).not.toContainText('Reserve');
  await expect(page.getByTestId('claim-status-page')).not.toContainText('Internal notes');
  await expect(page.getByTestId('claim-status-page')).not.toContainText('coverage');

  expect(leaked, `internal fields leaked on the claimant wire: ${leaked.join(', ')}`).toEqual([]);
});
