import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { formatDateTime } from '../format';
import { normalizePage } from '../paged';
import { Toasts, serverMessage } from '../toasts';
import { App } from '../app';

export interface NotificationRow {
  id: number;
  claimId: number;
  claimNumber: string;
  event: string;
  title: string;
  body: string;
  read: boolean;
  createdAt: string;
}

interface NotificationEnvelope {
  content: NotificationRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  unread: number;
}

const PAGE_SIZE = 25;

/**
 * S10 (V25): the claimant's own notification center — newest first, with a
 * per-item mark-read. The envelope carries the bell `unread` count; after a
 * mark-read the header bell refreshes via the shell (`App`).
 *
 * Mirrors the staff/privacy shell: breadcrumb, banner, panels, skeleton /
 * empty / error states. First page plus load-more (trivially easy paging on
 * the shared envelope) — no client filtering.
 */
@Component({
  imports: [RouterLink],
  selector: 'app-notifications',
  templateUrl: './notifications.html',
})
export class Notifications {
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);
  private readonly shell = inject(App);

  protected readonly items = signal<NotificationRow[]>([]);
  protected readonly totalElements = signal(0);
  protected readonly totalPages = signal(1);
  protected readonly page = signal(0);
  protected readonly unread = signal(0);
  protected readonly loaded = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly marking = signal<number | null>(null);

  constructor() {
    void this.load(true);
  }

  protected createdText(row: NotificationRow): string {
    return formatDateTime(row.createdAt);
  }

  protected hasMore(): boolean {
    return this.page() + 1 < this.totalPages();
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
      const body = await firstValueFrom(
        this.http.get<NotificationRow[] | NotificationEnvelope>('/api/notifications/mine', {
          params: { page: String(target), size: String(PAGE_SIZE) },
        }),
      );
      const envelope = !Array.isArray(body);
      const page = normalizePage(body, PAGE_SIZE);
      this.totalElements.set(page.totalElements);
      this.totalPages.set(page.totalPages);
      this.page.set(page.page);
      this.items.set(reset ? page.content : [...this.items(), ...page.content]);
      this.unread.set(envelope ? (body as NotificationEnvelope).unread ?? 0 : 0);
    } catch (err) {
      if (reset) {
        this.error.set(serverMessage(err, 'Could not load your notifications. Please try again.'));
      } else {
        this.toasts.error('Could not load more notifications.', err);
      }
    } finally {
      this.loaded.set(true);
      this.loadingMore.set(false);
    }
  }

  protected loadMore(): void {
    void this.load(false);
  }

  /** Marks one item read (idempotent server-side), then refreshes the bell. */
  protected async markRead(row: NotificationRow): Promise<void> {
    if (row.read || this.marking() !== null) {
      return;
    }
    this.marking.set(row.id);
    try {
      const updated = await firstValueFrom(
        this.http.post<NotificationRow>(`/api/notifications/${row.id}/read`, {}),
      );
      this.items.set(this.items().map((item) => (item.id === row.id ? updated : item)));
      this.unread.set(Math.max(0, this.unread() - 1));
      this.shell.refreshNotifications();
    } catch (err) {
      this.toasts.error('Could not mark this notification as read.', err);
    } finally {
      this.marking.set(null);
    }
  }
}
