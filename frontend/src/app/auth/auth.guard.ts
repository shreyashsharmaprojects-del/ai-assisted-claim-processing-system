import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { ensureAuthenticated, hasRole } from './auth.service';

/** The /claim/new surface is for signed-in claimants only. */
export const claimantGuard: CanActivateFn = async () => {
  const authenticated = await ensureAuthenticated();
  if (!authenticated || !hasRole('claimant')) {
    const router = inject(Router);
    await router.navigate(['']);
    return false;
  }
  return true;
};
