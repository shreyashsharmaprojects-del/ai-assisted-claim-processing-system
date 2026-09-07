import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

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

/**
 * The supervisor's operations overview: one glance at team load, the escalation
 * pressure, and the money committed this month — with the action each number implies.
 * Aggregates only, no per-claim data.
 */
@Component({
  imports: [RouterLink],
  selector: 'app-overview',
  templateUrl: './overview.html',
  styleUrl: './overview.css',
})
export class Overview {
  private readonly http = inject(HttpClient);

  protected readonly stats = signal<DashboardStats | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);

  protected approvedText(): string {
    const amount = this.stats()?.approvedThisMonth;
    return amount == null ? '—' : '£' + amount.toFixed(2);
  }

  constructor() {
    void this.load();
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
}
