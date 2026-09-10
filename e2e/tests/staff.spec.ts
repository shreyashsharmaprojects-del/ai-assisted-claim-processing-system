import { expect, test } from '@playwright/test';
import { Browser, Page } from '@playwright/test';

let registrationCounter = 7000;

/**
 * Per-run loss date (staged.spec.ts scheme): the V2-2 duplicate guard is not
 * claimant-scoped and the hermetic claims_e2e DB persists across runs, so a
 * fixed date would 409 on a previous run's own filing.
 */
const runDate = new Date(
  Date.UTC(2020, 0, 1) + (Math.floor(Date.now() / 1000) % 2000) * 86_400_000,
);
const lossDate = runDate.toISOString().slice(0, 10);

function requiredEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} is not set — copy .env.example to .env (or set it in CI).`);
  }
  return value;
}

/** Registers a fresh claimant through the real Keycloak realm and returns to the FNOL form. */
async function registerClaimant(page: Page): Promise<void> {
  const stamp = Date.now() + '_' + registrationCounter++;
  const username = `staff${stamp}`;
  const email = `${username}@example.test`;

  await page.goto('/claim/new');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();

  await page.locator('#firstName').fill('Staff');
  await page.locator('#lastName').fill('Test');
  await page.locator('#email').fill(email);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill('claims-Pass-123');
  await page.locator('#password-confirm').fill('claims-Pass-123');
  await page.getByRole('button', { name: 'Register' }).click();
  await expect(page.getByTestId('fnol-policy-number')).toBeVisible();
}

/** Signs in a provisioned adjuster and lands on the queue page. */
async function signInAdjuster(page: Page, username: string): Promise<void> {
  await page.goto('/queue');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(requiredEnv('ADJUSTER_PASSWORD'));
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('queue-page')).toBeVisible();
}

/** Signs in the provisioned supervisor and lands on the escalation queue. */
async function signInSupervisor(page: Page): Promise<void> {
  await page.goto('/escalations');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.locator('#username').fill('supervisor');
  await page.locator('#password').fill(requiredEnv('SUPERVISOR_PASSWORD'));
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('escalations-page')).toBeVisible();
}

/**
 * True when the signed-in adjuster's queue holds the claim. Uses the server-side
 * queue search (queue.spec.ts polls the first page only — L1 queues hold ~29 rows
 * across two pages here, so a moved claim can hide on page 2): after filling the
 * search, the backend narrows to the matching row or the no-results panel.
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
  await page.getByTestId('queue-search').fill(claimNumber);
  await expect(
    page
      .getByTestId('queue-row')
      .filter({ hasText: claimNumber })
      .or(page.getByTestId('queue-no-results')),
  ).toBeVisible();
  const row = page.getByTestId('queue-row').filter({ hasText: claimNumber });
  return (await row.count()) === 1;
}

/** True when the signed-in adjuster's queue (all pages) no longer holds the claim. */
async function queueHides(page: Page, claimNumber: string): Promise<boolean> {
  await page.goto('/queue');
  await expect(page.getByTestId('queue-page')).toBeVisible();
  await expect(
    page
      .getByTestId('queue-error')
      .or(page.getByTestId('queue-empty'))
      .or(page.getByTestId('queue-row').first()),
  ).toBeVisible();
  await expect(page.getByTestId('queue-error')).toHaveCount(0);
  await page.getByTestId('queue-search').fill(claimNumber);
  await expect(page.getByTestId('queue-no-results')).toBeVisible();
  return (await page.getByTestId('queue-row').filter({ hasText: claimNumber }).count()) === 0;
}

/** Seeded L1 adjusters (HOME-skilled: one + two; four has no HOME skill but is L1). */
const L1_ADJUSTERS: Record<string, string> = {
  'adjuster.one': 'Priya Sharma',
  'adjuster.two': 'Marcus Webb',
  'adjuster.four': 'Aisha Verma',
};

/**
 * S7 journey: the supervisor deactivates the L1 adjuster holding a freshly filed
 * claim → the claim drains to another L1's queue and leaves the holder's queue.
 * The holder is reactivated in a finally so the shared claims_e2e DB stays clean.
 */
test('supervisor deactivates an L1 with an open claim and it moves to another L1 queue', async ({
  browser,
}: {
  browser: Browser;
}) => {
  test.setTimeout(240_000);

  // (1) File one HOME claim (POL-10001 routes L1) as a fresh claimant.
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
  await claimantPage
    .getByTestId('fnol-loss-description')
    .fill(`Staff E2E burst pipe ${Date.now()}.`);
  await claimantPage.getByTestId('fnol-submit').click();
  await expect(claimantPage.getByTestId('claim-number')).toBeVisible();
  const claimNumber = (await claimantPage.getByTestId('claim-number').textContent())!.trim();
  expect(claimNumber).toMatch(/CLM-\d{6}/);
  await claimantContext.close();

  // (2) Find which L1 adjuster holds it.
  let holderUsername: string | null = null;
  for (const username of Object.keys(L1_ADJUSTERS)) {
    const holderContext = await browser.newContext();
    const holderPage = await holderContext.newPage();
    await signInAdjuster(holderPage, username);
    if (await queueShows(holderPage, claimNumber)) {
      holderUsername = username;
    }
    await holderContext.close();
    if (holderUsername) {
      break;
    }
  }
  expect(holderUsername, `an L1 adjuster holds claim ${claimNumber}`).not.toBeNull();
  const holderName = L1_ADJUSTERS[holderUsername!];

  // (3) Supervisor deactivates the holder on /admin/staff, then reactivates in
  // a finally so no other spec inherits a deactivated adjuster.
  const supervisorContext = await browser.newContext();
  const supervisorPage = await supervisorContext.newPage();
  await signInSupervisor(supervisorPage);
  await supervisorPage.goto('/admin/staff');
  await expect(supervisorPage.getByTestId('staff-page')).toBeVisible();

  const holderRow = supervisorPage
    .getByTestId(/staff-row-\d+/)
    .filter({ hasText: holderName });
  await expect(holderRow).toHaveCount(1);
  const holderLoad = holderRow.getByTestId(/staff-load-\d+/);
  const loadBefore = Number(((await holderLoad.textContent()) ?? '').trim());

  try {
    await holderRow.getByTestId(/staff-toggle-\d+/).click();
    await expect(supervisorPage.getByTestId('staff-confirm')).toBeVisible();
    await expect(supervisorPage.getByTestId('staff-page')).toContainText('will move');
    await supervisorPage.getByTestId('staff-confirm').click();
    await expect(holderRow).toContainText('INACTIVE');

    // staff-load-{id} count dropped: the holder's open queue drained.
    const loadAfter = Number(((await holderLoad.textContent()) ?? '').trim());
    expect(loadAfter).toBeLessThan(loadBefore);

    // (4) The claim left the holder's queue…
    const aContext = await browser.newContext();
    const aPage = await aContext.newPage();
    await signInAdjuster(aPage, holderUsername!);
    expect(await queueHides(aPage, claimNumber)).toBe(true);
    await aContext.close();

    // …and appears in another L1's queue.
    let otherHolds = 0;
    for (const username of Object.keys(L1_ADJUSTERS).filter((u) => u !== holderUsername)) {
      const otherContext = await browser.newContext();
      const otherPage = await otherContext.newPage();
      await signInAdjuster(otherPage, username);
      if (await queueShows(otherPage, claimNumber)) {
        otherHolds++;
      }
      await otherContext.close();
    }
    expect(otherHolds, `claim ${claimNumber} must appear in another L1 queue`).toBe(1);
  } finally {
    // Reactivate the holder: no claim moves, the shared DB keeps all L1s active.
    await supervisorPage.goto('/admin/staff');
    await expect(supervisorPage.getByTestId('staff-page')).toBeVisible();
    const row = supervisorPage.getByTestId(/staff-row-\d+/).filter({ hasText: holderName });
    await expect(row).toHaveCount(1);
    const toggle = row.getByTestId(/staff-toggle-\d+/);
    if ((await toggle.textContent())?.includes('Reactivate')) {
      await toggle.click();
      await expect(supervisorPage.getByTestId('staff-confirm')).toBeVisible();
      await supervisorPage.getByTestId('staff-confirm').click();
      await expect(row).toContainText('ACTIVE');
    }
    await supervisorContext.close();
  }
});
