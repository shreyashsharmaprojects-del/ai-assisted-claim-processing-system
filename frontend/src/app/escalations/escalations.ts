import { Component, inject, OnDestroy, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { badgeClass } from '../ui';
import { Toasts, serverMessage } from '../toasts';
import { normalizePage, pageParams } from '../paged';

interface QueueClaimView {
  claimNumber: string;
  status: string;
  level: string;
  policyNumber: string;
  lossDate: string;
  lossLocation: string;
  lossDescription: string;
  createdAt: string;
  assignedTo: string | null;
}

const PAGE_SIZE = 25;

/** The supervisor's escalation queue (supervisor-only; needs a decision). */
@Component({
  imports: [RouterLink],
  selector: 'app-escalations',
  styleUrl: './escalations.css',
  templateUrl: './escalations.html',
})
export class Escalations implements OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);
  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly claims = signal<QueueClaimView[]>([]);
  protected readonly totalElements = signal(0);
  protected readonly totalPages = signal(1);
  protected readonly page = signal(0);
  protected readonly serverPaged = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly query = signal('');

  protected statusBadge(status: string): string {
    return badgeClass(status);
  }

  constructor() {
    void this.load(true);
  }

  ngOnDestroy(): void {
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
    }
  }

  /** Server-paged: rows arrive searched; legacy array: client filter fallback. */
  protected visible(): QueueClaimView[] {
    if (this.serverPaged()) {
      return this.claims();
    }
    const q = this.query().trim().toLowerCase();
    if (!q) {
      return this.claims();
    }
    return this.claims().filter(
      (claim) =>
        claim.claimNumber.toLowerCase().includes(q) ||
        claim.policyNumber.toLowerCase().includes(q) ||
        claim.lossLocation.toLowerCase().includes(q) ||
        claim.lossDescription.toLowerCase().includes(q),
    );
  }

  protected escCount(): number {
    return this.serverPaged() ? this.totalElements() : this.claims().length;
  }

  protected hasMore(): boolean {
    return this.serverPaged() && this.page() + 1 < this.totalPages();
  }

  protected setQuery(value: string): void {
    this.query.set(value);
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
    }
    this.searchTimer = setTimeout(() => void this.load(true), 300);
  }

  async load(reset: boolean) {
    if (reset) {
      this.loaded.set(false);
      this.page.set(0);
    } else {
      this.loadingMore.set(true);
    }
    this.error.set(null);
    try {
      const target = reset ? 0 : this.page() + 1;
      const body = await firstValueFrom(
        this.http.get<QueueClaimView[] | import('../paged').Page<QueueClaimView>>(
          '/api/escalations',
          { params: pageParams(target, PAGE_SIZE, this.query()) },
        ),
      );
      const envelope = !Array.isArray(body);
      const page = normalizePage(body, PAGE_SIZE);
      this.serverPaged.set(envelope);
      this.totalElements.set(page.totalElements);
      this.totalPages.set(page.totalPages);
      this.page.set(page.page);
      this.claims.set(reset ? page.content : [...this.claims(), ...page.content]);
    } catch (err) {
      if (reset) {
        this.error.set(serverMessage(err, 'Could not load the escalation queue. Please try again.'));
      } else {
        this.toasts.error('Could not load more escalations.', err);
      }
    } finally {
      this.loaded.set(true);
      this.loadingMore.set(false);
    }
  }

  protected loadMore(): void {
    void this.load(false);
  }
}
