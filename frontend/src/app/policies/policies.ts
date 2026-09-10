import { Component, ElementRef, ViewChild, effect, inject, OnDestroy, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { badgeClass } from '../ui';
import { Toasts, serverMessage } from '../toasts';
import { normalizePage, pageParams } from '../paged';

export interface PolicyRow {
  policyNumber: string;
  productCode: string;
  holderName: string;
  holderEmail: string;
  status: string;
  createdAt?: string;
}

interface PreviewRow {
  line: number;
  number: string;
  product: string;
  holder: string;
  email: string;
  problem: string | null;
}

export interface ImportRowResult {
  row: number;
  policyNumber: string;
  ok: boolean;
  error: string | null;
}

type PolicyStatusFilter = 'ALL' | 'ACTIVE' | 'RETIRED';

const PAGE_SIZE = 25;
const CSV_HEADER = 'policy_number,product_code,holder_name,holder_email,coverage';
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * Supervisor policy book (R1): the carrier's book in one screen — browse the table,
 * create a policy, import a CSV with per-row errors, retire dead policies.
 * Banner card + icon-tile section grammar, same tokens as queue/authority.
 * Every list fetch is defensive: the backend may not exist yet, so loading,
 * empty, filtered-to-zero, error + retry states are all first-class.
 */
@Component({
  imports: [FormsModule, RouterLink],
  selector: 'app-policies',
  styleUrl: './policies.css',
  templateUrl: './policies.html',
})
export class Policies implements OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);
  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  // ---- Book list (R4: server search + load-more, plain-array backward-compat) ----
  protected readonly rows = signal<PolicyRow[]>([]);
  protected readonly totalElements = signal(0);
  protected readonly totalPages = signal(1);
  protected readonly page = signal(0);
  protected readonly serverPaged = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly query = signal('');
  protected readonly statusFilter = signal<PolicyStatusFilter>('ALL');

  // ---- Create form ----
  protected readonly createNumber = signal('');
  protected readonly createProduct = signal('');
  protected readonly createHolder = signal('');
  protected readonly createEmail = signal('');
  protected readonly createCoverage = signal('');
  protected readonly createError = signal<string | null>(null);
  protected readonly creating = signal(false);

  // ---- CSV import (client-side dry-run preview -> confirm upload) ----
  protected readonly importFileName = signal<string | null>(null);
  protected readonly importPreview = signal<PreviewRow[]>([]);
  protected readonly importTotal = signal(0);
  protected readonly importError = signal<string | null>(null);
  protected readonly confirming = signal(false);
  protected readonly importResults = signal<ImportRowResult[] | null>(null);
  protected readonly importSummary = signal<string | null>(null);
  private pendingFile: File | null = null;

  // ---- Retire ----
  protected readonly retireTarget = signal<string | null>(null);
  protected readonly retiring = signal(false);
  @ViewChild('retireDialog') private retireDialog?: ElementRef<HTMLElement>;
  private retireOpener: HTMLElement | null = null;

  protected statusBadge(status: string): string {
    return badgeClass(status);
  }

  constructor() {
    void this.load(true);
    // S11: when the retire dialog opens, move focus inside; when it closes,
    // return focus to the opener (Escape path included — cancelRetire runs there).
    effect(() => {
      if (this.retireTarget() !== null) {
        queueMicrotask(() => {
          const dialog = this.retireDialog?.nativeElement;
          const first = dialog?.querySelector<HTMLElement>(
            'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled])',
          );
          (first ?? dialog)?.focus();
        });
      }
    });
  }

  ngOnDestroy(): void {
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
    }
  }

  // ---------- list ----------

  protected bookCount(): number {
    return this.serverPaged() ? this.totalElements() : this.rows().length;
  }

  /** Rows for the table: server-filtered when paged, legacy client filter otherwise. */
  protected visible(): PolicyRow[] {
    const all = this.rows();
    if (this.serverPaged()) {
      return all;
    }
    const q = this.query().trim().toLowerCase();
    const filter = this.statusFilter();
    return all.filter((row) => {
      if (filter !== 'ALL' && (row.status ?? '').toUpperCase() !== filter) {
        return false;
      }
      if (!q) {
        return true;
      }
      return (
        row.policyNumber.toLowerCase().includes(q) ||
        (row.productCode ?? '').toLowerCase().includes(q) ||
        (row.holderName ?? '').toLowerCase().includes(q) ||
        (row.holderEmail ?? '').toLowerCase().includes(q)
      );
    });
  }

  protected hasMore(): boolean {
    return this.serverPaged() && this.page() + 1 < this.totalPages();
  }

  protected hasActiveFilters(): boolean {
    return this.query().trim() !== '' || this.statusFilter() !== 'ALL';
  }

  protected setQuery(value: string): void {
    this.query.set(value);
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
    }
    this.searchTimer = setTimeout(() => void this.load(true), 300);
  }

  protected setStatusFilter(value: PolicyStatusFilter): void {
    this.statusFilter.set(value);
    void this.load(true);
  }

  protected clearFilters(): void {
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
    }
    this.query.set('');
    this.statusFilter.set('ALL');
    void this.load(true);
  }

  async load(reset: boolean): Promise<void> {
    if (reset) {
      this.loaded.set(false);
      this.page.set(0);
    } else {
      this.loadingMore.set(true);
    }
    this.error.set(null);
    try {
      const target = reset ? 0 : this.page() + 1;
      const body = await firstValueFrom(
        this.http.get<PolicyRow[] | import('../paged').Page<PolicyRow>>('/api/policies/admin', {
          params: pageParams(target, PAGE_SIZE, this.query(), this.statusFilter()),
        }),
      );
      const envelope = !Array.isArray(body);
      const page = normalizePage(body, PAGE_SIZE);
      this.serverPaged.set(envelope);
      this.totalElements.set(page.totalElements);
      this.totalPages.set(page.totalPages);
      this.page.set(page.page);
      this.rows.set(reset ? page.content : [...this.rows(), ...page.content]);
    } catch (err) {
      if (reset) {
        this.error.set(serverMessage(err, 'Could not load the policy book. Please try again.'));
      } else {
        this.toasts.error('Could not load more policies.', err);
      }
    } finally {
      this.loaded.set(true);
      this.loadingMore.set(false);
    }
  }

  protected loadMore(): void {
    void this.load(false);
  }

  // ---------- create ----------

  protected createErrorText(): string | null {
    if (this.createNumber().trim() === '') {
      return 'A policy number is required.';
    }
    if (this.createProduct().trim() === '') {
      return 'A product code is required.';
    }
    if (this.createHolder().trim() === '') {
      return 'A holder name is required.';
    }
    if (!EMAIL_RE.test(this.createEmail().trim())) {
      return 'A valid holder email is required.';
    }
    const coverage = this.createCoverage().trim();
    if (coverage !== '') {
      try {
        JSON.parse(coverage);
      } catch {
        return 'Coverage must be valid JSON or left blank.';
      }
    }
    return null;
  }

  async create(): Promise<void> {
    const problem = this.createErrorText();
    if (problem) {
      this.createError.set(problem);
      return;
    }
    this.createError.set(null);
    this.creating.set(true);
    try {
      const coverageText = this.createCoverage().trim();
      const payload: Record<string, unknown> = {
        policyNumber: this.createNumber().trim(),
        productCode: this.createProduct().trim(),
        holderName: this.createHolder().trim(),
        holderEmail: this.createEmail().trim(),
      };
      if (coverageText !== '') {
        payload['coverage'] = JSON.parse(coverageText);
      }
      await firstValueFrom(this.http.post('/api/policies', payload));
      this.toasts.success(`Policy ${this.createNumber().trim()} created.`);
      this.createNumber.set('');
      this.createProduct.set('');
      this.createHolder.set('');
      this.createEmail.set('');
      this.createCoverage.set('');
      await this.load(true);
    } catch (err) {
      this.createError.set(serverMessage(err, 'Could not create the policy.'));
    } finally {
      this.creating.set(false);
    }
  }

  // ---------- CSV import ----------

  protected downloadTemplate(): void {
    const sample =
      `${CSV_HEADER}\n` + 'POL-30001,HOME,Alice Example,alice@example.com,"{""limit"": 5000}"\n';
    const blob = new Blob([sample], { text: 'text/csv' } as BlobPropertyBag);
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'policies-template.csv';
    anchor.click();
    URL.revokeObjectURL(url);
  }

  async onFileSelected(file: File | null): Promise<void> {
    this.importError.set(null);
    this.importResults.set(null);
    this.importSummary.set(null);
    this.pendingFile = file;
    if (!file) {
      this.importFileName.set(null);
      this.importPreview.set([]);
      this.importTotal.set(0);
      return;
    }
    this.importFileName.set(file.name);
    try {
      const text = await file.text();
      const parsed = parseCsvPreview(text);
      this.importPreview.set(parsed.slice(0, 8));
      this.importTotal.set(parsed.length);
      (this as { fullPreview?: PreviewRow[] }).fullPreview = parsed;
    } catch {
      this.importError.set('Could not read that file. Choose a CSV file and try again.');
      this.importPreview.set([]);
      this.importTotal.set(0);
    }
  }

  protected previewProblems(): number {
    return ((this as { fullPreview?: PreviewRow[] }).fullPreview ?? []).filter(
      (row) => row.problem !== null,
    ).length;
  }

  protected importSummaryLine(): string {
    const total = this.importTotal();
    const file = this.importFileName() ?? 'file';
    const problems = this.previewProblems();
    return problems > 0
      ? `${total} rows in ${file}, ${problems} with problems`
      : `${total} rows in ${file}`;
  }

  protected canConfirm(): boolean {
    const all = (this as { fullPreview?: PreviewRow[] }).fullPreview ?? [];
    return all.length > 0 && all.length <= 500;
  }

  async confirmImport(): Promise<void> {
    if (!this.pendingFile) {
      return;
    }
    this.importError.set(null);
    this.confirming.set(true);
    try {
      const form = new FormData();
      form.append('file', this.pendingFile);
      const body = await firstValueFrom(
        this.http.post<unknown>('/api/policies/import', form),
      );
      const results = asImportResults(body);
      this.importResults.set(results);
      const okCount = results.filter((row) => row.ok).length;
      const badCount = results.length - okCount;
      this.importSummary.set(
        results.length === 0
          ? 'Import finished with no row results from the server.'
          : `Imported ${okCount} of ${results.length} rows${badCount > 0 ? ` — ${badCount} rejected (valid rows were kept).` : '.'}`,
      );
      if (badCount === 0 && results.length > 0) {
        this.toasts.success(`Imported ${okCount} policies.`);
      }
      await this.load(true);
    } catch (err) {
      this.importError.set(serverMessage(err, 'Could not import the CSV.'));
    } finally {
      this.confirming.set(false);
    }
  }

  protected clearImport(): void {
    this.pendingFile = null;
    (this as { fullPreview?: PreviewRow[] }).fullPreview = undefined;
    this.importFileName.set(null);
    this.importPreview.set([]);
    this.importTotal.set(0);
    this.importResults.set(null);
    this.importSummary.set(null);
    this.importError.set(null);
  }

  // ---------- retire ----------

  protected askRetire(policyNumber: string, opener?: HTMLElement): void {
    this.retireOpener = opener ?? null;
    this.retireTarget.set(policyNumber);
  }

  protected cancelRetire(): void {
    this.retireTarget.set(null);
    const opener = this.retireOpener;
    this.retireOpener = null;
    if (opener) {
      queueMicrotask(() => opener.focus());
    }
  }

  /**
   * Minimal focus trap for the retire dialog (the only real modal): Escape closes,
   * Tab cycles inside while open. Inline confirms elsewhere need no trap.
   */
  protected trapRetireFocus(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.cancelRetire();
      return;
    }
    if (event.key !== 'Tab') {
      return;
    }
    const dialog = this.retireDialog?.nativeElement;
    if (!dialog) {
      return;
    }
    const focusables = [...dialog.querySelectorAll<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    )].filter((el) => el.offsetParent !== null || el === document.activeElement);
    if (focusables.length === 0) {
      event.preventDefault();
      dialog.focus();
      return;
    }
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  async doRetire(): Promise<void> {
    const target = this.retireTarget();
    if (!target) {
      return;
    }
    this.retiring.set(true);
    try {
      await firstValueFrom(this.http.post(`/api/policies/${target}/retire`, {}));
      this.toasts.success(`Policy ${target} retired. It stays readable for history.`);
      this.retireTarget.set(null);
      await this.load(true);
    } catch (err) {
      this.toasts.error('Could not retire the policy.', err);
    } finally {
      this.retiring.set(false);
    }
  }
}

/** Minimal CSV parse for the dry-run preview: handles quoted commas, no multiline. */
function parseCsvPreview(text: string): PreviewRow[] {
  const lines = text.split(/\r?\n/).filter((line) => line.trim() !== '');
  if (lines.length === 0) {
    return [];
  }
  const header = splitCsvLine(lines[0]).map((cell) => cell.trim().toLowerCase());
  const idx = (name: string): number => header.indexOf(name);
  const numberIdx = idx('policy_number');
  const productIdx = idx('product_code');
  const holderIdx = idx('holder_name');
  const emailIdx = idx('holder_email');
  const rows: PreviewRow[] = [];
  for (let i = 1; i < lines.length; i++) {
    const cells = splitCsvLine(lines[i]);
    const number = numberIdx >= 0 ? (cells[numberIdx] ?? '').trim() : '';
    const product = productIdx >= 0 ? (cells[productIdx] ?? '').trim() : '';
    const holder = holderIdx >= 0 ? (cells[holderIdx] ?? '').trim() : '';
    const email = emailIdx >= 0 ? (cells[emailIdx] ?? '').trim() : '';
    let problem: string | null = null;
    if (numberIdx < 0 || productIdx < 0 || holderIdx < 0 || emailIdx < 0) {
      problem = 'Header must be policy_number,product_code,holder_name,holder_email,coverage.';
    } else if (number === '') {
      problem = 'Missing policy number.';
    } else if (product === '') {
      problem = 'Missing product code.';
    } else if (holder === '') {
      problem = 'Missing holder name.';
    } else if (!EMAIL_RE.test(email)) {
      problem = 'Invalid holder email.';
    }
    rows.push({ line: i + 1, number, product, holder, email, problem });
  }
  return rows;
}

function splitCsvLine(line: string): string[] {
  const cells: string[] = [];
  let current = '';
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (quoted) {
      if (ch === '"') {
        if (line[i + 1] === '"') {
          current += '"';
          i++;
        } else {
          quoted = false;
        }
      } else {
        current += ch;
      }
    } else if (ch === '"') {
      quoted = true;
    } else if (ch === ',') {
      cells.push(current);
      current = '';
    } else {
      current += ch;
    }
  }
  cells.push(current);
  return cells;
}

/** The import endpoint returns per-row results — tolerate envelope or bare array. */
function asImportResults(body: unknown): ImportRowResult[] {
  if (Array.isArray(body)) {
    return body as ImportRowResult[];
  }
  if (body !== null && typeof body === 'object') {
    const record = body as Record<string, unknown>;
    for (const key of ['results', 'rows', 'content']) {
      if (Array.isArray(record[key])) {
        return record[key] as ImportRowResult[];
      }
    }
  }
  return [];
}
