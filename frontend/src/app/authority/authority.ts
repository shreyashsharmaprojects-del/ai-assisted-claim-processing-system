import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { accessToken } from '../auth/auth.service';

interface ConfigRow {
  productCode: string;
  routeLevel: 'L1' | 'L2';
  l1LimitAmount: number;
  l2LimitAmount: number;
}

/**
 * Editable copy of one config row: the route level is bound directly; the amounts stay
 * text inputs and are converted on save (same pattern as the claim-detail reserve form).
 */
interface EditableRow {
  productCode: string;
  routeLevel: 'L1' | 'L2';
  l1Text: string;
  l2Text: string;
}

/** The supervisor's authority config editor (route-table row /admin/authority, slice 7). */
@Component({
  imports: [FormsModule],
  selector: 'app-authority',
  styleUrl: './authority.css',
  templateUrl: './authority.html',
})
export class Authority {
  private readonly http = inject(HttpClient);

  protected readonly rows = signal<EditableRow[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly saved = signal<string | null>(null);
  protected readonly loaded = signal(false);

  constructor() {
    void this.load();
  }

  async load() {
    this.error.set(null);
    try {
      const headers = await this.authHeaders();
      if (!headers) {
        return;
      }
      const rows = await firstValueFrom(
        this.http.get<ConfigRow[]>('/api/config/authority', { headers }),
      );
      this.rows.set(
        rows.map((row) => ({
          productCode: row.productCode,
          routeLevel: row.routeLevel,
          l1Text: String(row.l1LimitAmount),
          l2Text: String(row.l2LimitAmount),
        })),
      );
    } catch {
      this.error.set('Could not load the authority settings. Please try again.');
    } finally {
      this.loaded.set(true);
    }
  }

  async save(row: EditableRow) {
    this.error.set(null);
    this.saved.set(null);
    const headers = await this.authHeaders();
    if (!headers) {
      return;
    }
    try {
      await firstValueFrom(
        this.http.put(
          `/api/config/authority/${row.productCode}`,
          {
            routeLevel: row.routeLevel,
            l1LimitAmount: Number(row.l1Text),
            l2LimitAmount: Number(row.l2Text),
          },
          { headers },
        ),
      );
      this.saved.set(
        `Saved ${row.productCode}. The change applies to the next claim filed and the next decision.`,
      );
      await this.load();
    } catch (err) {
      const message = (err as { error?: { message?: string } })?.error?.message;
      this.error.set(message ?? 'Could not save the authority settings.');
    }
  }

  private async authHeaders(): Promise<HttpHeaders | null> {
    const token = await accessToken();
    if (!token) {
      this.error.set('You are not signed in.');
      return null;
    }
    return new HttpHeaders().set('Authorization', 'Bearer ' + token);
  }
}
