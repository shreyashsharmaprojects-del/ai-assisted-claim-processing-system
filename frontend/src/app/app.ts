import { Component, OnDestroy, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import {
  isAuthenticated,
  logout,
  onSessionChange,
  preferredUsername,
  sessionState,
} from './auth/auth.service';
import { Toasts } from './toasts';

@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App implements OnDestroy {
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);
  private readonly stopListening: () => void;

  /** Bump to re-render the chrome when the session changes (sign-in/out, expiry). */
  protected readonly sessionVersion = signal(0);

  constructor() {
    this.stopListening = onSessionChange(() => this.sessionVersion.update((v) => v + 1));
  }

  ngOnDestroy(): void {
    this.stopListening();
  }

  /** Supervisor-only nav links (the routes are guarded too). */
  protected isSupervisor(): boolean {
    this.sessionVersion();
    return sessionState().roles.includes('supervisor');
  }

  /** The adjuster queue link is internal-only (never advertised to the public). */
  protected isInternal(): boolean {
    this.sessionVersion();
    const roles = sessionState().roles;
    return (
      roles.includes('adjuster_l1') || roles.includes('adjuster_l2') || roles.includes('supervisor')
    );
  }

  /** Claimant-only nav (my claims history; the routes are guarded too). */
  protected isClaimant(): boolean {
    this.sessionVersion();
    return sessionState().roles.includes('claimant');
  }

  protected isSignedIn(): boolean {
    this.sessionVersion();
    return isAuthenticated();
  }

  protected username(): string | null {
    this.sessionVersion();
    return preferredUsername();
  }

  protected readonly toastItems = this.toasts.items;

  protected dismissToast(id: number): void {
    this.toasts.dismiss(id);
  }

  protected async signOut(): Promise<void> {
    await logout();
    this.toasts.info('You have been signed out.');
    await this.router.navigate(['/']);
  }
}
