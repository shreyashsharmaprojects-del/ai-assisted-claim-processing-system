import { Component, OnDestroy, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { Subscription } from 'rxjs';
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
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);
  private readonly stopListening: () => void;

  /** Bump to re-render the chrome when the session changes (sign-in/out, expiry). */
  protected readonly sessionVersion = signal(0);

  /**
   * Queue-depth badges: quiet counts beside My queue / Escalations so an operator
   * sees pressure before opening the list. Best-effort — a failed fetch hides the
   * badge instead of erroring the whole shell.
   */
  protected readonly queueBadge = signal<number | null>(null);
  protected readonly escalationBadge = signal<number | null>(null);

  /**
   * S10 (V25): claimant bell unread count. Rides on the
   * `GET /api/notifications/mine` envelope's `unread` field — no new endpoint.
   * Poll on navigation/app-load (no SSE); a failed fetch hides the badge.
   */
  protected readonly notifUnread = signal<number | null>(null);
  private readonly navSub: Subscription;

  constructor() {
    this.stopListening = onSessionChange(() => {
      this.sessionVersion.update((v) => v + 1);
      this.refreshBadges();
      this.refreshNotifUnread();
    });
    this.navSub = this.router.events.subscribe((event) => {
      if (event instanceof NavigationEnd) {
        this.refreshNotifUnread();
      }
    });
    this.refreshBadges();
    this.refreshNotifUnread();
  }

  ngOnDestroy(): void {
    this.stopListening();
    this.navSub.unsubscribe();
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
      roles.includes('adjuster_l1') || roles.includes('adjuster_l2')
      || roles.includes('adjuster_l3') || roles.includes('supervisor')
    );
  }

  /** Claimant-only nav (my claims history; the routes are guarded too). */
  protected isClaimant(): boolean {
    this.sessionVersion();
    const roles = sessionState().roles;
    return roles.includes('claimant') && !this.isInternal();
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

  private refreshBadges(): void {
    this.queueBadge.set(null);
    this.escalationBadge.set(null);
    const roles = sessionState().roles;
    const internal =
      roles.includes('adjuster_l1') || roles.includes('adjuster_l2')
      || roles.includes('adjuster_l3') || roles.includes('supervisor');
    if (!internal || !isAuthenticated()) {
      return;
    }
    this.http.get<{ totalElements?: number } | unknown[]>('/api/queue', {
      params: { page: '0', size: '1' },
    }).subscribe({
      next: (body) => {
        if (!Array.isArray(body) && typeof body === 'object' && body !== null) {
          const total = (body as { totalElements?: number }).totalElements;
          this.queueBadge.set(typeof total === 'number' ? total : null);
        } else if (Array.isArray(body)) {
          this.queueBadge.set(body.length);
        }
      },
      error: () => this.queueBadge.set(null),
    });
    if (roles.includes('supervisor')) {
      this.http.get<{ totalElements?: number } | unknown[]>('/api/escalations', {
        params: { page: '0', size: '1' },
      }).subscribe({
        next: (body) => {
          if (!Array.isArray(body) && typeof body === 'object' && body !== null) {
            const total = (body as { totalElements?: number }).totalElements;
            this.escalationBadge.set(typeof total === 'number' ? total : null);
          } else if (Array.isArray(body)) {
            this.escalationBadge.set(body.length);
          }
        },
        error: () => this.escalationBadge.set(null),
      });
    }
  }

  protected async signOut(): Promise<void> {
    await logout();
    this.toasts.info('You have been signed out.');
    await this.router.navigate(['/']);
  }

  /** Claimant-only: fetch the unread count for the header bell. Best-effort. */
  private refreshNotifUnread(): void {
    this.notifUnread.set(null);
    const roles = sessionState().roles;
    if (!isAuthenticated() || !roles.includes('claimant')) {
      return;
    }
    const internal =
      roles.includes('adjuster_l1') || roles.includes('adjuster_l2')
      || roles.includes('adjuster_l3') || roles.includes('supervisor');
    if (internal) {
      return;
    }
    this.http.get<{ unread?: number }>('/api/notifications/mine', {
      params: { page: '0', size: '1' },
    }).subscribe({
      next: (body) => {
        const unread = (body as { unread?: number }).unread;
        this.notifUnread.set(typeof unread === 'number' ? unread : null);
      },
      error: () => this.notifUnread.set(null),
    });
  }

  /** Called by the notifications screen after a mark-read so the bell clears. */
  refreshNotifications(): void {
    this.refreshNotifUnread();
  }
}
