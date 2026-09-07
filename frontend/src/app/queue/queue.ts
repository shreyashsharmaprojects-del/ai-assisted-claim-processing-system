import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { badgeClass } from '../ui';

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

@Component({
  imports: [RouterLink],
  selector: 'app-queue',
  styleUrl: './queue.css',
  templateUrl: './queue.html',
})
export class Queue {
  private readonly http = inject(HttpClient);

  protected readonly claims = signal<QueueClaimView[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);
  protected readonly query = signal('');
  protected readonly statusFilter = signal<StatusFilter>('ALL');
  protected readonly sortKey = signal<SortKey>('OLDEST');

  protected statusBadge(status: string): string {
    return badgeClass(status);
  }

  constructor() {
    void this.load();
  }

  /** Rows after search + status filter + sort — the table renders this, never raw. */
  protected visible(): QueueClaimView[] {
    const q = this.query().trim().toLowerCase();
    const filter = this.statusFilter();
    const sorted = [...this.claims()];
    sorted.sort((a, b) =>
      this.sortKey() === 'OLDEST'
        ? a.createdAt.localeCompare(b.createdAt)
        : b.createdAt.localeCompare(a.createdAt),
    );
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

  protected setQuery(value: string): void {
    this.query.set(value);
  }

  protected setStatusFilter(value: StatusFilter): void {
    this.statusFilter.set(value);
  }

  protected setSortKey(value: SortKey): void {
    this.sortKey.set(value);
  }

  protected clearFilters(): void {
    this.query.set('');
    this.statusFilter.set('ALL');
  }

  protected hasActiveFilters(): boolean {
    return this.query().trim() !== '' || this.statusFilter() !== 'ALL';
  }

  async load() {
    this.error.set(null);
    this.loaded.set(false);
    try {
      const rows = await firstValueFrom(this.http.get<QueueClaimView[]>('/api/queue'));
      this.claims.set(rows);
    } catch {
      this.error.set('Could not load your queue. Please try again.');
    } finally {
      this.loaded.set(true);
    }
  }
}
