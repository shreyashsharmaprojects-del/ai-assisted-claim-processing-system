import { expect, test } from '@playwright/test';

test('skeleton page shows the seeded policy from the real database', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByTestId('policy-error')).toHaveCount(0);
  await expect(page.getByTestId('policy-list')).toBeVisible();

  // The seeded row (V2__seed_policy.sql) must be present and render the right data.
  // Deliberately no exact row-count assertion: later slices add more seed policies.
  const seedRow = page.getByTestId('policy-row').filter({ hasText: 'POL-10001' });
  await expect(seedRow).toHaveCount(1);
  await expect(seedRow.getByTestId('policy-number')).toHaveText('POL-10001');
  await expect(seedRow.getByTestId('policy-holder')).toHaveText('Ada Lovelace');
});
