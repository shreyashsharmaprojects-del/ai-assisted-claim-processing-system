import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { Toasts, serverMessage } from '../toasts';

interface RetentionReport {
  policyClosedYears: number;
  buckets: { olderThan6y: number; olderThan7y: number; olderThan10y: number };
}

interface AnonymizeResult {
  anonymizedSub: string;
  claimsAnonymized: number;
  policiesRedacted: number;
}

/**
 * Supervisor privacy panel (S9): erasure of one subject's PII with a confirm
 * step, plus the report-only retention table. Mirrors the S7 staff shell:
 * breadcrumb, banner, panels, confirm-then-write. The server redacts exactly
 * the listed columns; audit, money and decisions stay intact.
 */
@Component({
  imports: [RouterLink],
  selector: 'app-privacy',
  templateUrl: './privacy.html',
})
export class Privacy {
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);

  protected readonly claimantSub = signal('');
  protected readonly rationale = signal('');
  protected readonly confirming = signal(false);
  protected readonly anonymizing = signal(false);
  protected readonly result = signal<AnonymizeResult | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly report = signal<RetentionReport | null>(null);
  protected readonly reportLoaded = signal(false);
  protected readonly reportError = signal<string | null>(null);

  constructor() {
    void this.loadReport();
  }

  protected canAsk(): boolean {
    return this.claimantSub().trim() !== '' && this.rationale().trim() !== '';
  }

  async loadReport() {
    this.reportError.set(null);
    this.reportLoaded.set(false);
    try {
      const report = await firstValueFrom(
        this.http.get<RetentionReport>('/api/admin/privacy/retention-report'),
      );
      this.report.set(report);
    } catch (err) {
      this.reportError.set(
        serverMessage(err, 'Could not load the retention report. Please try again.'),
      );
    } finally {
      this.reportLoaded.set(true);
    }
  }

  protected askAnonymize(): void {
    if (!this.canAsk()) {
      return;
    }
    this.confirming.set(true);
  }

  protected cancelAnonymize(): void {
    this.confirming.set(false);
  }

  /** Confirm calls POST /api/admin/privacy/anonymize {claimantSub, rationale}. */
  async confirmAnonymize() {
    if (!this.canAsk() || this.anonymizing()) {
      return;
    }
    this.anonymizing.set(true);
    this.error.set(null);
    try {
      const result = await firstValueFrom(
        this.http.post<AnonymizeResult>('/api/admin/privacy/anonymize', {
          claimantSub: this.claimantSub().trim(),
          rationale: this.rationale().trim(),
        }),
      );
      this.result.set(result);
      this.confirming.set(false);
      this.toasts.success(
        `${result.claimsAnonymized} claim(s) anonymized to ${result.anonymizedSub}.`,
      );
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not anonymize this subject. Please try again.'));
    } finally {
      this.anonymizing.set(false);
    }
  }
}
