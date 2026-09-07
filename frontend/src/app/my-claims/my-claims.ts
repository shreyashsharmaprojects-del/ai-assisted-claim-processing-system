import { Component, inject, OnDestroy, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { isAuthenticated, onSessionChange } from '../auth/auth.service';
import { badgeClass } from '../ui';
import { formatDate, formatMoney } from '../format';
import { Toasts, serverMessage } from '../toasts';
import { normalizePage, pageParams } from '../paged';

export interface MyClaimRow {
  claimNumber: string;
  status: string;
  productCode: string;
  lossDate: string;
  createdAt: string;
  decision?: 'APPROVED' | 'DENIED' | null;
  indemnityAmount?: number | null;
  decisionRemarks?: string | null;
}

type MyClaimsStatusFilter = 'ALL' | 'OPEN' | 'APPROVED' | 'DENIED';

const PAGE_SIZE = 25;

/**
 * The claimant's own history: every claim they filed, newest first. The list that makes
 * claim numbers findable — a claimant who lost the email reopens the claim from here
 * instead of guessing a number in the URL.
 *
 * R4: server search + status filter + load-more; legacy plain-array backward-compat.
 * Claimant surfaces never show reserve/notes/assignee/coverage — unchanged here.
 */
@Component({
  imports: [RouterLink],
  selector: 'app-my-claims',
  templateUrl: './my-claims.html',
  styleUrl: './my-claims.css',
})
export class MyClaims implements OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);
  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly claims = signal<MyClaimRow[]>([]);
  protected readonly totalElements = signal(0);
  protected readonly totalPages = signal(1);
  protected readonly page = signal(0);
  protected readonly serverPaged = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly signedIn = signal(isAuthenticated());
  protected readonly query = signal('');
  protected readonly statusFilter = signal<MyClaimsStatusFilter>('ALL');

  protected statusBadge(status: string): string {
    return badgeClass(status);
  }

  protected amountText(row: MyClaimRow): string {
    return formatMoney(row.indemnityAmount);
  }

  protected lossDateText(row: MyClaimRow): string {
    return formatDate(row.lossDate);
  }

  constructor() {
    onSessionChange(() => this.signedIn.set(isAuthenticated()));
    void this.load(true);
  }

  ngOnDestroy(): void {
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
    }
  }

  /** Server-paged: rows arrive searched/filtered; legacy array: client fallback. */
  protected visible(): MyClaimRow[] {
    if (this.serverPaged()) {
      return this.claims();
    }
    const q = this.query().trim().toLowerCase();
    const filter = this.statusFilter();
    return this.claims().filter((claim) => {
      if (filter === 'APPROVED' && claim.decision !== 'APPROVED') {
        return false;
      }
      if (filter === 'DENIED' && claim.decision !== 'DENIED') {
        return false;
      }
      if (filter === 'OPEN' && claim.decision != null) {
        return false;
      }
      if (!q) {
        return true;
      }
      return (
        claim.claimNumber.toLowerCase().includes(q) ||
        (claim.productCode ?? '').toLowerCase().includes(q)
      );
    });
  }

  protected mineCount(): number {
    return this.serverPaged() ? this.totalElements() : this.claims().length;
  }

  protected hasMore(): boolean {
    return this.serverPaged() && this.page() + 1 < this.totalPages();
  }

  protected hasActiveFilters(): boolean {
    return this.query().trim() !== '' || this.statusFilter() !== 'ALL';
  }

  protected setQuery(value: string): void {
    this.query.set(value);
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
    }
    this.searchTimer = setTimeout(() => void this.load(true), 300);
  }

  protected setStatusFilter(value: MyClaimsStatusFilter): void {
    this.statusFilter.set(value);
    void this.load(true);
  }

  protected clearFilters(): void {
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
    }
    this.query.set('');
    this.statusFilter.set('ALL');
    void this.load(true);
  }

  async load(reset: boolean) {
    this.error.set(null);
    if (reset) {
      this.loaded.set(false);
      this.page.set(0);
    } else {
      this.loadingMore.set(true);
    }
    try {
      const target = reset ? 0 : this.page() + 1;
      const status = this.statusFilter() === 'OPEN' ? '' : this.statusFilter();
      const body = await firstValueFrom(
        this.http.get<MyClaimRow[] | import('../paged').Page<MyClaimRow>>('/api/claims/mine', {
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
        this.error.set(serverMessage(err, 'Could not load your claims. Please try again.'));
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
