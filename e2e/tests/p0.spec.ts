import { expect, test } from '@playwright/test';
import { Browser, Page } from '@playwright/test';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';let registrationCounter = 1000;

/** Provisioned-realm passwords come from the environment (see .env.example -> .env). */
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
 * Signs in the provisioned supervisor (fixed realm subject; supervisors have no app_user
 * row — their authority is the Keycloak role alone) and lands on the escalation queue.
 * Copied from the queue.spec.ts pattern.
 */
async function signInSupervisor(page: Page): Promise<void> {
  await page.goto('/escalations');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.locator('#username').fill('supervisor');
  await page.locator('#password').fill(requiredEnv('SUPERVISOR_PASSWORD'));
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('escalations-page')).toBeVisible();
}

/**
 * Signs in a provisioned adjuster and lands on the queue page.
 * Copied from the queue.spec.ts pattern.
 */
async function signInAdjuster(page: Page, username: string): Promise<void> {
  await page.goto('/queue');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(requiredEnv('ADJUSTER_PASSWORD'));
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('queue-page')).toBeVisible();
}

/** True when the signed-in adjuster's queue shows exactly one row for the claim. */
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

/** Files an FNOL against an explicit policy and returns the claim number. */
async function fileFnol(
  page: Page,
  policyNumber: string,
  holderName: string,
  holderEmail: string,
): Promise<string> {
  await page.getByTestId('fnol-policy-number').fill(policyNumber);
  await page.getByTestId('fnol-holder-name').fill(holderName);
  await page.getByTestId('fnol-holder-email').fill(holderEmail);
  await page.getByTestId('fnol-next').click();
  await expect(page.getByTestId('fnol-loss-date')).toBeVisible();
  await page.getByTestId('fnol-loss-date').fill('2026-09-01');
  await page.getByTestId('fnol-loss-location').fill('London');
  await page.getByTestId('fnol-loss-description').fill('Kitchen flooded after a pipe burst.');
  await page.getByTestId('fnol-submit').click();
  await expect(page.getByTestId('claim-number')).toBeVisible();
  return ((await page.getByTestId('claim-number').textContent()) ?? '').trim();
}

/**
 * R1 policy-import journey: the supervisor imports the 3-row fixture CSV (2 valid, 1
 * bad product code), sees the per-row error, and a claimant files an FNOL against an
 * imported policy whose claim lands in an adjuster queue. Retire is exercised on the
 * second imported policy (kept out of FNOL so journey 9's AUTO assumptions hold).
 */
test(
  'R1 policy-import journey: supervisor imports 3-row CSV (1 bad), FNOL against imported policy lands in queue',
  async ({ browser }) => {
    const supervisorContext = await browser.newContext();
    const supervisorPage = await supervisorContext.newPage();
    await signInSupervisor(supervisorPage);

    await supervisorPage.goto('/admin/policies');
    await expect(supervisorPage.getByTestId('policies-page')).toBeVisible();
    await expect(supervisorPage.getByTestId('policies-table')).toBeVisible();

    // Unique suffix per run keeps the journey idempotent: a previous partial run
    // may already have imported the static POL-E2E-01/02 fixture rows, and reruns
    // would then see "already exists" instead of the expected 2-of-3 result.
    const stamp = String(Date.now() % 100000).padStart(5, '0');
    const policyA = `POL-E2E-${stamp}-01`;
    const policyB = `POL-E2E-${stamp}-02`;
    const runCsv = join(
      mkdtempSync(join(tmpdir(), 'policy-import-')),
      'policy-import.csv',
    );
    writeFileSync(
      runCsv,
      'policy_number,product_code,holder_name,holder_email,coverage\n' +
        `${policyA},PROP-HOME,Ada Lovelace,ada.lovelace@example.test,"{""type"": ""home""}"\n` +
        `${policyB},AUTO-STD,Grace Hopper,grace.hopper@example.test,"{""type"": ""auto""}"\n` +
        'POL-E2E-BAD,BOGUS,Bad Row,bad.row@example.test,\n',
    );
    await supervisorPage.getByTestId('policy-import-input').setInputFiles(runCsv);
    await expect(supervisorPage.getByTestId('policy-import-preview')).toBeVisible();
    await supervisorPage.getByTestId('policy-import-confirm').click();
    // Server result: 2 kept, the BOGUS row rejected (unknown product code). The
    // result notice renders under `policy-import-result`; the preview keeps its
    // own summary.
    await expect(supervisorPage.getByTestId('policy-import-result')).toContainText(
      'Imported 2 of 3 rows',
    );
    await expect(
      supervisorPage.getByTestId('policy-import-error-row').filter({ hasText: 'POL-E2E-BAD' }),
    ).toHaveCount(1);

    // The imported policy is listed (search narrows server-side).
    await supervisorPage.getByTestId('policies-search').fill(policyA);
    await expect(
      supervisorPage.getByTestId('policies-row').filter({ hasText: policyA }),
    ).toHaveCount(1);

    // Retire the second imported policy (stays readable, leaves AUTO/HOME routing alone).
    await supervisorPage.getByTestId('policies-search').fill('');
    await supervisorPage
      .getByTestId('policies-row')
      .filter({ hasText: policyB })
      .getByTestId(`policy-retire-${policyB}`)
      .click();
    await supervisorPage.getByTestId(`policy-retire-confirm-${policyB}`).click();
    await expect(
      supervisorPage.getByTestId('policies-row').filter({ hasText: policyB }),
    ).toContainText('RETIRED');
    await supervisorContext.close();

    // A fresh claimant files against the imported PROP-HOME policy; it routes L1.
    // (PROP-HOME is the V2 property product; HOME/AUTO rows no longer exist in
    // authority_config since V12 replaced them with the V2 catalog.)
    const claimantContext = await browser.newContext();
    const claimantPage = await claimantContext.newPage();
    await registerClaimant(claimantPage);
    const claimNumber = await fileFnol(
      claimantPage,
      policyA,
      'Ada Lovelace',
      'ada.lovelace@example.test',
    );
    await claimantContext.close();

    // The claim lands in exactly one L1 adjuster's queue, never the L2 queue.
    // V2-1 staff: three L1 adjusters — poll all three, exactly one must hold it.
    let l1Holds = 0;
    for (const username of ['adjuster.one', 'adjuster.two', 'adjuster.four']) {
      const holderContext = await browser.newContext();
      const holderPage = await holderContext.newPage();
      await signInAdjuster(holderPage, username);
      if (await queueShows(holderPage, claimNumber)) {
        l1Holds++;
      }
      await holderContext.close();
    }

    const l2Context = await browser.newContext();
    const l2Page = await l2Context.newPage();
    await signInAdjuster(l2Page, 'adjuster.three');
    const l2Holds = await queueShows(l2Page, claimNumber);
    await l2Context.close();

    expect(l1Holds).toBe(1);
    expect(l2Holds).toBe(false);
  },
);

/**
 * V2-1 cockpit journey: a freshly registered claimant owns no policy, so My
 * policies renders the empty state (never another holder's rows, never an error).
 * The owner path (Ada / POL-10001, 5-cover detail) is covered by
 * CockpitIntegrationTest (mine/detail/404) against the same seeded rows — the
 * E2E realm cannot mint Ada's email without breaking other journeys' isolation.
 */
test(
  'V2-1 cockpit journey: unlinked claimant sees the empty state, never another holder',
  async ({ browser }) => {
    const freshContext = await browser.newContext();
    const freshPage = await freshContext.newPage();
    await registerClaimant(freshPage);

    await freshPage.goto('/policies');
    await expect(freshPage.getByTestId('cockpit-page')).toBeVisible();
    await expect(
      freshPage.getByTestId('cockpit-error').or(freshPage.getByTestId('cockpit-empty')),
    ).toBeVisible();
    await expect(freshPage.getByTestId('cockpit-error')).toHaveCount(0);
    await expect(freshPage.getByTestId('cockpit-empty')).toBeVisible();
    await expect(freshPage.getByTestId('cockpit-empty')).toContainText('No policies found');
    await freshContext.close();
  },
);

/** Opens a claim as its current holder via the queue (mirrors queue.spec.ts). */
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
 * R2 outbox journey: after an adjuster approves a within-limit claim, the supervisor
 * sees the SENT decision row for it in the overview outbox panel.
 */
test(
  'R2 outbox journey: supervisor sees the SENT row in the outbox panel after a decision closure',
  async ({ browser }) => {
    // Fresh claim against the seeded HOME policy, approved within L1 limits.
    const claimantContext = await browser.newContext();
    const claimantPage = await claimantContext.newPage();
    await registerClaimant(claimantPage);
    const claimNumber = await fileFnol(
      claimantPage,
      'POL-10001',
      'Ada Lovelace',
      'ada.lovelace@example.test',
    );
    await claimantContext.close();

    const holder = await openClaimAsHolder(browser, claimNumber);
    await holder.getByTestId('detail-decision-amount').fill('1500.00');
    await holder.getByTestId('detail-decision-rationale').fill('Quotes verified; within authority.');
    await holder.getByTestId('detail-approve').click();
    await expect(holder.getByTestId('detail-decision-result')).toContainText('approved', {
      ignoreCase: true,
    });
    await holder.context().close();

    const supervisorContext = await browser.newContext();
    const supervisorPage = await supervisorContext.newPage();
    await signInSupervisor(supervisorPage);
    await supervisorPage.goto('/overview');
    await expect(supervisorPage.getByTestId('overview-page')).toBeVisible();
    await expect(supervisorPage.getByTestId('outbox-panel')).toBeVisible();
    // The decision row for the just-closed claim was delivered (immediate send +
    // dispatcher flush both mark SENT; the panel lists newest first).
    await supervisorPage.getByTestId('outbox-search').fill(claimNumber);
    await expect(
      supervisorPage.getByTestId('outbox-row').filter({ hasText: claimNumber }).first(),
    ).toBeVisible();
    await supervisorContext.close();
  },
);
