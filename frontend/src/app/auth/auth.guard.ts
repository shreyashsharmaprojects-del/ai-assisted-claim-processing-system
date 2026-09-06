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

/** Internal surfaces (the adjuster queue) are for provisioned staff roles only. */
export const internalGuard: CanActivateFn = async () => {
  const authenticated = await ensureAuthenticated();
  const internalRoles = ['adjuster_l1', 'adjuster_l2', 'supervisor'];
  if (!authenticated || !internalRoles.some(hasRole)) {
    const router = inject(Router);
    await router.navigate(['']);
    return false;
  }
  return true;
};
