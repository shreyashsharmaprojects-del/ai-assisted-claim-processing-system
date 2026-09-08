import { Component, OnDestroy, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { isAuthenticated, onSessionChange, sessionState } from '../auth/auth.service';

/**
 * Role-aware landing page. It fetches no data: the old whole-book policy table
 * (GET /api/policies — every customer's number + holder name) was a privacy leak
 * and is now supervisor-only, so home shows no customer data at all.
 *
 * Each audience gets exactly its own surface (separation of concern; the route
 * guards enforce the same split): anonymous visitors get the claimant acquisition
 * path, claimants get their own quick links, adjusters get a staff card pointing
 * at the queue with no filing/tracking CTAs, and supervisors get oversight links.
 */
@Component({
  imports: [RouterLink],
  selector: 'app-home',
  styleUrl: './home.css',
  templateUrl: './home.html',
})
export class Home implements OnDestroy {
  private readonly stopListening: () => void;

  /** Re-render the chrome when the session changes (sign-in/out, expiry). */
  protected readonly sessionVersion = signal(0);

  constructor() {
    this.stopListening = onSessionChange(() => {
      this.sessionVersion.update((v) => v + 1);
    });
  }

  ngOnDestroy(): void {
    this.stopListening();
  }

  protected isSignedIn(): boolean {
    this.sessionVersion();
    return isAuthenticated();
  }

  /** Supervisors get the oversight card (operational role wins over claimant). */
  protected isSupervisor(): boolean {
    this.sessionVersion();
    return sessionState().roles.includes('supervisor');
  }

  /** Adjusters and supervisors: operations staff, never claimant CTAs. */
  protected isInternal(): boolean {
    this.sessionVersion();
    const roles = sessionState().roles;
    return (
      roles.includes('adjuster_l1') || roles.includes('adjuster_l2')
      || roles.includes('adjuster_l3') || roles.includes('supervisor')
    );
  }

  /** Claimants without an operational role: their own workspace only. */
  protected isClaimant(): boolean {
    this.sessionVersion();
    return sessionState().roles.includes('claimant') && !this.isInternal();
  }
}
