import { expect, test } from '@playwright/test';

/**
 * Skeleton page: anonymous visitors see the marketing banner and the sign-in prompt for
 * the policy reference (holder names are personal data — the list needs a session).
 * The primary CTAs stay usable: filing a claim triggers sign-in through the guard.
 */
test('skeleton page invites anonymous visitors to sign in for the policy reference', async ({
  page,
}) => {
  await page.goto('/');

  await expect(page.getByTestId('policy-signin')).toBeVisible();
  await expect(page.getByTestId('policy-list')).toHaveCount(0);
  await expect(page.getByTestId('home-file-claim')).toBeVisible();
});
