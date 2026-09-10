import { expect, test } from '@playwright/test';
import { Browser, Page } from '@playwright/test';

let registrationCounter = 7000;

/**
 * S8 structured-decision E2E: staged FNOL on the multi-cover HLTH-PLUS policy
 * (POL-10001) → holder works review → verification → assessment → cover-decision,
 * rejects one cover with a denial code, asserts the rationale count hint
 * (short rationale blocks submit client-side), then closes successfully.
 *
 * Per-run loss date (same scheme as staged.spec.ts): the V2-2 duplicate guard is
 * not claimant-scoped and the hermetic claims_e2e DB persists across runs.
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
  const username = `structdec${stamp}`;
  const email = `${username}@example.test`;

  await page.goto('/claim/new');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();

  await page.locator('#firstName').fill('Structured');
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
 * Reject one cover with a denial code: short rationale shows the min-length
 * hint and blocks submit; a ≥20-char rationale updates the count and closes.
 */
test('structured decision: reject a cover with a code, count hint, then close', async ({
  browser,
}: {
  browser: Browser;
}) => {
  test.setTimeout(240_000);
  const claimantContext = await browser.newContext();
  const claimantPage = await claimantContext.newPage();
  await registerClaimant(claimantPage);

  // (1) Staged FNOL on HLTH-PLUS (POL-10001, L1-routed, 5 covers): two small
  // covers so the split decision stays within the holding L1's 100k authority
  // and closes directly (no referral).
  await claimantPage.getByTestId('fnol-policy-number').fill('POL-10001');
  await claimantPage.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await claimantPage.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await claimantPage.getByTestId('fnol-next').click();
  await expect(claimantPage.getByTestId('fnol-covers')).toBeVisible();

  await claimantPage.getByTestId('fnol-cover-HOSPITALIZATION').check();
  await claimantPage.getByTestId('fnol-amount-HOSPITALIZATION').fill('8000');
  await claimantPage.getByTestId('fnol-cover-DAYCARE').check();
  await claimantPage.getByTestId('fnol-amount-DAYCARE').fill('2000');
  await claimantPage.getByTestId('fnol-loss-date').fill(lossDate);
  await claimantPage.getByTestId('fnol-loss-location').fill('London');
  await claimantPage.getByTestId('fnol-loss-description').fill('Hospital stay plus daycare follow-up.');
  await claimantPage.getByTestId('fnol-submit').click();

  await expect(claimantPage.getByTestId('claim-number')).toBeVisible();
  const claimNumber = (await claimantPage.getByTestId('claim-number').textContent())!.trim();
  expect(claimNumber).toMatch(/CLM-\d{6}/);
  await claimantContext.close();

  // (2) Work to DECISION as the holder: review -> verification -> assessment.
  const holder = await openClaimAsHolder(browser, claimNumber);
  await expect(holder.getByTestId('detail-review-panel')).toBeVisible();
  await holder.getByTestId('detail-review-rationale').fill('Documents look complete.');
  await holder.getByTestId('detail-review-advance').click();
  await completeOpenVerifications(holder);

  await holder.getByTestId('detail-assess-HOSPITALIZATION').fill('7500');
  await holder.getByTestId('detail-assess-DAYCARE').fill('1800');
  await holder.getByTestId('detail-assessment-rationale').fill('Within cover limits.');
  await holder.getByTestId('detail-save-assessment').click();
  await expect(holder.getByTestId('detail-stage-DECISION')).toHaveClass(/is-current/);

  // (3) Reject DAYCARE with a denial code + remarks; approve HOSPITALIZATION.
  await holder.getByTestId('detail-approve-HOSPITALIZATION').fill('7000');
  await holder.getByTestId('detail-cover-decision-DAYCARE').selectOption('REJECTED');
  await holder.getByTestId('detail-deny-reason-DAYCARE').selectOption('INSUFFICIENT_EVIDENCE');
  await holder.getByTestId('detail-cover-remarks-DAYCARE')
    .fill('Daycare follow-ups unrelated to the admitted procedure.');

  // (4) Short rationale: the count hint shows the min-length error and
  // submit stays blocked client-side.
  await holder.getByTestId('detail-decision-rationale').fill('Too short');
  await expect(holder.getByTestId('detail-rationale-count')).toContainText('at least');
  await expect(holder.getByTestId('detail-submit-decision')).toBeDisabled();

  // (5) Full rationale: the count updates past the 20-char floor, submit
  // enables, and the split decision closes the claim.
  const rationale = 'Split outcome, hospital pays in full on this claim.';
  await holder.getByTestId('detail-decision-rationale').fill(rationale);
  await expect(holder.getByTestId('detail-rationale-count'))
    .toContainText(`${rationale.length}/20`);
  await expect(holder.getByTestId('detail-submit-decision')).toBeEnabled();
  await holder.getByTestId('detail-submit-decision').click();
  await expect(holder.getByTestId('detail-decision-closed')).toBeVisible();
  await expect(holder.getByTestId('detail-status')).toContainText('CLOSED');
  await holder.context().close();
});
