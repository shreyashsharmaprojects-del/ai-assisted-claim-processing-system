import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { Toasts, serverMessage } from '../toasts';

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

/** The supervisor's authority config editor (supervisor-only). */
@Component({
  imports: [FormsModule],
  selector: 'app-authority',
  styleUrl: './authority.css',
  templateUrl: './authority.html',
})
export class Authority {
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);

  protected readonly rows = signal<EditableRow[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly saved = signal<string | null>(null);
  protected readonly loaded = signal(false);
  protected readonly saving = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  async load() {
    this.error.set(null);
    this.loaded.set(false);
    try {
      const rows = await firstValueFrom(this.http.get<ConfigRow[]>('/api/config/authority'));
      this.rows.set(
        rows.map((row) => ({
          productCode: row.productCode,
          routeLevel: row.routeLevel,
          l1Text: row.l1LimitAmount.toFixed(2),
          l2Text: row.l2LimitAmount.toFixed(2),
        })),
      );
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not load the authority settings. Please try again.'));
    } finally {
      this.loaded.set(true);
    }
  }

  /** Client-side ladder check: L1 must not exceed L2 (the server re-validates). */
  protected ladderError(row: EditableRow): string | null {
    const l1 = Number(row.l1Text);
    const l2 = Number(row.l2Text);
    if (row.l1Text.trim() === '' || row.l2Text.trim() === '' || Number.isNaN(l1) || Number.isNaN(l2)) {
      return 'Both limits need a numeric amount.';
    }
    if (l1 <= 0 || l2 <= 0) {
      return 'Limits must be greater than zero.';
    }
    if (l1 > l2) {
      return 'The L1 limit cannot exceed the L2 limit.';
    }
    return null;
  }

  async save(row: EditableRow) {
    this.error.set(null);
    this.saved.set(null);
    const ladder = this.ladderError(row);
    if (ladder) {
      this.error.set(`${row.productCode}: ${ladder}`);
      return;
    }
    this.saving.set(row.productCode);
    try {
      await firstValueFrom(
        this.http.put(
          `/api/config/authority/${row.productCode}`,
          {
            routeLevel: row.routeLevel,
            l1LimitAmount: Number(row.l1Text),
            l2LimitAmount: Number(row.l2Text),
          },
        ),
      );
      this.saved.set(
        `Saved ${row.productCode}. The change applies to the next claim filed and the next decision.`,
      );
      this.toasts.success(`Authority settings saved for ${row.productCode}.`);
      await this.load();
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not save the authority settings.'));
    } finally {
      this.saving.set(null);
    }
  }
}
