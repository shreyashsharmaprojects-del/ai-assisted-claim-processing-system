import { expect, test } from '@playwright/test';
import { Browser, Page } from '@playwright/test';

let registrationCounter = 9000;

/**
 * Per-run loss date (same scheme as ai-advisory.spec.ts/clauses.spec.ts): the
 * V2-2 duplicate guard is intentionally not claimant-scoped and the hermetic
 * claims_e2e DB persists across runs, so a fixed date would 409 on the
 * previous run's own filings.
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

async function registerClaimant(page: Page, prefix: string): Promise<void> {
  const stamp = Date.now() + '_' + registrationCounter++;
  const username = `${prefix}${stamp}`;
  const email = `${username}@example.test`;

  await page.goto('/claim/new');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();

  await page.locator('#firstName').fill('Chat');
  await page.locator('#lastName').fill('Test');
  await page.locator('#email').fill(email);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill('claims-Pass-123');
  await page.locator('#password-confirm').fill('claims-Pass-123');
  await page.getByRole('button', { name: 'Register' }).click();
  await expect(page.getByTestId('fnol-policy-number')).toBeVisible();
}

/** Files a MATERNITY-only claim on the seeded HLTH-PLUS policy (POL-10001) and returns its number. */
async function fileMaternityClaim(page: Page): Promise<string> {
  await page.getByTestId('fnol-policy-number').fill('POL-10001');
  await page.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await page.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await page.getByTestId('fnol-next').click();
  await expect(page.getByTestId('fnol-covers')).toBeVisible();

  await page.getByTestId('fnol-cover-MATERNITY').check();
  await page.getByTestId('fnol-amount-MATERNITY').fill('50000');
  await page.getByTestId('fnol-loss-date').fill(lossDate);
  await page.getByTestId('fnol-loss-location').fill('London');
  await page.getByTestId('fnol-loss-description').fill('Maternity admission and delivery.');
  await page.getByTestId('fnol-submit').click();

  await expect(page.getByTestId('claim-number')).toBeVisible();
  const claimNumber = ((await page.getByTestId('claim-number').textContent()) ?? '').trim();
  expect(claimNumber).toMatch(/CLM-\d{6}/);
  return claimNumber;
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

/**
 * AI chat at REVIEW: the conversational thread (`app-ai-chat`) renders at
 * every stage — including pre-DECISION review — while the per-cover advisory
 * artifact stays DECISION-gated. Asking about the waiting period yields an
 * assistant answer in the thread with a Live/Fallback status.
 *
 * All assertions via testids with Playwright expect-polling; no fixed sleeps.
 */
test('AI chat answers at review stage', async ({ browser }: { browser: Browser }) => {
  test.setTimeout(240_000);
  const claimantContext = await browser.newContext();
  const claimantPage = await claimantContext.newPage();
  await registerClaimant(claimantPage, 'aichat');
  const claimNumber = await fileMaternityClaim(claimantPage);
  await claimantContext.close();

  // Open as the holding L1 adjuster: still at REVIEW (pre-DECISION).
  const holder = await openClaimAsHolder(browser, claimNumber);
  await expect(holder.getByTestId('detail-review-panel')).toBeVisible();

  // (a) The chat thread opens from the collapsed AI control — the per-cover
  // advisory artifact stays locked until DECISION.
  await holder.getByTestId('detail-ai-toggle').click();
  await expect(holder.getByTestId('detail-ai-drawer')).toBeVisible();
  await expect(holder.getByTestId('detail-ai-chat')).toBeVisible();
  await expect(holder.getByTestId('detail-ai')).toBeVisible();
  await expect(holder.getByTestId('detail-ai-MATERNITY')).toHaveCount(0);

  // (b) Ask a question: the user turn renders, then the assistant answers.
  const question = 'Does the waiting period apply here?';
  await holder.getByTestId('detail-ai-input').fill(question);
  await holder.getByTestId('detail-ai-send').click();
  await expect(holder.getByTestId('detail-ai-msg-0')).toContainText('waiting period', {
    timeout: 30_000,
  });
  await expect(holder.getByTestId('detail-ai-msg-1'), 'assistant answer renders').toBeVisible({
    timeout: 60_000,
  });
  await expect
    .poll(async () => (await holder.getByTestId('detail-ai-thread').textContent()) ?? '', {
      message: 'chat thread carries the assistant answer',
      timeout: 60_000,
    })
    .not.toHaveLength(0);
  await expect(holder.getByTestId('detail-ai-chat-error')).toHaveCount(0);

  // (c) The status names the serving path: Live on a COMPLETED turn,
  // Fallback on the rules-only turn (no live provider in this box).
  await expect(holder.getByTestId('detail-ai-chat-status')).toContainText(/Live|Fallback/i, {
    timeout: 60_000,
  });
  await holder.context().close();
});

/**
 * FNOL result checklist: filing the same MATERNITY-only claim renders the
 * required-documents tracker on the result card — the "N of M received" count
 * plus one row per expected document with a RECEIVED/PENDING status word. No
 * file uploads are attached, so PENDING rows are the expected assertion
 * (warn-not-block: missing docs never block filing).
 *
 * All assertions via testids with Playwright expect-polling; no fixed sleeps.
 */
test('FNOL result shows the required-documents checklist', async ({
  page,
}: {
  page: Page;
}) => {
  test.setTimeout(240_000);
  await registerClaimant(page, 'fnoldoc');

  await page.getByTestId('fnol-policy-number').fill('POL-10001');
  await page.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await page.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await page.getByTestId('fnol-next').click();
  await expect(page.getByTestId('fnol-covers')).toBeVisible();

  // The filing form hints that missing documents can be uploaded later.
  await expect(page.getByTestId('fnol-reqdocs-hint')).toBeVisible();

  await page.getByTestId('fnol-cover-MATERNITY').check();
  await page.getByTestId('fnol-amount-MATERNITY').fill('50000');
  await page.getByTestId('fnol-loss-date').fill(lossDate);
  await page.getByTestId('fnol-loss-location').fill('London');
  await page.getByTestId('fnol-loss-description').fill('Maternity admission and delivery.');
  await page.getByTestId('fnol-submit').click();

  await expect(page.getByTestId('claim-number')).toBeVisible();

  // The result card carries the required-documents checklist: the count line
  // plus at least one document row with a RECEIVED/PENDING status word.
  await expect(page.getByTestId('fnol-result-docs-count')).toContainText(
    /Documents: \d+ of \d+ received/,
  );
  const docs = page.getByTestId('fnol-result-doc');
  await expect.poll(() => docs.count(), { message: 'required-doc rows render' }).toBeGreaterThan(0);
  await expect(docs.first().getByTestId('fnol-result-doc-status')).toContainText(
    /received|pending|waived/i,
  );
});
