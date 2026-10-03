import { Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ensureAuthenticated, isAuthenticated } from '../auth/auth.service';

/**
 * Public pre-login landing screen. It makes no authenticated API calls and does
 * not initialise Keycloak until the visitor asks to sign in, so a first-time
 * visitor sees this page immediately with no redirect to the identity provider.
 *
 * Auth flow:
 *  - "Sign in" runs ensureAuthenticated() — the existing init-then-login path
 *    used by every route guard — so nothing about token acquisition changes.
 *  - Keycloak returns to this same route with `code` + `state` query params;
 *    ngOnInit detects that callback, re-runs ensureAuthenticated() (which
 *    exchanges the code via the existing check-sso init) and continues to the
 *    app's normal first screen (/home).
 */
@Component({
  selector: 'app-landing',
  styleUrl: './landing.css',
  templateUrl: './landing.html',
})
export class Landing implements OnInit {
  private readonly router = inject(Router);

  /** True while a sign-in is in flight, to debounce the button. */
  protected readonly signingIn = signal(false);

  ngOnInit(): void {
    // Returning from a Keycloak login: finish the token exchange and move on.
    // Also redirect a user who is already signed in within this page session
    // (e.g. clicked the brand or a "Home" link) straight past the landing.
    if (this.hasLoginCallback() || isAuthenticated()) {
      void this.signIn();
    }
  }

  async signIn(): Promise<void> {
    this.signingIn.set(true);
    try {
      const ok = await ensureAuthenticated();
      if (ok) {
        await this.router.navigate(['/home']);
      }
    } finally {
      this.signingIn.set(false);
    }
  }

  /**
   * Detect the return leg of a Keycloak login.
   *
   * The response is NOT in the query string: keycloak-js requests
   * `response_mode=fragment`, so `code` and `state` arrive in the URL *fragment*
   * (`#code=...&state=...`). Reading `location.search` alone therefore never
   * matched, the exchange was never triggered, and a visitor who signed in
   * landed back here still unauthenticated. Check both places.
   */
  private hasLoginCallback(): boolean {
    const has = (p: URLSearchParams) => p.has('code') && p.has('state');
    const query = new URLSearchParams(window.location.search);
    const fragment = new URLSearchParams(window.location.hash.replace(/^#/, ''));
    return has(query) || has(fragment);
  }
}
