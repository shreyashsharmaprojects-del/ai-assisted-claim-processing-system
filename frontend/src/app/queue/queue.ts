import { Component, inject, OnDestroy, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { badgeClass } from '../ui';
import { Toasts, serverMessage } from '../toasts';
import { normalizePage, pageParams } from '../paged';

export interface QueueClaimView {
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

type StatusFilter = 'ALL' | 'UNDER_REVIEW' | 'UNASSIGNED' | 'ESCALATED';
type SortKey = 'OLDEST' | 'NEWEST';

const PAGE_SIZE = 25;

@Component({
  imports: [RouterLink],
  selector: 'app-queue',
  styleUrl: './queue.css',
  templateUrl: './queue.html',
})
export class Queue implements OnDestroy {
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
  protected readonly statusFilter = signal<StatusFilter>('ALL');
  protected readonly sortKey = signal<SortKey>('OLDEST');

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

  /**
   * Rows for the table. Server-paged: the backend already filtered/searched, rows
   * are shown in arrival order with the sort applied to the loaded window.
   * Legacy plain array: the pre-R4 client filter/sort fallback.
   */
  protected visible(): QueueClaimView[] {
    const sorted = [...this.claims()];
    sorted.sort((a, b) =>
      this.sortKey() === 'OLDEST'
        ? a.createdAt.localeCompare(b.createdAt)
        : b.createdAt.localeCompare(a.createdAt),
    );
    if (this.serverPaged()) {
      return sorted;
    }
    const q = this.query().trim().toLowerCase();
    const filter = this.statusFilter();
    return sorted.filter((claim) => {
      if (filter === 'UNDER_REVIEW' && claim.status !== 'UNDER_REVIEW') {
        return false;
      }
      if (filter === 'UNASSIGNED' && claim.status !== 'UNASSIGNED') {
        return false;
      }
      if (filter === 'ESCALATED' && !claim.status.startsWith('ESCALATED')) {
        return false;
      }
      if (!q) {
        return true;
      }
      return (
        claim.claimNumber.toLowerCase().includes(q) ||
        claim.policyNumber.toLowerCase().includes(q) ||
        claim.lossLocation.toLowerCase().includes(q) ||
        claim.lossDescription.toLowerCase().includes(q) ||
        (claim.assignedTo ?? '').toLowerCase().includes(q)
      );
    });
  }

  protected queueCount(): number {
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

  protected setStatusFilter(value: StatusFilter): void {
    this.statusFilter.set(value);
    void this.load(true);
  }

  protected setSortKey(value: SortKey): void {
    this.sortKey.set(value);
  }

  protected clearFilters(): void {
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
    }
    this.query.set('');
    this.statusFilter.set('ALL');
    void this.load(true);
  }

  protected hasActiveFilters(): boolean {
    return this.query().trim() !== '' || this.statusFilter() !== 'ALL';
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
      const status = this.statusFilter() === 'ESCALATED' ? '' : this.statusFilter();
      const body = await firstValueFrom(
        this.http.get<QueueClaimView[] | import('../paged').Page<QueueClaimView>>('/api/queue', {
          params: pageParams(target, PAGE_SIZE, this.query(), status),
        }),
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
        this.error.set(serverMessage(err, 'Could not load your queue. Please try again.'));
      } else {
        this.toasts.error('Could not load more claims.', err);
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
