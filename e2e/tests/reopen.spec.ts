import { expect, test } from '@playwright/test';
import { Browser, Page } from '@playwright/test';

let registrationCounter = 5000;

/**
 * Per-run loss date (same scheme as staged.spec.ts): the V2-2 duplicate guard is
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
  const username = `reopen${stamp}`;
  const email = `${username}@example.test`;

  await page.goto('/claim/new');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();

  await page.locator('#firstName').fill('Reopen');
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

async function signInSupervisor(page: Page): Promise<void> {
  await page.goto('/escalations');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.locator('#username').fill('supervisor');
  await page.locator('#password').fill(requiredEnv('SUPERVISOR_PASSWORD'));
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('escalations-page')).toBeVisible();
}

/**
 * True when the signed-in adjuster's queue shows exactly one row for the claim. Waits
 * for the queue fetch to settle first (same race guard as queue.spec.ts).
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

/** Reloads the detail page so the form state (incl. expectedVersion) is fresh. */
async function reloadDetail(page: Page): Promise<void> {
  await page.reload();
  await expect(page.getByTestId('claim-detail-page')).toBeVisible();
  await expect(page.getByTestId('detail-stage-stepper')).toBeVisible();
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
    await page.getByTestId(`detail-ver-notes-${rowId}`).fill('Re-checked on rework; admissible.');
    await page.getByTestId(`detail-ver-evidence-${rowId}`).fill('attachment-1');
    await saveButton.click();
  }
  await expect(page.getByTestId('detail-verification-guard')).toHaveCount(0);
}

/**
 * S6 reopen journey: a staged claim is worked to CLOSED by its holder, a supervisor
 * reopens it (timeline "Reopened"), the claimant tracker shows the reopened panel,
 * and the holding adjuster re-works it (assess + decide) to CLOSED again.
 */
test('supervisor reopens a decided staged claim, tracker shows reopened, adjuster re-closes', async ({
  browser,
}: {
  browser: Browser;
}) => {
  test.setTimeout(300_000);
  const claimantContext = await browser.newContext();
  const claimantPage = await claimantContext.newPage();
  await registerClaimant(claimantPage);

  // (1) Staged FNOL on HLTH-PLUS (POL-10001, own L1-routed policy, sum insured
  // 5L): one small cover, well within the holding L1's 100k limit so the first
  // decision closes directly (no referral). Small amounts keep the assessed
  // total under the sum-insured cap even after prior E2E closures on this
  // shared policy. POL-30002 is unusable here: its HOSPITALIZATION cover
  // carries a 10k default deductible, so any assessed total trips the
  // remaining-sum-insured cap once prior runs have paid out on it.
  await claimantPage.getByTestId('fnol-policy-number').fill('POL-10001');
  await claimantPage.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await claimantPage.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await claimantPage.getByTestId('fnol-next').click();
  await expect(claimantPage.getByTestId('fnol-covers')).toBeVisible();

  await claimantPage.getByTestId('fnol-cover-OPD').check();
  await claimantPage.getByTestId('fnol-amount-OPD').fill('5000');
  await claimantPage.getByTestId('fnol-loss-date').fill(lossDate);
  await claimantPage.getByTestId('fnol-loss-location').fill('London');
  await claimantPage.getByTestId('fnol-loss-description').fill('Diagnosis plus hospital stay.');
  await claimantPage.getByTestId('fnol-submit').click();

  await expect(claimantPage.getByTestId('claim-number')).toBeVisible();
  const claimNumber = (await claimantPage.getByTestId('claim-number').textContent())!.trim();
  expect(claimNumber).toMatch(/CLM-\d{6}/);

  // Work to CLOSED as the holder: review -> verification -> assess -> decide.
  const holder = await openClaimAsHolder(browser, claimNumber);
  await expect(holder.getByTestId('detail-review-panel')).toBeVisible();
  await holder.getByTestId('detail-review-rationale').fill('Documents look complete.');
  await expect(holder.getByTestId('detail-review-advance')).toBeEnabled();
  await holder.getByTestId('detail-review-advance').click();
  await expect(holder.getByTestId('detail-verification-panel')).toBeVisible();
  await completeOpenVerifications(holder);

  await holder.getByTestId('detail-assess-OPD').fill('5000');
  await holder.getByTestId('detail-assessment-rationale').fill('Within cover limits.');
  await holder.getByTestId('detail-save-assessment').click();
  // The stepper li always exists (staged.spec.ts asserts the current marker,
  // not visibility, for exactly this reason).
  await expect(holder.getByTestId('detail-stage-DECISION')).toHaveClass(/is-current/);

  await holder.getByTestId('detail-approve-OPD').fill('5000');
  await holder.getByTestId('detail-decision-rationale').fill('Recommend full payment.');
  await holder.getByTestId('detail-submit-decision').click();
  await expect(holder.getByTestId('detail-decision-closed')).toBeVisible();
  await holder.context().close();

  // (2) As supervisor: open the closed claim directly and reopen it.
  const supervisorContext = await browser.newContext();
  const supervisorPage = await supervisorContext.newPage();
  await signInSupervisor(supervisorPage);
  await supervisorPage.goto('/claims/' + claimNumber);
  await expect(supervisorPage.getByTestId('claim-detail-page')).toBeVisible();
  await expect(supervisorPage.getByTestId('detail-status')).toContainText('CLOSED');

  const rationale = `Reopened for re-review run ${Date.now()} — new evidence arrived.`;
  await supervisorPage.getByTestId('detail-reopen-toggle').click();
  await supervisorPage.getByTestId('detail-reopen-rationale').fill(rationale);
  await supervisorPage.getByTestId('detail-reopen-confirm').click();
  await expect(supervisorPage.getByTestId('detail-timeline-list')).toContainText('Reopened');
  await supervisorContext.close();

  // (3) As claimant: the tracker shows the reopened panel.
  await claimantPage.goto('/claim/' + claimNumber);
  await expect(claimantPage.getByTestId('claim-status-page')).toBeVisible();
  await expect(claimantPage.getByTestId('claim-status-page')).toContainText(
    'was reopened — what happens next',
  );

  // (4) As the (possibly reassigned) holding adjuster: re-work to closure.
  // Reopen resets the claim to REVIEW with the old verification history kept;
  // advancing review opens a FRESH default checklist, then assess + decide.
  // RACE: the queue list and the detail screen can straddle the reopen — the
  // reopen may commit AFTER the queue read but BEFORE the staged GET, so the
  // page loads the CLOSED version and review/ADVANCE 409s (stale
  // expectedVersion; the review() handler has no handleConflict, so the error
  // lands in claim-detail-error). Retry loop below (poll, no sleep): reload
  // replays the reopened REVIEW view, then ADVANCE again.
  const reworker = await openClaimAsHolder(browser, claimNumber);
  await expect
    .poll(
      async () => {
        if ((await reworker.getByTestId('detail-verification-panel').count()) > 0) {
          return 'ready';
        }
        if ((await reworker.getByTestId('detail-review-panel').count()) === 0) {
          return 'waiting';
        }
        await reworker.getByTestId('detail-review-rationale').fill('Re-reviewing after reopen.');
        await expect(reworker.getByTestId('detail-review-advance')).toBeEnabled();
        const advanceResponse = reworker.waitForResponse(
          (r) => r.url().includes('/api/claims/') && r.url().endsWith('/review'),
        );
        await reworker.getByTestId('detail-review-advance').click();
        const status = (await advanceResponse).status();
        if (status === 200) {
          return 'ready';
        }
        // 409 straddle: reload replays the reopened view, then poll again.
        await reloadDetail(reworker);
        return 'retry';
      },
      { timeout: 60_000 },
    )
    .toBe('ready');
  await expect(reworker.getByTestId('detail-verification-panel')).toBeVisible();
  await completeOpenVerifications(reworker);

  await reworker.getByTestId('detail-assess-OPD').fill('5000');
  await reworker.getByTestId('detail-assessment-rationale').fill('Confirmed again on rework.');
  await reworker.getByTestId('detail-save-assessment').click();
  await expect(reworker.getByTestId('detail-stage-DECISION')).toHaveClass(/is-current/);

  await reworker.getByTestId('detail-approve-OPD').fill('5000');
  await reworker.getByTestId('detail-decision-rationale').fill('Confirming full payment on rework.');
  await reworker.getByTestId('detail-submit-decision').click();
  await expect(reworker.getByTestId('detail-decision-closed')).toBeVisible();
  await expect(reworker.getByTestId('detail-status')).toContainText('CLOSED');
  await reworker.context().close();

  await claimantContext.close();
});
