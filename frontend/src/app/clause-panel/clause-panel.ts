import { Component, Input, OnChanges, OnInit, SimpleChanges, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { formatDate, formatMoney } from '../format';
import { statusLabel } from '../ui';
import { serverMessage } from '../toasts';

/** One policy clause row from GET /api/claims/{claimNumber}/policy-clauses. */
interface PolicyClause {
  id: number;
  productCode: string;
  coverCode: string | null;
  clauseRef: string;
  clauseType: string;
  title: string;
  clauseText: string;
  waitingPeriodDays: number | null;
  subLimitAmount: number | null;
  coPayPercent: number | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  sortOrder: number;
}

/** One rendered group: product-level rows first ("General"), then one per cover. */
interface ClauseGroup {
  key: string;
  heading: string;
  clauses: PolicyClause[];
}

/**
 * Read-only policy-clause panel for the claim workspace. Loads the clauses
 * that apply to one claim (product-level rows plus per-cover rows), groups
 * them under headings, and lets the adjuster expand each clause to read the
 * full wording. No writes, no forms — the toggle is the only control.
 */
@Component({
  selector: 'app-clause-panel',
  styleUrl: './clause-panel.css',
  templateUrl: './clause-panel.html',
})
export class ClausePanelComponent implements OnInit, OnChanges {
  private readonly http = inject(HttpClient);

  @Input() claimNumber = '';

  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly clauses = signal<PolicyClause[]>([]);
  /** Local UI state: which clause wordings are expanded (collapsed by default). */
  protected expanded: Record<number, boolean> = {};

  ngOnInit(): void {
    void this.load();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['claimNumber'] && !changes['claimNumber'].isFirstChange()) {
      void this.load();
    }
  }

  /** Clause groups in render order: "General" first, then one per coverCode. */
  protected groups(): ClauseGroup[] {
    const all = this.clauses();
    const general = all.filter((c) => c.coverCode == null);
    const groups: ClauseGroup[] =
      general.length > 0 ? [{ key: 'general', heading: 'General', clauses: general }] : [];
    const seen = new Set<string>();
    for (const clause of all) {
      if (clause.coverCode == null || seen.has(clause.coverCode)) {
        continue;
      }
      seen.add(clause.coverCode);
      groups.push({
        key: clause.coverCode,
        heading: clause.coverCode,
        clauses: all.filter((c) => c.coverCode === clause.coverCode),
      });
    }
    return groups;
  }

  protected isExpanded(clause: PolicyClause): boolean {
    return this.expanded[clause.id] ?? false;
  }

  protected toggle(clause: PolicyClause): void {
    this.expanded[clause.id] = !this.isExpanded(clause);
  }

  protected retry(): void {
    void this.load();
  }

  /** Sentence-case label for the raw clause-type token (display only). */
  protected typeLabel(clauseType: string): string {
    return statusLabel(clauseType);
  }

  /** Defined value for one clause, via the shared formatters (display only). */
  protected definedValue(clause: PolicyClause): string {
    switch (clause.clauseType) {
      case 'SUB_LIMIT':
        return formatMoney(clause.subLimitAmount);
      case 'WAITING_PERIOD':
        return clause.waitingPeriodDays == null ? '—' : `${clause.waitingPeriodDays} days`;
      case 'CO_PAY':
        return clause.coPayPercent == null ? '—' : `${clause.coPayPercent}%`;
      default:
        return '—';
    }
  }

  /** Second-line facts: the limits that do not drive the headline value, in
   * clause order — sub-limit, waiting period, co-pay — then the dates. Only
   * present facts render, separated with middots; empty renders an em dash. */
  protected factsText(clause: PolicyClause): string {
    const facts: string[] = [];
    if (clause.clauseType !== 'SUB_LIMIT') {
      const subLimit = formatMoney(clause.subLimitAmount);
      if (subLimit !== '—') {
        facts.push(`Sub-limit ${subLimit}`);
      }
    }
    if (clause.clauseType !== 'WAITING_PERIOD' && clause.waitingPeriodDays != null) {
      facts.push(`Waiting period ${clause.waitingPeriodDays} days`);
    }
    if (clause.clauseType !== 'CO_PAY' && clause.coPayPercent != null) {
      facts.push(`Co-pay ${clause.coPayPercent}%`);
    }
    facts.push(this.effectiveText(clause));
    return facts.length > 0 ? facts.join(' · ') : '—';
  }

  /** Effective window for one clause, via the shared date formatter. */
  protected effectiveText(clause: PolicyClause): string {
    const from = formatDate(clause.effectiveFrom);
    const to = formatDate(clause.effectiveTo);
    return clause.effectiveTo == null || to === '—' ? `From ${from}` : `${from} – ${to}`;
  }

  private async load(): Promise<void> {
    const claimNumber = (this.claimNumber ?? '').trim();
    if (!claimNumber) {
      this.clauses.set([]);
      this.error.set(null);
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    try {
      // Auth rides the shared authInterceptor (Bearer token on /api) — no
      // per-component Authorization header is built here.
      const rows = await firstValueFrom(
        this.http.get<PolicyClause[]>(
          `/api/claims/${encodeURIComponent(claimNumber)}/policy-clauses`,
        ),
      );
      this.clauses.set(rows ?? []);
    } catch (err) {
      this.clauses.set([]);
      this.error.set(serverMessage(err, 'Could not load the policy clauses.'));
    } finally {
      this.loading.set(false);
    }
  }
}
