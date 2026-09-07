import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { ensureAuthenticated, hasRole } from './auth.service';

/**
 * The /claim/new surface is for signed-in claimants only.
 *
 * `inject(Router)` is called at the top, before the `await`: functional guards only run
 * inside an injection context during their synchronous call, so `inject()` after an
 * `await` throws NG0203 on the redirect path.
 */
export const claimantGuard: CanActivateFn = async () => {
  const router = inject(Router);
  const authenticated = await ensureAuthenticated();
  if (!authenticated || !hasRole('claimant')) {
    await router.navigate(['']);
    return false;
  }
  return true;
};

/** Internal surfaces (the adjuster queue) are for provisioned staff roles only. */
export const internalGuard: CanActivateFn = async () => {
  const router = inject(Router);
  const authenticated = await ensureAuthenticated();
  const internalRoles = ['adjuster_l1', 'adjuster_l2', 'supervisor'];
  if (!authenticated || !internalRoles.some(hasRole)) {
    await router.navigate(['']);
    return false;
  }
  return true;
};

/** The escalation queue (/escalations) is supervisor-only (route-table row for slice 5). */
export const supervisorGuard: CanActivateFn = async () => {
  const router = inject(Router);
  const authenticated = await ensureAuthenticated();
  if (!authenticated || !hasRole('supervisor')) {
    await router.navigate(['']);
    return false;
  }
  return true;
};
