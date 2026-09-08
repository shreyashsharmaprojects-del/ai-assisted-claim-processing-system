import { expect, test } from '@playwright/test';
import { Browser, Page } from '@playwright/test';

let registrationCounter = 9000;

/**
 * Per-run loss date (same scheme as covers.spec.ts): the V2-2 duplicate guard is
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
  const username = `staged${stamp}`;
  const email = `${username}@example.test`;

  await page.goto('/claim/new');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();

  await page.locator('#firstName').fill('Staged');
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
 * Staged journey: file two covers on HLTH-CRIT (POL-30002, sum insured 25L — a
 * policy no other E2E journey touches, so the remaining-benefit math is stable)
 * → the holding L2 adjuster advances review → opens + completes a verification
 * → saves an assessment (claim reaches DECISION) → submits an over-authority
 * cover decision → the gate banner shows with the authority hint → auto-refer
 * moves the claim out of their hands (to the skilled L3).
 */
test('staged claim flows review to verification to decision, gates, and refers upwards', async ({
  browser,
}: {
  browser: Browser;
}) => {
  // A full six-login workflow: registration, FNOL, holder polling across the
  // roster, then the whole staged flow. The 60s suite default is too tight.
  test.setTimeout(180_000);
  const claimantContext = await browser.newContext();
  const claimantPage = await claimantContext.newPage();
  await registerClaimant(claimantPage);

  await claimantPage.getByTestId('fnol-policy-number').fill('POL-30002');
  await claimantPage.getByTestId('fnol-holder-name').fill('Fatima Khan');
  await claimantPage.getByTestId('fnol-holder-email').fill('fatima.khan@example.test');
  await claimantPage.getByTestId('fnol-next').click();
  await expect(claimantPage.getByTestId('fnol-covers')).toBeVisible();

  // CRITICAL_ILLNESS 900k (sub-limit 25L) + HOSPITALIZATION 200k (sub-limit
  // 500k): assessed 1.1M total, above the holding L2's 800k limit so the
  // decision gates and auto-refer picks the skilled L3.
  await claimantPage.getByTestId('fnol-cover-CRITICAL_ILLNESS').check();
  await claimantPage.getByTestId('fnol-amount-CRITICAL_ILLNESS').fill('900000');
  await claimantPage.getByTestId('fnol-cover-HOSPITALIZATION').check();
  await claimantPage.getByTestId('fnol-amount-HOSPITALIZATION').fill('200000');
  await claimantPage.getByTestId('fnol-loss-date').fill(lossDate);
  await claimantPage.getByTestId('fnol-loss-location').fill('London');
  await claimantPage.getByTestId('fnol-loss-description').fill('Diagnosis plus hospital stay.');
  await claimantPage.getByTestId('fnol-submit').click();

  await expect(claimantPage.getByTestId('claim-number')).toBeVisible();
  const claimNumber = (await claimantPage.getByTestId('claim-number').textContent())!.trim();
  expect(claimNumber).toMatch(/CLM-\d{6}/);
  await claimantContext.close();

  // Find the holding adjuster: HLTH-CRIT routes L2, so poll them first.
  let holder: Page | null = null;
  let holderContext = null;
  for (const username of [
    'adjuster.three',
    'adjuster.five',
    'adjuster.one',
    'adjuster.two',
    'adjuster.four',
    'adjuster.six',
  ]) {
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
      holder = page;
      holderContext = context;
      break;
    }
    await context.close();
  }
  expect(holder, 'an adjuster holds the filed claim').not.toBeNull();
  const adjusterPage = holder!;

  // Open the claim workspace: the stepper starts at Review.
  await adjusterPage
    .getByTestId('queue-row')
    .filter({ hasText: claimNumber })
    .first()
    .getByTestId('queue-open-claim')
    .click();
  await expect(adjusterPage.getByTestId('claim-detail-page')).toBeVisible();
  await expect(adjusterPage.getByTestId('detail-stage-stepper')).toBeVisible();
  await expect(adjusterPage.getByTestId('detail-review-panel')).toBeVisible();
  // The above-limit cover is flagged at review.
  await expect(adjusterPage.getByTestId('detail-review-covers')).toContainText(
    'HOSPITALIZATION',
  );

  // Review → advance to verification.
  await adjusterPage.getByTestId('detail-review-rationale').fill('Documents look complete.');
  await adjusterPage.getByTestId('detail-review-advance').click();
  await expect(adjusterPage.getByTestId('detail-verification-panel')).toBeVisible();

  // Open a DIGITAL verification and complete it with outcome + notes.
  await adjusterPage.getByTestId('detail-verification-type').selectOption('DIGITAL');
  await adjusterPage.getByTestId('detail-verification-notes').fill('Checking discharge summary.');
  await adjusterPage.getByTestId('detail-verification-create').click();
  // ADVANCE already opened a typeless PENDING row: wait for the new DIGITAL row
  // before targeting last() — otherwise the assertions below would complete the
  // PENDING row while the POST is still in flight.
  await expect(adjusterPage.getByTestId('detail-verification-item')).toHaveCount(2);
  const item = adjusterPage.getByTestId('detail-verification-item').last();
  await expect(item).toBeVisible();
  // The per-record complete form carries the row id in its testids; read it back.
  const outcomeSelect = item.getByTestId(/detail-ver-outcome-\d+/);
  await outcomeSelect.selectOption('PASSED');
  const saveButton = item.getByTestId(/detail-ver-save-\d+/);
  const saveTestId = (await saveButton.getAttribute('data-testid'))!;
  const rowId = saveTestId.replace('detail-ver-save-', '');
  await adjusterPage.getByTestId(`detail-ver-notes-${rowId}`).fill('Bills verified; admissible.');
  await adjusterPage.getByTestId(`detail-ver-evidence-${rowId}`).fill('attachment-1');
  await saveButton.click();
  await expect(adjusterPage.getByTestId('detail-verification-guard')).toHaveCount(0);

  // Assessment: assess the claimed figures in full (within sub-limits and
  // the untouched 25L sum insured) then save.
  await adjusterPage.getByTestId('detail-assess-CRITICAL_ILLNESS').fill('900000');
  await adjusterPage.getByTestId('detail-assess-HOSPITALIZATION').fill('200000');
  await adjusterPage.getByTestId('detail-assessment-rationale').fill('Within cover limits.');
  await adjusterPage.getByTestId('detail-save-assessment').click();
  await expect(adjusterPage.getByTestId('detail-stage-DECISION')).toBeVisible();

  // Cover decision above the holder's authority: approve both (1.1M proposed
  // vs the 800k L2 limit). The authority hint is always visible next to the
  // proposed aggregate.
  await adjusterPage.getByTestId('detail-approve-CRITICAL_ILLNESS').fill('900000');
  await adjusterPage.getByTestId('detail-approve-HOSPITALIZATION').fill('200000');
  await expect(adjusterPage.getByTestId('detail-authority-hint')).toContainText('proposed');
  await expect(adjusterPage.getByTestId('detail-authority-hint')).toContainText('limit');
  await adjusterPage.getByTestId('detail-decision-rationale').fill('Recommend full payment.');
  await adjusterPage.getByTestId('detail-submit-decision').click();

  // The gate holds: proposals saved, nothing closed, referral offered.
  await expect(adjusterPage.getByTestId('detail-gate-banner')).toBeVisible();
  await expect(adjusterPage.getByTestId('detail-decision-result')).toContainText(
    'Above your authority',
  );

  // Explicit referral: auto-pick a qualified senior.
  await adjusterPage.getByTestId('detail-refer-reason').fill('Above my authority; needs senior sign-off.');
  await adjusterPage.getByTestId('detail-refer-auto').click();
  await expect(adjusterPage.getByTestId('detail-decision-result')).toContainText(
    'Referred upwards',
  );

  // The claim left the actor's hands: no decision or review forms remain.
  await expect(adjusterPage.getByTestId('detail-submit-decision')).toHaveCount(0);
  await expect(adjusterPage.getByTestId('detail-review-panel')).toHaveCount(0);
  await holderContext!.close();
});
