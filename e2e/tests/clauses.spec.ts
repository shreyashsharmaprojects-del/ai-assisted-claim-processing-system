import { expect, test } from '@playwright/test';
import { Browser, Page } from '@playwright/test';

let registrationCounter = 7000;

/**
 * Per-run loss date, unique to the second: base 2020-01-01 plus
 * (epochSeconds mod 2000) days — always past, always within the 10-year window.
 * Needed because the V2-2 duplicate guard (same policy + loss date + cover set
 * < 24h) is intentionally NOT claimant-scoped, and the hermetic claims_e2e DB
 * persists across runs — a fixed date trips on the previous run's own filings.
 */
const runDate = new Date(Date.UTC(2020, 0, 1)
  + (Math.floor(Date.now() / 1000) % 2000) * 86_400_000);
const lossDate = runDate.toISOString().slice(0, 10);

/** Registers a fresh claimant through the real Keycloak realm and returns to the FNOL form. */
async function registerClaimant(page: Page): Promise<void> {
  const stamp = Date.now() + '_' + registrationCounter++;
  const username = `clauseclaim${stamp}`;
  const email = `${username}@example.test`;

  await page.goto('/claim/new');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();

  await page.locator('#firstName').fill('Clause');
  await page.locator('#lastName').fill('Test');
  await page.locator('#email').fill(email);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill('claims-Pass-123');
  await page.locator('#password-confirm').fill('claims-Pass-123');
  await page.getByRole('button', { name: 'Register' }).click();
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

/**
 * Clause-panel journey: a maternity-only filing on the seeded HLTH-PLUS policy
 * (POL-10001) drives the adjuster's clause panel. The panel must show the
 * section, list the claim's own MATERNITY-bound clause (4.5), omit
 * HOSPITALIZATION-bound clauses (4.1) the claim does not have, and expand a
 * clause to reveal its full wording — all via testids, with Playwright
 * expect-polling instead of fixed sleeps.
 */
test('adjuster sees only the claim covers\u2019 clauses and can expand the wording', async ({
  page,
  browser,
}) => {
  // (1) File a maternity-only claim so cover scoping is observable: a
  // MATERNITY-bound clause must appear while HOSPITALIZATION-bound rows stay out.
  await registerClaimant(page);
  await page.getByTestId('fnol-policy-number').fill('POL-10001');
  await page.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await page.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await page.getByTestId('fnol-next').click();
  await expect(page.getByTestId('fnol-loss-date')).toBeVisible();

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

  // (2) Open the claim as the holding L1 adjuster, capturing the clause payload
  // on the wire to cross-check cover scoping beyond the sampled rows below.
  const clausePayloads: Array<Array<{ coverCode: string | null }>> = [];
  const holder = await openClaimAsHolder(browser, claimNumber);
  holder.on('response', async (response) => {
    if (
      response.request().method() === 'GET'
      && /\/api\/claims\/CLM-\d+\/policy-clauses/.test(response.url())
    ) {
      try {
        clausePayloads.push(await response.json());
      } catch {
        // Non-JSON (e.g. an error page) — the UI assertions below cover it.
      }
    }
  });
  await holder.reload();
  await expect(holder.getByTestId('claim-detail-page')).toBeVisible();

  // (a) The clause section renders on the adjuster workspace.
  const panel = holder.getByTestId('detail-clauses');
  await expect(panel).toBeVisible();
  await expect(holder.getByTestId('detail-clauses-error')).toHaveCount(0);

  // Product-level rows are always in scope for the claim's product.
  await expect(holder.getByTestId('detail-clause-1.1')).toBeVisible();

  // (b) Cover scoping: the MATERNITY-bound sub-limit row appears with its own
  // title, while HOSPITALIZATION/OPD-bound rows the claim does not have stay out.
  const maternityRow = holder.getByTestId('detail-clause-4.5');
  await expect(maternityRow).toBeVisible();
  await expect(maternityRow).toContainText('Maternity sub-limit');
  await expect(holder.getByTestId('detail-clause-4.1')).toHaveCount(0);
  await expect(holder.getByTestId('detail-clause-4.4')).toHaveCount(0);

  // Wire cross-check: every served row is product-level or MATERNITY-bound,
  // and at least one MATERNITY-bound row was served.
  await expect
    .poll(() => clausePayloads.length, { message: 'policy-clauses response observed' })
    .toBeGreaterThan(0);
  const served = clausePayloads[clausePayloads.length - 1];
  expect(served.some((row) => row.coverCode === 'MATERNITY')).toBe(true);
  expect(
    served.filter((row) => row.coverCode !== null && row.coverCode !== 'MATERNITY'),
    'policy-clauses served covers outside the claim',
  ).toEqual([]);

  // (c) Expanding the row reveals the full wording text (collapsed by default).
  const toggle = maternityRow.getByTestId('detail-clause-toggle');
  await expect(toggle).toContainText('Show wording');
  await toggle.scrollIntoViewIfNeeded();
  await toggle.click();
  await expect(toggle).toContainText('Hide wording');
  await expect(maternityRow).toContainText('Twin deliveries');
});
