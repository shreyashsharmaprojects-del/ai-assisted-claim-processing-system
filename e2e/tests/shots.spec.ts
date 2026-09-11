/** Comprehensive screenshot run for the sales demo PDF. Real app, real data — no mocks.
 *
 * Uses the LIVE dev stack (frontend :4200 dev proxy -> backend :8081 -> dev `claims`
 * DB, freshly demo-seeded) rather than the hermetic E2E stack, because the demo seed
 * lives in the dev DB by design. Keycloak :8090 provides SSO.
 *
 * Coverage (every built flow, happy path + edge cases):
 *  claimant  — landing/home, FNOL step 1, FNOL retired-policy error, FNOL step 2
 *              (incl. above-limit hint), FNOL duplicate 409, instant confirmation,
 *              open-claim tracker, NEED_INFO tracker state, denied tracker,
 *              partially-approved tracker (per-cover outcomes + net payable),
 *              my-claims history (+ Approved filter), cockpit list + policy detail
 *  adjuster  — queue (+ Under-review / Escalated / Breaching tabs, search, sort),
 *              claim work surface (reserve + notes + photos panel + coverage JSON),
 *              review triage panel, verification history, send-back (NEED_INFO)
 *              confirmation, NEED_INFO banner state, legacy single-figure decision,
 *              staged cover grid + assessment + gate banner + refer box,
 *              closed-claim read-only view, audit trail, 404-for-others'-claim
 *  supervisor— overview aggregates, outbox (+ FAILED filter + retry),
 *              escalations queue, policy book (+ create form + import preview),
 *              authority ladder editor
 *
 * Viewport 1440x900 (fits A4 panels at ~2x crisply). Screenshots land in
 * e2e/shots/ (regenerable; the names double as the PDF build's contract — keep
 * them stable). Run AFTER `npm run demo:seed`:
 *   npx playwright test --config shots.config.ts
 *
 * Live transitions the run performs (all on throwaway shot-filed claims, all
 * cleaned by `npm run demo:reset` via the `shot-%` marker — never on demo rows):
 *  - files three real multi-cover FNOLs (covers picker path)
 *  - refiles the same cover set (duplicate 409), files on a RETIRED policy (error)
 *  - sends claim A back to the claimant (NEED_INFO) from REVIEW
 *  - rejects claim C at review (DENIED close with rationale)
 *  - drives claim B end to end: advance REVIEW -> VERIFICATION, opens +
 *    completes a DIGITAL verification, saves the assessment, submits a split
 *    cover decision (APPROVE + REJECT within limit → PARTIALLY_APPROVED close)
 */
import { expect, test } from '@playwright/test';
import type { Browser, BrowserContext, Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

function loadEnvFile(): void {
  try {
    const raw = readFileSync(resolve(process.cwd(), '../.env'), 'utf8');
    for (const line of raw.split('\n')) {
      const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$/);
      if (match && process.env[match[1]] === undefined) {
        process.env[match[1]] = match[2].replace(/^["']|["']$/g, '');
      }
    }
  } catch {
    // CI injects real env vars instead.
  }
}
loadEnvFile();

function requiredEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set — copy .env.example to .env (or set it in CI).`);
  return value;
}

const stamp = Date.now();
const claimantUser = `shotclaim${stamp}`;
const claimantEmail = `${claimantUser}@example.test`;
// Per-run loss date (past, unique per day-bucket): the duplicate-FNOL guard
// rejects same policy + date + cover set within 24h, and the dev DB persists.
const shotLossDate = new Date(Date.UTC(2020, 0, 1) + (Math.floor(stamp / 1000) % 2000) * 86400000)
  .toISOString()
  .slice(0, 10);

async function registerClaimant(page: Page): Promise<void> {
  await page.goto('/claim/new');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.getByRole('link', { name: 'Register' }).click();
  await expect(page.locator('#firstName')).toBeVisible();
  await page.locator('#firstName').fill('Shot');
  await page.locator('#lastName').fill('Claimant');
  await page.locator('#email').fill(claimantEmail);
  await page.locator('#username').fill(claimantUser);
  await page.locator('#password').fill('claims-Pass-123');
  await page.locator('#password-confirm').fill('claims-Pass-123');
  await page.getByRole('button', { name: 'Register' }).click();
  await expect(page.getByTestId('fnol-policy-number')).toBeVisible();
}

async function signIn(page: Page, username: string, password: string, landingTestId: string, landingUrl = '/queue'): Promise<void> {
  await page.goto(landingUrl);
  await expect(page).toHaveURL(/realms\/claims/);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await expect(page.getByTestId(landingTestId)).toBeVisible({ timeout: 30000 });
}

/** Settle helper: wait until neither the loading skeleton nor the error panel shows. */
async function settled(page: Page, loadingId: string, errorId: string): Promise<void> {
  await expect(page.getByTestId(loadingId)).toHaveCount(0, { timeout: 30000 });
  await expect(page.getByTestId(errorId)).toHaveCount(0);
}

async function newCtx(browser: Browser): Promise<{ ctx: BrowserContext; page: Page }> {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  return { ctx, page: await ctx.newPage() };
}

test('capture demo screenshots against the live seeded stack', async ({ browser }) => {
  // ================= Claimant: FNOL incl. guards =================
  const claimantCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const claimant = await claimantCtx.newPage();

  // 00 — public landing page (before registration).
  await claimant.goto('/');
  await expect(claimant.getByTestId('home-file-claim')).toBeVisible({ timeout: 30000 });
  await claimant.screenshot({ path: 'shots/00-home.png' });

  await registerClaimant(claimant);

  // 01 — FNOL step 1 against the seeded book policy.
  await claimant.getByTestId('fnol-policy-number').fill('POL-10001');
  await claimant.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await claimant.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await claimant.screenshot({ path: 'shots/01-fnol-step1.png' });

  // 02 — FNOL error path: filing identity against a RETIRED policy. Step 1 is
  // client-side only (the check fires at submit), so walk to step 2, fill the
  // loss, submit — the policy-mismatch error names no internals (no signal
  // whether the number exists, the holder mismatched, or it is retired).
  await claimant.getByTestId('fnol-policy-number').fill('POL-30007');
  await claimant.getByTestId('fnol-holder-name').fill('Retired Holder');
  await claimant.getByTestId('fnol-holder-email').fill('retired.holder@example.test');
  await claimant.getByTestId('fnol-next').click();
  await expect(claimant.getByTestId('fnol-loss-date')).toBeVisible();
  await claimant.getByTestId('fnol-loss-date').fill(shotLossDate);
  await claimant.getByTestId('fnol-loss-location').fill('London');
  await claimant.getByTestId('fnol-loss-description').fill('Shot demo: retired-policy probe that must not file.');
  await claimant.getByTestId('fnol-submit').click();
  await expect(claimant.getByTestId('fnol-error')).toBeVisible();
  await claimant.screenshot({ path: 'shots/02-fnol-retired-error.png' });

  // Back to step 1 with the valid policy and through to step 2.
  await claimant.getByTestId('fnol-back').click();
  await claimant.getByTestId('fnol-policy-number').fill('POL-10001');
  await claimant.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await claimant.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await claimant.getByTestId('fnol-next').click();
  await expect(claimant.getByTestId('fnol-covers')).toBeVisible();

  // 03 — FNOL step 2 with the multi-cover picker (HLTH-PLUS 5-cover set),
  // including the above-limit hint: OPD 40k vs its 30k sub-limit files fine
  // and is flagged for the adjuster instead of blocked.
  await claimant.getByTestId('fnol-cover-HOSPITALIZATION').check();
  await claimant.getByTestId('fnol-amount-HOSPITALIZATION').fill('300000');
  await claimant.getByTestId('fnol-cover-DAYCARE').check();
  await claimant.getByTestId('fnol-amount-DAYCARE').fill('40000');
  await claimant.getByTestId('fnol-cover-OPD').check();
  await claimant.getByTestId('fnol-amount-OPD').fill('40000');
  await claimant.getByTestId('fnol-loss-date').fill(shotLossDate);
  await claimant.getByTestId('fnol-loss-location').fill('London');
  await claimant.getByTestId('fnol-loss-description').fill('Shot demo: gallbladder surgery with daycare follow-ups.');
  await claimant.screenshot({ path: 'shots/03-fnol-step2.png' });
  await claimant.getByTestId('fnol-submit').click();

  // 04 — instant claim-number confirmation.
  await expect(claimant.getByTestId('claim-number')).toBeVisible();
  const claimNumber = (await claimant.getByTestId('claim-number').textContent())!.trim();
  await expect(claimant.getByTestId('claim-steps')).toContainText('Under review');
  await claimant.screenshot({ path: 'shots/04-fnol-confirmation.png' });

  // 05 — duplicate filing: same policy + loss date + cover set within 24h
  // returns the existing number (409) instead of a second claim.
  await claimant.goto('/claim/new');
  await expect(claimant.getByTestId('fnol-policy-number')).toBeVisible();
  await claimant.getByTestId('fnol-policy-number').fill('POL-10001');
  await claimant.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await claimant.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await claimant.getByTestId('fnol-next').click();
  await expect(claimant.getByTestId('fnol-covers')).toBeVisible();
  await claimant.getByTestId('fnol-cover-HOSPITALIZATION').check();
  await claimant.getByTestId('fnol-amount-HOSPITALIZATION').fill('300000');
  await claimant.getByTestId('fnol-cover-DAYCARE').check();
  await claimant.getByTestId('fnol-amount-DAYCARE').fill('40000');
  await claimant.getByTestId('fnol-cover-OPD').check();
  await claimant.getByTestId('fnol-amount-OPD').fill('40000');
  await claimant.getByTestId('fnol-loss-date').fill(shotLossDate);
  await claimant.getByTestId('fnol-loss-location').fill('London');
  await claimant.getByTestId('fnol-loss-description').fill('Shot demo: gallbladder surgery with daycare follow-ups.');
  await claimant.getByTestId('fnol-submit').click();
  await expect(claimant.getByTestId('fnol-error')).toContainText(claimNumber);
  await claimant.screenshot({ path: 'shots/05-fnol-duplicate.png' });

  // 06 — claimant's own status screen: steps, filed covers, never internals.
  await claimant.goto(`/claim/${claimNumber}`);
  await expect(claimant.getByTestId('claim-status-page')).toBeVisible();
  await claimant.screenshot({ path: 'shots/06-claimant-status.png' });

  // 07 — claimant history (the fresh filing is the only row).
  await claimant.goto('/claims');
  await expect(claimant.getByTestId('my-claims-page')).toBeVisible();
  await settled(claimant, 'my-claims-loading', 'my-claims-error');
  await claimant.screenshot({ path: 'shots/07-my-claims.png' });

  // 08 — cockpit empty state for a fresh claimant (owns no policy yet; the
  // empty state itself proves the wall — never another holder's rows).
  // (Claimant context stays open: claims B and C are filed from it below, and
  // the tracker/history shots read back through it.)
  await claimant.goto('/policies');
  await expect(claimant.getByTestId('cockpit-page')).toBeVisible();
  await settled(claimant, 'cockpit-loading', 'cockpit-error');
  await claimant.screenshot({ path: 'shots/08-cockpit-empty.png' });

  // Two signed-in L1 adjusters (Priya = adjuster.one, Aisha = adjuster.four):
  // FNOL load-balances to the least-loaded eligible, so a live filing may land
  // on either. openLiveClaim finds whichever queue holds the row.
  const l1aCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const l1a = await l1aCtx.newPage();
  await signIn(l1a, 'adjuster.one', requiredEnv('ADJUSTER_PASSWORD'), 'queue-page');
  const l1bCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const l1b = await l1bCtx.newPage();
  await signIn(l1b, 'adjuster.four', requiredEnv('ADJUSTER_PASSWORD'), 'queue-page');

  /** Open whichever L1 queue holds the live-filed row; returns that page.
   * Matches by exact claim number — descriptions repeat across runs. */
  async function openLiveClaim(claimNumber: string): Promise<Page> {
    for (const cand of [l1a, l1b]) {
      await cand.goto('/queue');
      await expect(cand.getByTestId('queue-page')).toBeVisible();
      const row = cand.getByTestId('queue-row').filter({ hasText: claimNumber });
      try {
        await expect(row).toHaveCount(1, { timeout: 8000 });
        await row.getByTestId('queue-open-claim').click();
        await expect(cand.getByTestId('claim-detail-page')).toBeVisible();
        return cand;
      } catch {
        // Not this adjuster's queue — try the other.
      }
    }
    throw new Error(`live claim row not found in either L1 queue: ${claimNumber}`);
  }

  // ================= Live send-back → NEED_INFO tracker =================
  // Park claim A (Gallbladder 3-cover) with the claimant, straight from REVIEW.
  {
    const holder = await openLiveClaim(claimNumber);
    await expect(holder.getByTestId('detail-review-panel')).toBeVisible();
    await holder.getByTestId('detail-review-need-info').click();
    await holder.getByTestId('detail-review-requested-items')
      .fill('Shot demo: please attach the itemised final bill and discharge summary.');
    // 23 — the Ask-claimant form state, filled, before sending.
    await holder.screenshot({ path: 'shots/23-sendback-form.png' });
    await holder.getByTestId('detail-review-need-info-confirm').click();
    await expect(holder.getByTestId('detail-need-info-banner')).toBeVisible();
    // 24 — NEED_INFO banner state: parked with the claimant, actions locked.
    await holder.screenshot({ path: 'shots/24-need-info-banner.png' });
  }

  // 10 — NEED_INFO tracker, read back as the claimant who filed it.
  await claimant.goto(`/claim/${claimNumber}`);
  await expect(claimant.getByTestId('claim-status-page')).toBeVisible();
  await claimant.waitForTimeout(400);
  await claimant.screenshot({ path: 'shots/10-tracker-need-info.png' });

  // ================= Live reject → DENIED tracker =================
  // File claim C (OPD-only 5k) and reject it at review with a rationale.
  await claimant.goto('/claim/new');
  await expect(claimant.getByTestId('fnol-policy-number')).toBeVisible();
  await claimant.getByTestId('fnol-policy-number').fill('POL-10001');
  await claimant.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await claimant.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await claimant.getByTestId('fnol-next').click();
  await expect(claimant.getByTestId('fnol-covers')).toBeVisible();
  await claimant.getByTestId('fnol-cover-OPD').check();
  await claimant.getByTestId('fnol-amount-OPD').fill('5000');
  await claimant.getByTestId('fnol-loss-date').fill(shotLossDate);
  await claimant.getByTestId('fnol-loss-location').fill('Mumbai');
  await claimant.getByTestId('fnol-loss-description').fill('Shot demo: denied case, spectacles not covered.');
  await claimant.getByTestId('fnol-submit').click();
  await expect(claimant.getByTestId('claim-number')).toBeVisible();
  const claimC = (await claimant.getByTestId('claim-number').textContent())!.trim();

  // 09 — open tracker first (UNDER_REVIEW, still the claimant's to watch)…
  await claimant.goto(`/claim/${claimC}`);
  await expect(claimant.getByTestId('claim-status-page')).toBeVisible();
  await claimant.waitForTimeout(400);
  await claimant.screenshot({ path: 'shots/09-tracker-open.png' });

  // …then the live review-reject closes it as DENIED.
  {
    const holder = await openLiveClaim(claimC);
    await holder.getByTestId('detail-review-reject').click();
    await holder.getByTestId('detail-review-rationale')
      .fill('Shot demo: spectacles are excluded under the OPD cover wording.');
    await holder.getByTestId('detail-review-reject-confirm').click();
    await expect(holder.getByTestId('detail-decision-closed')).toBeVisible();
  }

  // 11 — DENIED tracker (closed, remarks visible).
  await claimant.goto(`/claim/${claimC}`);
  await expect(claimant.getByTestId('claim-status-page')).toBeVisible();
  await claimant.waitForTimeout(400);
  await claimant.screenshot({ path: 'shots/11-tracker-denied.png' });

  // ================= Live split close → PARTIAL tracker =================
  // File claim B (HOSP 80k + DAYCARE 20k) and drive it end to end.
  await claimant.goto('/claim/new');
  await expect(claimant.getByTestId('fnol-policy-number')).toBeVisible();
  await claimant.getByTestId('fnol-policy-number').fill('POL-10001');
  await claimant.getByTestId('fnol-holder-name').fill('Ada Lovelace');
  await claimant.getByTestId('fnol-holder-email').fill('ada.lovelace@example.test');
  await claimant.getByTestId('fnol-next').click();
  await expect(claimant.getByTestId('fnol-covers')).toBeVisible();
  await claimant.getByTestId('fnol-cover-HOSPITALIZATION').check();
  await claimant.getByTestId('fnol-amount-HOSPITALIZATION').fill('80000');
  await claimant.getByTestId('fnol-cover-DAYCARE').check();
  await claimant.getByTestId('fnol-amount-DAYCARE').fill('20000');
  await claimant.getByTestId('fnol-loss-date').fill(shotLossDate);
  await claimant.getByTestId('fnol-loss-location').fill('Pune');
  await claimant.getByTestId('fnol-loss-description').fill('Shot demo: split case for the partial close.');
  await claimant.getByTestId('fnol-submit').click();
  await expect(claimant.getByTestId('claim-number')).toBeVisible();
  const claimB = (await claimant.getByTestId('claim-number').textContent())!.trim();

  // REVIEW triage on claim B…
  const decider = await openLiveClaim(claimB);
  // 21 — REVIEW triage: stepper on Review, per-cover claimed vs sub-limit with
  // the Above-limit flag on OPD, advance/reject/send-back actions.
  await expect(decider.getByTestId('detail-review-panel')).toBeVisible();
  await decider.screenshot({ path: 'shots/21-review-triage.png' });
  await decider.getByTestId('detail-review-rationale')
    .fill('Shot demo: valid policy, covers confirmed, advancing to verification.');
  await decider.getByTestId('detail-review-advance').click();
  await expect(decider.getByTestId('detail-verification-panel')).toBeVisible();

  // …VERIFICATION: the default checklist (physical, document, clause) is
  // already open — complete every row (the extra follow-up form stays
  // collapsed; completing all rows unlocks assessment).
  await expect(decider.getByTestId('detail-verification-item')).toHaveCount(3);
  for (let round = 0; round < 4; round += 1) {
    const items = decider.getByTestId('detail-verification-item');
    const n = await items.count();
    let progressed = false;
    for (let k = 0; k < n; k += 1) {
      const row = items.nth(k);
      if ((await row.getByTestId(/^detail-ver-outcome-/).count()) === 1) {
        await row.getByTestId(/^detail-ver-outcome-/).selectOption('PASSED');
        await row.getByTestId(/^detail-ver-notes-/).fill('Shot demo: checked and passed.');
        await row.getByTestId(/^detail-ver-save-/).click();
        await decider.waitForTimeout(1000);
        progressed = true;
      }
    }
    if (!progressed) break;
  }
  // 22 — VERIFICATION with the completed record on file.
  await decider.screenshot({ path: 'shots/22-verification.png' });

  // …ASSESSMENT: per-cover inputs + rationale, totals line…
  await decider.getByTestId('detail-assess-HOSPITALIZATION').fill('75000');
  await decider.getByTestId('detail-assess-DAYCARE').fill('18000');
  await decider.getByTestId('detail-assessment-rationale').fill('Shot demo: assessed within sub-limits.');
  // 25 — assessment grid filled, before saving.
  await decider.screenshot({ path: 'shots/25-assessment.png' });
  await decider.getByTestId('detail-save-assessment').click();
  // Real DECISION signal (the stepper li always exists — assert the current marker).
  await expect(decider.getByTestId('detail-stage-DECISION')).toHaveClass(/is-current/);

  // …SPLIT DECISION: approve HOSP 70k, reject DAYCARE with remarks (within the
  // L1 100k HLTH-PLUS limit — closes PARTIALLY_APPROVED, single payment).
  await decider.getByTestId('detail-approve-HOSPITALIZATION').fill('70000');
  await decider.getByTestId('detail-cover-decision-DAYCARE').selectOption('REJECTED');
  await decider.getByTestId('detail-cover-remarks-DAYCARE')
    .fill('Shot demo: daycare follow-ups unrelated to the admitted procedure.');
  await decider.getByTestId('detail-decision-rationale').fill('Shot demo: split outcome, hospital pays.');
  // 26 — cover decision grid filled, before submitting.
  await decider.screenshot({ path: 'shots/26-cover-decision.png' });
  await decider.getByTestId('detail-submit-decision').click();
  await decider.getByTestId('detail-submit-decision-confirm').click();
  await expect(decider.getByTestId('detail-decision-closed')).toBeVisible();

  // 27 — closed-claim read-only view (PARTIALLY_APPROVED, payment recorded).
  await decider.screenshot({ path: 'shots/27-closed-partial.png' });

  // 12 — PARTIALLY_APPROVED tracker (per-cover outcomes + net payable).
  await claimant.goto(`/claim/${claimB}`);
  await expect(claimant.getByTestId('claim-status-page')).toBeVisible();
  await claimant.waitForTimeout(400);
  await claimant.screenshot({ path: 'shots/12-tracker-partial.png' });

  // 13 — my-claims Approved filter (partial counts as approved; 3 filings now).
  await claimant.goto('/claims');
  await expect(claimant.getByTestId('my-claims-page')).toBeVisible();
  await settled(claimant, 'my-claims-loading', 'my-claims-error');
  await claimant.getByTestId('my-claims-filter-approved').click();
  await claimant.waitForTimeout(500);
  await claimant.screenshot({ path: 'shots/13-myclaims-approved.png' });
  await claimantCtx.close();
  await l1aCtx.close();
  await l1bCtx.close();

  // ================= Adjuster (L1): queue + work surface =================
  const adjCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const adj = await adjCtx.newPage();
  await signIn(adj, 'adjuster.one', requiredEnv('ADJUSTER_PASSWORD'), 'queue-page');
  await expect(adj.getByTestId('queue-row').first()).toBeVisible({ timeout: 30000 });
  await adj.screenshot({ path: 'shots/14-adjuster-queue.png' });

  // 15 — queue tabs: Under review.
  await adj.getByTestId('queue-filter-review').click();
  await adj.waitForTimeout(800);
  await adj.screenshot({ path: 'shots/15-queue-review-tab.png' });
  // 16 — queue tabs: Breaching SLA (seeded 6-day ESCALATED row is out of the
  // personal queue; the tab + SLA column still read honestly).
  await adj.getByTestId('queue-filter-breaching').click();
  await adj.waitForTimeout(800);
  await adj.screenshot({ path: 'shots/16-queue-breaching-tab.png' });
  // 17 — queue search narrows to one row.
  await adj.getByTestId('queue-filter-all').click();
  await adj.waitForTimeout(500);
  await adj.getByTestId('queue-search').fill('Storm tore ridge');
  await adj.waitForTimeout(800);
  await adj.screenshot({ path: 'shots/17-queue-search.png' });
  await adj.getByTestId('queue-search').fill('');
  await adj.waitForTimeout(500);

  // 18 — open the seeded L1 UNDER_REVIEW demo claim (reserve set): the top of
  // the work surface — banner, summary strip, stepper, review panel.
  // Server-side search isolates it first: the dev queue holds older residue.
  await adj.goto('/queue');
  await expect(adj.getByTestId('queue-page')).toBeVisible();
  await expect(adj.getByTestId('queue-row').first()).toBeVisible({ timeout: 30000 });
  await adj.getByTestId('queue-search').fill('Storm tore ridge');
  await adj.waitForTimeout(800);
  const seededRow = adj.getByTestId('queue-row').filter({ hasText: 'Storm tore ridge tiles' });
  if ((await seededRow.count()) === 1) {
    await seededRow.getByTestId('queue-open-claim').click();
    await expect(adj.getByTestId('claim-detail-page')).toBeVisible();
  } else {
    await adj.getByTestId('queue-open-claim').first().click();
    await expect(adj.getByTestId('claim-detail-page')).toBeVisible();
  }
  // Wait for content paint (the shell renders before the claim loads).
  await expect(adj.getByTestId('detail-loss-location')).toBeVisible({ timeout: 30000 });
  await adj.waitForTimeout(500);
  await adj.screenshot({ path: 'shots/18-claim-detail.png' });

  // 19 — reserve + notes panel (scroll into view).
  const reserveInput = adj.getByTestId('detail-reserve-input');
  if ((await reserveInput.count()) === 1) {
    await reserveInput.scrollIntoViewIfNeeded();
    await adj.waitForTimeout(400);
    await adj.screenshot({ path: 'shots/19-reserve-notes.png' });
  }

  // 20 — 404 for a colleague's claim (the wall, adjuster-to-adjuster):
  // adjuster.one opens the L2-held AUTO claim number via the seeded outbox row.
  await adjCtx.close();

  // ================= Adjuster: seeded verification + gate states =================
  // 29 — VERIFICATION history (seeded COMPLETE DIGITAL record, assessment gate).
  const verCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const ver = await verCtx.newPage();
  await signIn(ver, 'adjuster.four', requiredEnv('ADJUSTER_PASSWORD'), 'queue-page');
  await ver.goto('/queue');
  await expect(ver.getByTestId('queue-page')).toBeVisible();
  const verRow2 = ver.getByTestId('queue-row').filter({ hasText: 'Knee arthroscopy' });
  await expect(verRow2).toHaveCount(1);
  await verRow2.getByTestId('queue-open-claim').click();
  await expect(ver.getByTestId('claim-detail-page')).toBeVisible();
  await expect(ver.getByTestId('detail-verification-panel')).toBeVisible();
  await ver.screenshot({ path: 'shots/29-verification-history.png' });
  await verCtx.close();

  // 30 — DECISION gate (seeded above-authority proposals + explicit refer box).
  const gateCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const gate = await gateCtx.newPage();
  await signIn(gate, 'adjuster.six', requiredEnv('ADJUSTER_PASSWORD'), 'queue-page');
  await gate.goto('/queue');
  await expect(gate.getByTestId('queue-page')).toBeVisible();
  const gateRow = gate.getByTestId('queue-row').filter({ hasText: 'Liver transplant' });
  await expect(gateRow).toHaveCount(1);
  await gateRow.getByTestId('queue-open-claim').click();
  await expect(gate.getByTestId('claim-detail-page')).toBeVisible();
  await expect(gate.getByTestId('detail-gate-banner')).toBeVisible();
  await gate.getByTestId('detail-gate-banner').scrollIntoViewIfNeeded();
  await gate.waitForTimeout(400);
  await gate.screenshot({ path: 'shots/30-authority-gate.png' });
  await gateCtx.close();

  // 31 — PARTIALLY_APPROVED closure (seeded mixed outcome + single payment),
  // opened as the deciding L2 via the supervisor outbox claim number.
  const supNumCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const supNum = await supNumCtx.newPage();
  await supNum.goto('/overview');
  await expect(supNum).toHaveURL(/realms\/claims/);
  await supNum.locator('#username').fill('supervisor');
  await supNum.locator('#password').fill(requiredEnv('SUPERVISOR_PASSWORD'));
  await supNum.locator('#kc-login').click();
  await expect(supNum.getByTestId('overview-page')).toBeVisible({ timeout: 30000 });
  await settled(supNum, 'overview-loading', 'overview-error');
  const closedRow = supNum.getByTestId('outbox-row').filter({ hasText: 'demo.kabir@example.test' });
  await expect(closedRow).toHaveCount(1);
  const partNumber = ((await closedRow.getByTestId('outbox-claim').textContent()) ?? '').trim();
  await supNumCtx.close();

  const partCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const part = await partCtx.newPage();
  await signIn(part, 'adjuster.five', requiredEnv('ADJUSTER_PASSWORD'), 'queue-page');
  await part.goto(`/claims/${partNumber}`);
  await expect(part.getByTestId('claim-detail-page')).toBeVisible();
  await expect(part.getByTestId('detail-decision-closed')).toBeVisible();
  await part.getByTestId('detail-decision-closed').scrollIntoViewIfNeeded();
  await part.waitForTimeout(400);
  await part.screenshot({ path: 'shots/31-partial-approval.png' });
  await partCtx.close();

  // 32 — legacy single-figure decision (seeded no-cover claim, L1 within limit).
  const legCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const leg = await legCtx.newPage();
  await signIn(leg, 'adjuster.one', requiredEnv('ADJUSTER_PASSWORD'), 'queue-page');
  await leg.goto('/queue');
  await expect(leg.getByTestId('queue-page')).toBeVisible();
  await expect(leg.getByTestId('queue-row').first()).toBeVisible({ timeout: 30000 });
  await leg.getByTestId('queue-search').fill('Storm tore ridge');
  await leg.waitForTimeout(800);
  const legRow = leg.getByTestId('queue-row').filter({ hasText: 'Storm tore ridge tiles' });
  if ((await legRow.count()) === 1) {
    await legRow.getByTestId('queue-open-claim').click();
    await expect(leg.getByTestId('claim-detail-page')).toBeVisible();
    // Legacy single-figure form (cover-less claim) — must render, not skip.
    const legacyPanel = leg.getByTestId('detail-decision-panel');
    await expect(legacyPanel).toBeVisible();
    await legacyPanel.scrollIntoViewIfNeeded();
    await leg.waitForTimeout(400);
    await leg.screenshot({ path: 'shots/32-legacy-decision.png' });
  }
  await legCtx.close();

  // 33 — 404 for a colleague's claim (adjuster.one opens the L2-held AUTO row).
  {
    const { ctx, page } = await newCtx(browser);
    await signIn(page, 'adjuster.one', requiredEnv('ADJUSTER_PASSWORD'), 'queue-page');
    await page.goto('/claims/CLM-NOT-REAL-000');
    await expect(page.getByTestId('claim-detail-error')).toBeVisible();
    await page.waitForTimeout(800);
    await page.screenshot({ path: 'shots/33-not-found.png' });
    await ctx.close();
  }

  // ================= Supervisor =================
  const supCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const sup = await supCtx.newPage();
  await sup.goto('/overview');
  await expect(sup).toHaveURL(/realms\/claims/);
  await sup.locator('#username').fill('supervisor');
  await sup.locator('#password').fill(requiredEnv('SUPERVISOR_PASSWORD'));
  await sup.locator('#kc-login').click();
  await expect(sup.getByTestId('overview-page')).toBeVisible({ timeout: 30000 });
  await settled(sup, 'overview-loading', 'overview-error');
  await sup.screenshot({ path: 'shots/34-overview.png' });

  // 35 — outbox panel (SENT/FAILED/PENDING rows).
  const outbox = sup.getByTestId('outbox-panel');
  if ((await outbox.count()) === 1) {
    await outbox.scrollIntoViewIfNeeded();
    await sup.waitForTimeout(300);
    await sup.screenshot({ path: 'shots/35-outbox.png' });
  }

  // 36 — outbox FAILED filter (the retryable failure + Retry action).
  await sup.getByTestId('outbox-filter-failed').click();
  await sup.waitForTimeout(600);
  await sup.screenshot({ path: 'shots/36-outbox-failed.png' });
  await sup.getByTestId('outbox-filter-all').click();
  await sup.waitForTimeout(400);

  // 37 — escalations queue.
  await sup.goto('/escalations');
  await expect(sup.getByTestId('escalations-page')).toBeVisible();
  await settled(sup, 'esc-loading', 'esc-error');
  await sup.screenshot({ path: 'shots/37-escalations.png' });

  // 28 — audit trail + reassign (supervisor-only panels) on the live
  // split-closed claim: every transition with actor + rationale.
  await sup.goto(`/claims/${claimB}`);
  await expect(sup.getByTestId('claim-detail-page')).toBeVisible();
  await expect(sup.getByTestId('detail-audit-panel')).toBeVisible();
  await sup.getByTestId('detail-audit-panel').scrollIntoViewIfNeeded();
  await sup.waitForTimeout(400);
  await sup.screenshot({ path: 'shots/28-audit-trail.png' });

  // 38 — policy book.
  await sup.goto('/admin/policies');
  await expect(sup.getByTestId('policies-page')).toBeVisible();
  await settled(sup, 'policies-loading', 'policies-error');
  await sup.screenshot({ path: 'shots/38-policies.png' });

  // 39 — policy create form (scrolled into view).
  const createForm = sup.getByTestId('policy-create-form');
  if ((await createForm.count()) === 1) {
    await createForm.scrollIntoViewIfNeeded();
    await sup.waitForTimeout(400);
    await sup.screenshot({ path: 'shots/39-policy-create.png' });
  }

  // 40 — authority ladder editor.
  await sup.goto('/admin/authority');
  await expect(sup.getByTestId('auth-page')).toBeVisible();
  await settled(sup, 'auth-loading', 'auth-ladder-error');
  await sup.screenshot({ path: 'shots/40-authority.png' });
  await supCtx.close();

  // ================= Claimant cockpit (linked holder) =================
  // 41/42 — Ada owns POL-10001 by holder email, so signing in as the
  // pre-provisioned `ada.lovelace` claimant links her cockpit: cover list with
  // limits/claimed/remaining, then the HLTH-PLUS policy detail.
  const adaCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const ada = await adaCtx.newPage();
  await ada.goto('/claim/new');
  await expect(ada).toHaveURL(/realms\/claims/);
  await ada.locator('#username').fill('ada.lovelace');
  await ada.locator('#password').fill('claims-Pass-123');
  await ada.locator('#kc-login').click();
  await expect(ada.getByTestId('fnol-policy-number')).toBeVisible({ timeout: 30000 });
  await ada.goto('/policies');
  await expect(ada.getByTestId('cockpit-page')).toBeVisible();
  await settled(ada, 'cockpit-loading', 'cockpit-error');
  await ada.screenshot({ path: 'shots/41-cockpit.png' });
  const adaPolicy = ada.getByTestId('cockpit-open').first();
  if ((await adaPolicy.count()) === 1) {
    await adaPolicy.click();
    await expect(ada.getByTestId('policy-detail-page')).toBeVisible();
    await ada.waitForTimeout(500);
    await ada.screenshot({ path: 'shots/42-cockpit-policy.png' });
  }
  await adaCtx.close();
});
