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

const PHOTO = Buffer.from([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
]);

/** Files an FNOL with a photo through the UI and returns the claim number. */
async function fileFnolWithPhoto(page: Page): Promise<string> {
  await page.getByTestId('fnol-policy-number').fill('POL-10001');
  await page.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await page.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await page.getByTestId('fnol-loss-date').fill('2026-09-01');
  await page.getByTestId('fnol-loss-location').fill('London');
  await page.getByTestId('fnol-loss-description').fill('Kitchen flooded after a pipe burst.');
  await page.getByTestId('fnol-photos').setInputFiles({
    name: 'kitchen.png',
    mimeType: 'image/png',
    buffer: PHOTO,
  });
  await page.getByTestId('fnol-submit').click();
  await expect(page.getByTestId('claim-number')).toBeVisible();
  const claimNumber = (await page.getByTestId('claim-number').textContent())!.trim();
  await expect(page.getByTestId('claim-steps')).toContainText('Under review');
  return claimNumber;
}

/** Signs in the L1 adjuster who holds the claim and opens its detail screen. */
async function openClaimAsHolder(browser: Browser, claimNumber: string): Promise<Page> {
  for (const username of ['adjuster.one', 'adjuster.two']) {
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

/**
 * Journey 4: the assigned adjuster opens the claim from their queue and works it — reads
 * policy/coverage and the loss, sets a reserve, adds an internal note, and downloads the
 * photo. All of that data is internal; journey 2 pins that none of it reaches the claimant.
 */
test('the assigned adjuster sets a reserve, adds an internal note and downloads the photo', async ({
  browser,
}) => {
  const claimantContext = await browser.newContext();
  const claimantPage = await claimantContext.newPage();
  await registerClaimant(claimantPage);
  const claimNumber = await fileFnolWithPhoto(claimantPage);
  await claimantContext.close();

  const holder = await openClaimAsHolder(browser, claimNumber);

  // The internal full view: policy + coverage + loss details.
  await expect(holder.getByTestId('detail-claim-number')).toHaveText(claimNumber);
  await expect(holder.getByTestId('detail-policy')).toHaveText('POL-10001');
  await expect(holder.getByTestId('detail-coverage')).toContainText('"type": "home"');
  await expect(holder.getByTestId('detail-loss-description')).toContainText('Kitchen flooded');

  // Set a reserve; the saved value is shown back.
  await holder.getByTestId('detail-reserve-input').fill('1250.50');
  await holder.getByTestId('detail-reserve-save').click();
  await expect(holder.getByTestId('detail-reserve-value')).toContainText('1250');

  // Add an internal note; it appears in the note list.
  await holder.getByTestId('detail-note-input').fill('Coverage confirmed; awaiting builder quote.');
  await holder.getByTestId('detail-note-add').click();
  await expect(holder.getByTestId('detail-note-list')).toContainText('Coverage confirmed');

  // Download the uploaded photo.
  const downloadPromise = holder.waitForEvent('download');
  await holder.getByTestId('detail-attachment').click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe('kitchen.png');

  await holder.context().close();
});

/**
 * Journey 5: the assigned L1 adjuster approves an indemnity within their authority limit.
 * The claim closes and leaves their queue (payment + decision recorded at the API layer,
 * journey-asserted here as the claim no longer being open).
 */
test('the adjuster approves a within-limit amount and the claim closes', async ({ browser }) => {
  const claimantContext = await browser.newContext();
  const claimantPage = await claimantContext.newPage();
  await registerClaimant(claimantPage);
  const claimNumber = await fileFnolWithPhoto(claimantPage);
  await claimantContext.close();

  const holder = await openClaimAsHolder(browser, claimNumber);

  // Approve 1500.00 — a HOME claim held by an L1 adjuster, within the seeded L1 limit.
  await holder.getByTestId('detail-decision-amount').fill('1500.00');
  await holder.getByTestId('detail-decision-rationale').fill('Quotes verified; within my authority.');
  await holder.getByTestId('detail-approve').click();

  await expect(holder.getByTestId('detail-decision-result')).toBeVisible();
  await expect(holder.getByTestId('detail-decision-result')).toContainText('Approved');
  await expect(holder.getByTestId('detail-decision-result')).toContainText('closed');
  // The claim is closed: no decision form remains.
  await expect(holder.getByTestId('detail-approve')).toHaveCount(0);

  // It has dropped out of the deciding adjuster's queue.
  await expect(holder.getByTestId('detail-decision-result')).toBeVisible();
  await holder.goto('/queue');
  await expect(holder.getByTestId('queue-page')).toBeVisible();
  const row = holder.getByTestId('queue-row').filter({ hasText: claimNumber });
  await expect(row).toHaveCount(0);
  await holder.context().close();
});

/**
 * Journey 6: the assigned L1 adjuster tries to approve an amount above their authority —
 * the approval is blocked and the claim escalates (re-assigned to the L2 adjuster rather
 * than closed by the actor, who structurally cannot self-approve).
 */
test('an above-limit approval is blocked and escalated to the L2 adjuster', async ({ browser }) => {
  const claimantContext = await browser.newContext();
  const claimantPage = await claimantContext.newPage();
  await registerClaimant(claimantPage);
  const claimNumber = await fileFnolWithPhoto(claimantPage);
  await claimantContext.close();

  const holder = await openClaimAsHolder(browser, claimNumber);

  // 5000.00 exceeds the seeded L1 limit (2500) but is within the L2 limit (10000): the
  // approval must be blocked and the claim escalated to the least-loaded L2 adjuster.
  await holder.getByTestId('detail-decision-amount').fill('5000.00');
  await holder.getByTestId('detail-decision-rationale').fill('Large water damage claim.');
  await holder.getByTestId('detail-approve').click();

  await expect(holder.getByTestId('detail-decision-result')).toBeVisible();
  await expect(holder.getByTestId('detail-decision-result')).toContainText('above your authority');
  await expect(holder.getByTestId('detail-decision-result')).toContainText('Level 2');
  await expect(holder.getByTestId('detail-decision-result')).not.toContainText('Approved');
  // No approve button remains — the claim is out of this adjuster's hands.
  await expect(holder.getByTestId('detail-approve')).toHaveCount(0);

  // It is no longer in the L1 adjuster's queue…
  await holder.goto('/queue');
  await expect(holder.getByTestId('queue-page')).toBeVisible();
  const l1Row = holder.getByTestId('queue-row').filter({ hasText: claimNumber });
  await expect(l1Row).toHaveCount(0);
  await holder.context().close();

  // …and it has been reassigned to the (only) L2 adjuster's queue.
  const l2Context = await browser.newContext();
  const l2Page = await l2Context.newPage();
  await signInAdjuster(l2Page, 'adjuster.three');
  const l2Holds = await queueShows(l2Page, claimNumber);
  expect(l2Holds, `the escalated claim ${claimNumber} must appear in the L2 adjuster's queue`)
    .toBe(true);
  await l2Context.close();
});

/**
 * Signs in the provisioned supervisor (fixed realm subject; supervisors have no app_user
 * row — their authority is the Keycloak role alone) and lands on the escalation queue.
 */
async function signInSupervisor(page: Page): Promise<void> {
  await page.goto('/escalations');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.locator('#username').fill('supervisor');
  await page.locator('#password').fill('supervisor-Pass-123');
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('escalations-page')).toBeVisible();
}

/**
 * Journey 7: an L1 adjuster tries to approve an amount above even the L2 limit — the claim
 * escalates to ESCALATED_SUPERVISOR (no adjuster holds it) — and the supervisor, who can
 * see the escalation queue, approves it with a rationale. Payment + closure are recorded
 * (asserted at the API layer); here the claim visibly closes and leaves the escalation
 * queue.
 */
test('supervisor approves an escalated claim with rationale and it closes', async ({ browser }) => {
  // Setup through the UI, like journey 6 but above the L2 limit: the claim escalates all
  // the way to the supervisor, not to an L2 adjuster.
  const claimantContext = await browser.newContext();
  const claimantPage = await claimantContext.newPage();
  await registerClaimant(claimantPage);
  const claimNumber = await fileFnolWithPhoto(claimantPage);
  await claimantContext.close();

  const holder = await openClaimAsHolder(browser, claimNumber);
  await holder.getByTestId('detail-decision-amount').fill('12000.00');
  await holder.getByTestId('detail-decision-rationale').fill('Substantial structural damage.');
  await holder.getByTestId('detail-approve').click();
  await expect(holder.getByTestId('detail-decision-result')).toBeVisible();
  await expect(holder.getByTestId('detail-decision-result')).toContainText('escalated to a supervisor');
  // The claim is out of the adjuster's hands: no decision form remains.
  await expect(holder.getByTestId('detail-approve')).toHaveCount(0);
  await holder.context().close();

  // The supervisor sees it in the escalation queue and opens it.
  const supervisorContext = await browser.newContext();
  const supervisorPage = await supervisorContext.newPage();
  await signInSupervisor(supervisorPage);
  const escalationRow = supervisorPage.getByTestId('esc-row').filter({ hasText: claimNumber });
  await expect(escalationRow).toHaveCount(1);
  await escalationRow.getByTestId('esc-open-claim').click();
  await expect(supervisorPage.getByTestId('claim-detail-page')).toBeVisible();
  await expect(supervisorPage.getByTestId('detail-status')).toContainText('ESCALATED_SUPERVISOR');

  // Approve with a rationale: the claim closes and leaves the escalation queue.
  await supervisorPage.getByTestId('detail-decision-amount').fill('12000.00');
  await supervisorPage.getByTestId('detail-decision-rationale').fill(
    'Agreed under full authority.',
  );
  await supervisorPage.getByTestId('detail-approve').click();
  await expect(supervisorPage.getByTestId('detail-decision-result')).toBeVisible();
  await expect(supervisorPage.getByTestId('detail-decision-result')).toContainText('Approved');
  await expect(supervisorPage.getByTestId('detail-decision-result')).toContainText('closed');
  await expect(supervisorPage.getByTestId('detail-approve')).toHaveCount(0);

  await supervisorPage.goto('/escalations');
  await expect(supervisorPage.getByTestId('escalations-page')).toBeVisible();
  await expect(
    supervisorPage.getByTestId('esc-row').filter({ hasText: claimNumber }),
  ).toHaveCount(0);
  await supervisorContext.close();
});

/**
 * Journey 8 (slice 6): the claimant opens their CLOSED claim and sees the decision — the
 * approved amount on an approval closure, or the denial remarks on a denial closure. Both
 * rendering branches are exercised (approval fixture = journey-5 flow, denial fixture = a
 * deny with rationale). The visibility wall is checked on the wire of the closed claims
 * too (journey-2 pattern): reserve/notes/assignee/coverage never reach a closed claim's
 * claimant response.
 */
test('claimant sees the decision on the closed claim: approved amount, or denial remarks', async ({
  browser,
}) => {
  const leaked: string[] = [];
  /** Captures the claimant status response to assert the wall holds on closed claims. */
  const watchClaimantWire = (page: Page) => {
    page.on('response', async (response) => {
      if (
        response.request().method() === 'GET' &&
        /\/api\/claims\/CLM-\d+$/.test(response.url())
      ) {
        const body = await response.text();
        for (const field of [
          'reserveAmount',
          'reserve',
          'notes',
          'assignedTo',
          'policyNumber',
          'coverage',
        ]) {
          if (body.includes(`"${field}"`)) {
            leaked.push(field);
          }
        }
      }
    });
  };

  // Approval closure: the assigned L1 adjuster approves 1500.00 within authority.
  const claimantContext = await browser.newContext();
  const claimantPage = await claimantContext.newPage();
  watchClaimantWire(claimantPage);
  await registerClaimant(claimantPage);
  const approvedClaim = await fileFnolWithPhoto(claimantPage);

  const holder = await openClaimAsHolder(browser, approvedClaim);
  await holder.getByTestId('detail-decision-amount').fill('1500.00');
  await holder
    .getByTestId('detail-decision-rationale')
    .fill('Quotes verified; within my authority.');
  await holder.getByTestId('detail-approve').click();
  await expect(holder.getByTestId('detail-decision-result')).toContainText('Approved');
  await holder.context().close();

  // The claimant reopens the closed claim: the approved amount is on the screen.
  await claimantPage.goto('/claim/' + approvedClaim);
  await expect(claimantPage.getByTestId('claim-status-page')).toBeVisible();
  await expect(claimantPage.getByTestId('claim-status-state')).toHaveText('CLOSED');
  await expect(claimantPage.getByTestId('claim-decision-approved')).toBeVisible();
  await expect(claimantPage.getByTestId('claim-decision-amount')).toHaveText('£1500.00');
  await expect(claimantPage.getByTestId('claim-decision-denied')).toHaveCount(0);
  await claimantContext.close();

  // Denial closure: the holder denies with a rationale, which becomes the remarks.
  const denyClaimantContext = await browser.newContext();
  const denyClaimantPage = await denyClaimantContext.newPage();
  watchClaimantWire(denyClaimantPage);
  await registerClaimant(denyClaimantPage);
  const deniedClaim = await fileFnolWithPhoto(denyClaimantPage);

  const denyHolder = await openClaimAsHolder(browser, deniedClaim);
  await denyHolder
    .getByTestId('detail-decision-rationale')
    .fill('Coverage excludes the reported damage.');
  await denyHolder.getByTestId('detail-deny').click();
  await expect(denyHolder.getByTestId('detail-decision-result')).toContainText('Denied');
  await expect(denyHolder.getByTestId('detail-decision-result')).toContainText('closed');
  await denyHolder.context().close();

  // The claimant reopens the closed claim: the denial + remarks are on the screen, no amount.
  await denyClaimantPage.goto('/claim/' + deniedClaim);
  await expect(denyClaimantPage.getByTestId('claim-status-page')).toBeVisible();
  await expect(denyClaimantPage.getByTestId('claim-status-state')).toHaveText('CLOSED');
  await expect(denyClaimantPage.getByTestId('claim-decision-denied')).toBeVisible();
  await expect(denyClaimantPage.getByTestId('claim-decision-remarks')).toHaveText(
    'Coverage excludes the reported damage.',
  );
  await expect(denyClaimantPage.getByTestId('claim-decision-amount')).toHaveCount(0);
  await denyClaimantContext.close();

  // Neither closed-claim response leaked an internal field.
  expect(leaked, `internal fields leaked on a closed claimant wire: ${leaked.join(', ')}`)
    .toEqual([]);
});
