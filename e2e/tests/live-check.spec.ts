import { expect, test } from '@playwright/test';

test('adjuster workspace: new next-step layout renders', async ({ browser }) => {
  test.setTimeout(120_000);
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  // sign in as adjuster.one via queue
  await page.goto('/queue');
  await expect(page).toHaveURL(/realms\/claims/);
  await page.locator('#username').fill('adjuster.one');
  await page.locator('#password').fill(process.env.ADJUSTER_PASSWORD!);
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('queue-page')).toBeVisible();
  const row = page.getByTestId('queue-row').first();
  await expect(row).toBeVisible({ timeout: 30000 });
  await row.getByTestId('queue-open-claim').click();
  await expect(page.getByTestId('claim-detail-page')).toBeVisible();
  // new layout markers
  await expect(page.getByTestId('detail-documents-panel')).toBeVisible();
  await expect(page.getByTestId('detail-refer-panel')).toBeVisible();
  // no horizontal page overflow
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  console.log('OVERFLOW px:', overflow);
  expect(overflow).toBeLessThanOrEqual(1);
  await page.screenshot({ path: '/tmp/live-workspace.png' });
  await ctx.close();
});
