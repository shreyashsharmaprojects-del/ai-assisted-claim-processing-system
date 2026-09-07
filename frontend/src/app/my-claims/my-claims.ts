import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { isAuthenticated, onSessionChange } from '../auth/auth.service';
import { badgeClass } from '../ui';

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

/**
 * The claimant's own history: every claim they filed, newest first. The list that makes
 * claim numbers findable — a claimant who lost the email reopens the claim from here
 * instead of guessing a number in the URL.
 */
@Component({
  imports: [RouterLink],
  selector: 'app-my-claims',
  templateUrl: './my-claims.html',
  styleUrl: './my-claims.css',
})
export class MyClaims {
  private readonly http = inject(HttpClient);

  protected readonly claims = signal<MyClaimRow[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);
  protected readonly signedIn = signal(isAuthenticated());

  protected statusBadge(status: string): string {
    return badgeClass(status);
  }

  protected amountText(row: MyClaimRow): string {
    return row.indemnityAmount == null ? '' : '£' + row.indemnityAmount.toFixed(2);
  }

  constructor() {
    onSessionChange(() => this.signedIn.set(isAuthenticated()));
    void this.load();
  }

  async load() {
    this.error.set(null);
    this.loaded.set(false);
    try {
      const rows = await firstValueFrom(this.http.get<MyClaimRow[]>('/api/claims/mine'));
      this.claims.set(rows);
    } catch {
      this.error.set('Could not load your claims. Please try again.');
    } finally {
      this.loaded.set(true);
    }
  }
}
