import { expect, test } from '@playwright/test';

/**
 * Privacy + separation of concern on the landing page.
 *
 * Anonymous visitors see the claimant acquisition path and no customer data (the
 * whole-book policy table was removed: GET /api/policies is supervisor-only).
 * Guarded claimant links still trigger sign-in through their guards.
 */
test('skeleton page shows the claimant path and no customer data', async ({
  page,
}) => {
  await page.goto('/');

  await expect(page.getByTestId('home-file-claim')).toBeVisible();
  await expect(page.getByTestId('policy-list')).toHaveCount(0);
  await expect(page.getByTestId('policy-signin')).toHaveCount(0);
  await expect(page.getByTestId('home-work-queue')).toHaveCount(0);
  await expect(page.getByTestId('home-staff-panel')).toHaveCount(0);
});
