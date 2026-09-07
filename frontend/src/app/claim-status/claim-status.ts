import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { badgeClass } from '../ui';
import { formatDate, formatMoney } from '../format';
import { serverMessage } from '../toasts';

interface ClaimantClaimView {
  claimNumber: string;
  status: string;
  steps: string[];
  lossDate?: string | null;
  /** Present only once the claim is decided (omitted from the wire when null). */
  decision?: 'APPROVED' | 'DENIED' | null;
  indemnityAmount?: number | null;
  decisionRemarks?: string | null;
}

/** The claimant's own claim status screen: only claimant-visible data. */
@Component({
  imports: [RouterLink],
  selector: 'app-claim-status',
  styleUrl: './claim-status.css',
  templateUrl: './claim-status.html',
})
export class ClaimStatus {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);

  protected readonly view = signal<ClaimantClaimView | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);

  protected statusBadge(status: string): string {
    return badgeClass(status);
  }

  constructor() {
    void this.load();
  }

  /** The approved amount, rendered in pounds with two decimals. */
  protected approvedAmountText(): string {
    return formatMoney(this.view()?.indemnityAmount);
  }

  /** Filing date line under the banner; the wire may omit it on older backends. */
  protected filedText(): string {
    const lossDate = this.view()?.lossDate;
    return lossDate ? formatDate(lossDate) : 'recently';
  }

  async load() {
    this.error.set(null);
    this.loaded.set(false);
    const claimNumber = this.route.snapshot.paramMap.get('claimNumber');
    if (!claimNumber) {
      this.error.set('No claim number given.');
      this.loaded.set(true);
      return;
    }
    try {
      const view = await firstValueFrom(
        this.http.get<ClaimantClaimView>(`/api/claims/${claimNumber}`),
      );
      this.view.set(view);
    } catch (err) {
      this.error.set(serverMessage(err, 'We could not find that claim.'));
    } finally {
      this.loaded.set(true);
    }
  }
}
