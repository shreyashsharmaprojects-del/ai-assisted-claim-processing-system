import { Component, inject, signal } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { accessToken } from '../auth/auth.service';

interface ClaimantClaimView {
  claimNumber: string;
  status: string;
  steps: string[];
  /** Slice 6: present only once the claim is decided (omitted from the wire when null). */
  decision?: 'APPROVED' | 'DENIED' | null;
  indemnityAmount?: number | null;
  decisionRemarks?: string | null;
}

/** The claimant's own claim status screen (journey 2, 8): only claimant-visible data. */
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

  constructor() {
    void this.load();
  }

  /** The approved amount, rendered in pounds with two decimals (journey 8 asserts it). */
  protected approvedAmountText(): string {
    const amount = this.view()?.indemnityAmount;
    return amount == null ? '' : '£' + amount.toFixed(2);
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
      const token = await accessToken();
      if (!token) {
        this.error.set('You are not signed in.');
        this.loaded.set(true);
        return;
      }
      const headers = new HttpHeaders().set('Authorization', 'Bearer ' + token);
      const view = await firstValueFrom(
        this.http.get<ClaimantClaimView>(`/api/claims/${claimNumber}`, { headers }),
      );
      this.view.set(view);
    } catch {
      this.error.set('We could not find that claim.');
    } finally {
      this.loaded.set(true);
    }
  }
}
