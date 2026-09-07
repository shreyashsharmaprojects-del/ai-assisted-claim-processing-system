import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { badgeClass } from '../ui';
import { formatDate, formatMoney } from '../format';
import { serverMessage } from '../toasts';
import type { CockpitPolicy } from './policies-cockpit';
import { coerceAmount } from './policies-cockpit';

/** V2-1 policy detail: covers with remaining sub-limits, rating params, clauses. */
export interface CockpitCover {
  coverCode: string;
  displayName: string;
  subLimit: number;
  deductibleDefault: number;
  remainingSubLimit: number;
}

interface PolicyDetailResponse {
  policy: CockpitPolicy;
  covers: CockpitCover[];
  ratingParams: Record<string, unknown> | null;
  clauses: { covered?: string[]; excluded?: string[]; scope?: string } | null;
}

@Component({
  imports: [RouterLink],
  selector: 'app-policy-detail',
  templateUrl: './policy-detail.html',
  styleUrl: './policy-detail.css',
})
export class PolicyDetail {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);

  protected readonly detail = signal<PolicyDetailResponse | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);
  protected readonly notFound = signal(false);

  protected statusBadge(status: string): string {
    return badgeClass(status);
  }

  protected money(value: number | string | null | undefined): string {
    return formatMoney(coerceAmount(value));
  }

  protected ratingEntries(): Array<[string, unknown]> {
    const params = this.detail()?.ratingParams;
    if (!params || typeof params !== 'object') {
      return [];
    }
    return Object.entries(params);
  }

  constructor() {
    void this.load();
  }

  async load() {
    this.error.set(null);
    this.notFound.set(false);
    this.loaded.set(false);
    const policyNumber = this.route.snapshot.paramMap.get('policyNumber') ?? '';
    try {
      const detail = await firstValueFrom(
        this.http.get<PolicyDetailResponse>(
          `/api/policies/${encodeURIComponent(policyNumber)}`,
        ),
      );
      this.detail.set(detail);
    } catch (err) {
      const status =
        typeof err === 'object' && err !== null && 'status' in err
          ? (err as { status: unknown }).status
          : null;
      if (status === 404) {
        this.notFound.set(true);
      } else {
        this.error.set(serverMessage(err, 'Could not load this policy. Please try again.'));
      }
    } finally {
      this.loaded.set(true);
    }
  }

  protected fileClaimLink(): string {
    return '/claim/new';
  }

  protected validText(policy: CockpitPolicy): string {
    if (!policy.validFrom && !policy.validTo) {
      return '—';
    }
    return `${formatDate(policy.validFrom)} – ${formatDate(policy.validTo)}`;
  }
}
