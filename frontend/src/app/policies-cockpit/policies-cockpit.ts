import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { isAuthenticated, onSessionChange } from '../auth/auth.service';
import { badgeClass } from '../ui';
import { formatDate, formatMoney } from '../format';
import { serverMessage } from '../toasts';

/** JSON numbers sometimes arrive as strings; the money formatter needs numbers. */
export function coerceAmount(value: number | string | null | undefined): number | null {
  if (value == null || value === '') {
    return null;
  }
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isNaN(n) ? null : n;
}

/**
 * V2-1 claimant cockpit: my policies (covers count, remaining benefit, status).
 * Read-only and functionally complete; visual polish waits for the workflow slices.
 * Claimant-safe: the API shape carries no holder email or internal fields.
 *
 * Server amounts arrive as JSON numbers; when a backend ever serializes them as
 * strings, the text helpers coerce them before formatting (shared formatMoney
 * expects numbers). Product display name falls back to the code when absent.
 */
export interface CockpitPolicy {
  policyNumber: string;
  productCode: string;
  productFamily: string;
  productDisplayName: string;
  holderName: string;
  holderEmail: string;
  status: string;
  sumInsured: number | null;
  remainingBenefit: number | null;
  validFrom: string | null;
  validTo: string | null;
  coverCount: number;
}

@Component({
  imports: [RouterLink],
  selector: 'app-policies-cockpit',
  templateUrl: './policies-cockpit.html',
  styleUrl: './policies-cockpit.css',
})
export class PoliciesCockpit {
  private readonly http = inject(HttpClient);

  protected readonly policies = signal<CockpitPolicy[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);
  protected readonly signedIn = signal(isAuthenticated());

  protected statusBadge(status: string): string {
    return badgeClass(status);
  }

  protected sumText(policy: CockpitPolicy): string {
    return formatMoney(coerceAmount(policy.sumInsured));
  }

  protected remainingText(policy: CockpitPolicy): string {
    return formatMoney(coerceAmount(policy.remainingBenefit));
  }

  protected validText(policy: CockpitPolicy): string {
    if (!policy.validFrom && !policy.validTo) {
      return '—';
    }
    return `${formatDate(policy.validFrom)} – ${formatDate(policy.validTo)}`;
  }

  constructor() {
    onSessionChange(() => this.signedIn.set(isAuthenticated()));
    void this.load();
  }

  async load() {
    this.error.set(null);
    this.loaded.set(false);
    try {
      const rows = await firstValueFrom(
        this.http.get<CockpitPolicy[]>('/api/policies/mine'),
      );
      this.policies.set(rows);
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not load your policies. Please try again.'));
    } finally {
      this.loaded.set(true);
    }
  }
}
