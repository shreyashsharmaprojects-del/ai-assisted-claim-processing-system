import { Component, inject, OnDestroy, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { badgeClass } from '../ui';
import { formatISODate, formatMoney } from '../format';
import { Toasts, serverMessage } from '../toasts';
import { normalizePage, pageParams } from '../paged';

interface DashboardStats {
  openClaims: number;
  unassignedClaims: number;
  underReviewClaims: number;
  escalatedClaims: number;
  closedClaims: number;
  agedToL2Last7Days: number;
  agedToSupervisorLast7Days: number;
  approvedThisMonth: number;
}

export interface OutboxRow {
  id: number;
  claimNumber?: string;
  claimId?: number;
  kind: string;
  toAddress: string;
  subject: string;
  status: string;
  attempts: number;
  lastError?: string | null;
  createdAt?: string;
  sentAt?: string | null;
}

type OutboxStatusFilter = 'ALL' | 'PENDING' | 'SENT' | 'FAILED';

const OUTBOX_PAGE_SIZE = 25;

/** S8 (V23): decisions-export range defaults — the last 30 days, yyyy-MM-dd. */
function todayIso(): string {
  return formatISODate(new Date());
}

function last30Start(): string {
  const date = new Date();
  date.setDate(date.getDate() - 30);
  return formatISODate(date);
}

/**
 * The supervisor's operations overview: one glance at team load, the escalation
 * pressure, and the money committed this month — with the action each number implies.
 * Aggregates only, no per-claim data.
 */
@Component({
  imports: [RouterLink, FormsModule],
  selector: 'app-overview',
  templateUrl: './overview.html',
  styleUrl: './overview.css',
})
export class Overview implements OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);
  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly stats = signal<DashboardStats | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);

  // ---- R2 email outbox panel (supervisor-only; defensive until the backend lands) ----
  protected readonly outboxRows = signal<OutboxRow[]>([]);
  protected readonly outboxTotal = signal(0);
  protected readonly outboxTotalPages = signal(1);
  protected readonly outboxPage = signal(0);
  protected readonly outboxServerPaged = signal(false);
  protected readonly outboxError = signal<string | null>(null);
  protected readonly outboxLoaded = signal(false);
  protected readonly outboxLoadingMore = signal(false);
  protected readonly outboxQuery = signal('');
  protected readonly outboxStatus = signal<OutboxStatusFilter>('ALL');
  protected readonly retrying = signal<number | null>(null);

  // ---- S8 (V23) decisions CSV export (supervisor-only; from/to default to last 30 days) ----
  protected readonly exportingDecisions = signal(false);
  protected exportFrom = last30Start();
  protected exportTo = todayIso();

  protected approvedText(): string {
    return formatMoney(this.stats()?.approvedThisMonth);
  }

  protected outboxBadge(status: string): string {
    return badgeClass(status);
  }

  protected claimLabel(row: OutboxRow): string {
    return row.claimNumber ?? (row.claimId != null ? String(row.claimId) : '—');
  }

  protected outboxPending(): number {
    return this.outboxRows().filter((row) => (row.status ?? '').toUpperCase() === 'PENDING').length;
  }

  protected outboxFailed(): number {
    return this.outboxRows().filter((row) => (row.status ?? '').toUpperCase() === 'FAILED').length;
  }

  protected outboxVisible(): OutboxRow[] {
    const all = this.outboxRows();
    if (this.outboxServerPaged()) {
      return all;
    }
    const q = this.outboxQuery().trim().toLowerCase();
    const filter = this.outboxStatus();
    return all.filter((row) => {
      if (filter !== 'ALL' && (row.status ?? '').toUpperCase() !== filter) {
        return false;
      }
      if (!q) {
        return true;
      }
      return (
        this.claimLabel(row).toLowerCase().includes(q) ||
        (row.kind ?? '').toLowerCase().includes(q) ||
        (row.toAddress ?? '').toLowerCase().includes(q) ||
        (row.subject ?? '').toLowerCase().includes(q)
      );
    });
  }

  protected outboxHasMore(): boolean {
    return this.outboxServerPaged() && this.outboxPage() + 1 < this.outboxTotalPages();
  }

  protected setOutboxQuery(value: string): void {
    this.outboxQuery.set(value);
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
    }
    this.searchTimer = setTimeout(() => void this.loadOutbox(true), 300);
  }

  protected setOutboxStatus(value: OutboxStatusFilter): void {
    this.outboxStatus.set(value);
    void this.loadOutbox(true);
  }

  constructor() {
    void this.load();
    void this.loadOutbox(true);
  }

  ngOnDestroy(): void {
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
    }
  }

  async load() {
    this.error.set(null);
    this.loaded.set(false);
    try {
      const stats = await firstValueFrom(this.http.get<DashboardStats>('/api/dashboard'));
      this.stats.set(stats);
    } catch {
      this.error.set('Could not load the operations overview. Please try again.');
    } finally {
      this.loaded.set(true);
    }
  }

  async loadOutbox(reset: boolean): Promise<void> {
    if (reset) {
      this.outboxLoaded.set(false);
      this.outboxPage.set(0);
    } else {
      this.outboxLoadingMore.set(true);
    }
    this.outboxError.set(null);
    try {
      const target = reset ? 0 : this.outboxPage() + 1;
      const body = await firstValueFrom(
        this.http.get<OutboxRow[] | import('../paged').Page<OutboxRow>>('/api/outbox', {
          params: pageParams(target, OUTBOX_PAGE_SIZE, this.outboxQuery(), this.outboxStatus()),
        }),
      );
      const envelope = !Array.isArray(body);
      const page = normalizePage(body, OUTBOX_PAGE_SIZE);
      this.outboxServerPaged.set(envelope);
      this.outboxTotal.set(page.totalElements);
      this.outboxTotalPages.set(page.totalPages);
      this.outboxPage.set(page.page);
      this.outboxRows.set(reset ? page.content : [...this.outboxRows(), ...page.content]);
    } catch (err) {
      if (reset) {
        this.outboxError.set(serverMessage(err, 'Could not load the email outbox. Please try again.'));
      } else {
        this.toasts.error('Could not load more outbox rows.', err);
      }
    } finally {
      this.outboxLoaded.set(true);
      this.outboxLoadingMore.set(false);
    }
  }

  protected loadMoreOutbox(): void {
    void this.loadOutbox(false);
  }

  /** S8 (V23): download the closure CSV for the from/to range via blob + filename. */
  async exportDecisions(): Promise<void> {
    this.exportingDecisions.set(true);
    try {
      const params = `from=${encodeURIComponent(this.exportFrom)}&to=${encodeURIComponent(this.exportTo)}`;
      const response = await firstValueFrom(
        this.http.get(`/api/decisions/export?${params}`, {
          responseType: 'blob',
          observe: 'response',
        }),
      );
      const body = response.body ?? new Blob();
      const match = /filename[^;=\n]*=((["'])(.*?)\2|([^;\n]*))/.exec(
        response.headers.get('Content-Disposition') ?? '',
      );
      const name = match?.[3]?.trim() || match?.[4]?.trim() || `decisions-${this.exportFrom}-${this.exportTo}.csv`;
      const url = URL.createObjectURL(body);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = name;
      anchor.click();
      URL.revokeObjectURL(url);
      this.toasts.success('Decisions exported.');
    } catch (err) {
      this.toasts.error('Could not export the decisions.', err);
    } finally {
      this.exportingDecisions.set(false);
    }
  }

  /** Retry a FAILED row: reset to PENDING. Quiet toast, list refreshes in place. */
  async retryRow(row: OutboxRow): Promise<void> {
    this.retrying.set(row.id);
    try {
      await firstValueFrom(this.http.post(`/api/outbox/${row.id}/retry`, {}));
      this.toasts.success(`Retry queued for the email to ${row.toAddress}.`);
      await this.loadOutbox(true);
    } catch (err) {
      this.toasts.error('Could not queue the retry.', err);
    } finally {
      this.retrying.set(null);
    }
  }
}
