import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { formatMoney } from '../format';
import { serverMessage } from '../toasts';

/** One cover as returned by GET /api/claims/filing-covers (camelCase). */
export interface AvailableCover {
  coverCode: string;
  displayName: string;
  subLimit: number;
}

interface FilingCoverRow {
  coverCode?: string;
  displayName?: string | null;
  subLimit?: number | null;
}

/** One filed cover as echoed on the claimant view (POST + tracker GET, camelCase). */
export interface FiledCover {
  coverCode: string;
  claimedAmount: number;
  aboveLimit: boolean;
  displayName?: string | null;
  subLimit?: number | null;
}

interface ClaimantClaimView {
  claimNumber: string;
  status: string;
  steps: string[];
  covers?: FiledCover[] | null;
  claimedTotal?: number | null;
}

const MAX_PHOTOS = 5;
const MAX_PHOTO_MB = 10;

/** Mirrors the server evidence allowlist: images + PDF. */
function isEvidenceFile(file: File): boolean {
  return file.type.startsWith('image/') || file.type === 'application/pdf';
}

@Component({
  imports: [FormsModule, RouterLink],
  selector: 'app-fnol',
  styleUrl: './fnol.css',
  templateUrl: './fnol.html',
})
export class Fnol {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);

  protected step: 1 | 2 = 1;
  protected policyNumber = '';
  protected holderName = '';
  protected holderEmail = '';
  protected lossDate = '';
  protected lossLocation = '';
  protected lossDescription = '';
  protected remarks = '';
  protected photoFiles: FileList | null = null;

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly result = signal<ClaimantClaimView | null>(null);
  /**
   * Set when the form arrived pre-linked from a policy (My policies → File a
   * claim): the identity fields are verified server-side and shown read-only,
   * so the claimant answers only what happened — never retypes their policy.
   */
  protected readonly lockedPolicy = signal(false);

  constructor() {
    const preselected = (this.route.snapshot.queryParamMap.get('policy') ?? '').trim();
    if (preselected !== '') {
      this.lockedPolicy.set(true);
      this.policyNumber = preselected;
      // Identity comes from the caller's own cockpit row (/mine): holder
      // details are filled without the claimant typing them; the holder-match
      // gate still runs server-side at cover load + submit (same 404 shape).
      // Deferred past construction: the auth interceptor needs the Keycloak
      // session settled (a constructor-fetch races sign-in and 401s away the
      // lock even though the session exists).
      setTimeout(() => void this.adoptOwnPolicy(preselected), 0);
    }
  }

  /** Fill the step-1 identity from the caller's own cockpit row (same wall). */
  private async adoptOwnPolicy(policyNumber: string): Promise<void> {
    try {
      const mine = await firstValueFrom(
        this.http.get<
          Array<{ policyNumber: string; holderName: string; holderEmail: string }>
        >('/api/policies/mine'),
      );
      const own = (mine ?? []).find(
        (row) => row.policyNumber?.toUpperCase() === policyNumber.toUpperCase(),
      );
      if (!own || !own.holderEmail) {
        // Not mine (or gone): drop the lock and fall back to manual entry
        // rather than stranding the claimant on a broken pre-fill.
        this.lockedPolicy.set(false);
        return;
      }
      this.policyNumber = own.policyNumber;
      this.holderName = own.holderName ?? '';
      this.holderEmail = own.holderEmail;
      if (this.step1Valid()) {
        // Locked: identity verified against the caller's own row — stay on
        // the confirmation card and let Continue carry them to step 2.
        // (Do NOT auto-advance here: a lock that jumps straight to step 2
        // leaves the claimant wondering which policy they are filing under.)
      } else {
        this.lockedPolicy.set(false);
      }
    } catch {
      this.lockedPolicy.set(false);
    }
  }

  // --- cover picker (V2-2) ----------------------------------------------------
  // Loaded from GET /api/claims/filing-covers with the typed step-1 holder
  // details (holder-match gate, same 404 shape as submit). On 404/empty/<=1
  // cover the section hides and submit omits the `covers` part (legacy path).
  // Selection is always optional: shown-but-unchecked submits without covers.

  protected readonly availableCovers = signal<AvailableCover[]>([]);
  protected coverChecked: Record<string, boolean> = {};
  protected coverAmounts: Record<string, string> = {};

  /** The cover section shows only when the policy carries 2+ covers. */
  protected showCovers(): boolean {
    return this.availableCovers().length >= 2;
  }

  protected checkedCoverCodes(): string[] {
    return this.availableCovers()
      .map((cover) => cover.coverCode)
      .filter((code) => this.coverChecked[code] === true);
  }

  /** Above-limit is informational, never blocks: hint when amount > sub-limit. */
  protected aboveLimitHint(cover: AvailableCover): string | null {
    if (this.coverChecked[cover.coverCode] !== true) {
      return null;
    }
    const amount = Number(this.coverAmounts[cover.coverCode]);
    if (!Number.isFinite(amount) || amount <= 0) {
      return null;
    }
    if (!(cover.subLimit > 0) || amount <= cover.subLimit) {
      return null;
    }
    return `Above the ${formatMoney(cover.subLimit)} sub-limit — still fileable, flagged for review`;
  }

  /**
   * Cover validation: null when fine. Selection is optional, so an empty
   * selection is valid; every checked amount must be > 0.
   */
  protected coversError(): string | null {
    if (!this.showCovers()) {
      return null;
    }
    for (const code of this.checkedCoverCodes()) {
      const amount = Number(this.coverAmounts[code]);
      if (!Number.isFinite(amount) || amount <= 0) {
        return 'Enter a claimed amount greater than 0 for each selected cover.';
      }
    }
    return null;
  }

  protected money(value: number | null | undefined): string {
    return formatMoney(value);
  }

  /** Currency symbol derived from the locale formatter (en-GB: ₹) — for static labels. */
  protected currencySymbol(): string {
    const shaped = formatMoney(0).replace(/[\d.,\s ]+/g, '').trim();
    return shaped === '' ? '₹' : shaped;
  }

  private async loadCovers(): Promise<void> {
    this.availableCovers.set([]);
    this.coverChecked = {};
    this.coverAmounts = {};
    const policyNumber = this.policyNumber.trim();
    const holderName = this.holderName.trim();
    const holderEmail = this.holderEmail.trim();
    if (policyNumber === '' || holderName === '' || holderEmail === '') {
      return;
    }
    try {
      const rows = await firstValueFrom(
        this.http.get<FilingCoverRow[] | { covers?: FilingCoverRow[] }>(
          '/api/claims/filing-covers',
          { params: { policyNumber, holderName, holderEmail } },
        ),
      );
      const list: FilingCoverRow[] = Array.isArray(rows) ? rows : (rows?.covers ?? []);
      const covers: AvailableCover[] = (list ?? [])
        .filter(
          (cover): cover is FilingCoverRow & { coverCode: string } =>
            !!cover && typeof cover.coverCode === 'string' && cover.coverCode !== '',
        )
        .map((cover) => ({
          coverCode: cover.coverCode,
          displayName: cover.displayName ?? cover.coverCode,
          subLimit: typeof cover.subLimit === 'number' ? cover.subLimit : 0,
        }));
      this.availableCovers.set(covers);
    } catch {
      // 404 (holder mismatch / unknown policy) or backend not yet deployed:
      // legacy path, no covers section.
      this.availableCovers.set([]);
    }
  }

  // --- step 1 (policy) ------------------------------------------------------

  protected step1Valid(): boolean {
    return (
      this.policyNumber.trim() !== '' &&
      this.holderName.trim() !== '' &&
      /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.holderEmail.trim())
    );
  }

  protected step1Hint(): string {
    if (this.policyNumber.trim() === '' || this.holderName.trim() === '') {
      return 'Enter the policy number and holder name exactly as they appear on the policy document.';
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.holderEmail.trim())) {
      return 'Enter a valid policyholder email — the claim number and updates go there.';
    }
    return '';
  }

  protected nextStep(): void {
    this.error.set(null);
    if (this.step1Valid()) {
      this.step = 2;
      void this.loadCovers();
    }
  }

  /** Locked pre-fill proved wrong (or the claimant prefers manual): manual step 1. */
  protected unlockPolicy(): void {
    this.step = 1;
    this.lockedPolicy.set(false);
    this.policyNumber = '';
    this.holderName = '';
    this.holderEmail = '';
    this.availableCovers.set([]);
    this.coverChecked = {};
    this.coverAmounts = {};
  }

  protected prevStep(): void {
    this.error.set(null);
    this.step = 1;
  }

  // --- step 2 (loss) --------------------------------------------------------

  /** Client-side photo guard: count + per-file size/type, before the server round-trip. */
  protected photoHint(): string {
    if (!this.photoFiles || this.photoFiles.length === 0) {
      return 'Photos speed up your claim; you can add up to 5.';
    }
    const names: string[] = [];
    for (const file of Array.from(this.photoFiles)) {
      if (!isEvidenceFile(file)) {
        return `"${file.name}" must be image or PDF files.`;
      }
      if (file.size > MAX_PHOTO_MB * 1024 * 1024) {
        return `"${file.name}" is over ${MAX_PHOTO_MB} MB — choose a smaller photo.`;
      }
      names.push(file.name);
    }
    if (this.photoFiles.length > MAX_PHOTOS) {
      return `At most ${MAX_PHOTOS} photos may be attached — you selected ${this.photoFiles.length}.`;
    }
    return `${this.photoFiles.length} photo${this.photoFiles.length === 1 ? '' : 's'} selected: ${names.join(', ')}.`;
  }

  /** Loss-field validity (photos included); checked-cover amounts gate separately. */
  protected step2Valid(): boolean {
    return this.lossFieldsValid() && this.coversError() === null;
  }

  private lossFieldsValid(): boolean {
    if (this.lossDate === '' || this.lossLocation.trim() === '' || this.lossDescription.trim() === '') {
      return false;
    }
    if (this.photoFiles && this.photoFiles.length > 0) {
      if (this.photoFiles.length > MAX_PHOTOS) {
        return false;
      }
      for (const file of Array.from(this.photoFiles)) {
        if (!isEvidenceFile(file) || file.size > MAX_PHOTO_MB * 1024 * 1024) {
          return false;
        }
      }
    }
    return true;
  }

  onPhotosSelected(event: Event) {
    this.photoFiles = (event.target as HTMLInputElement).files;
  }

  async submit() {
    this.error.set(null);
    this.result.set(null);
    if (!this.step1Valid() || !this.lossFieldsValid()) {
      this.error.set('Check the highlighted details before submitting.');
      return;
    }
    const coverProblem = this.coversError();
    if (coverProblem) {
      this.error.set(coverProblem);
      return;
    }
    this.submitting.set(true);
    try {
      const form = new FormData();
      form.append('policyNumber', this.policyNumber.trim());
      form.append('holderName', this.holderName.trim());
      form.append('holderEmail', this.holderEmail.trim());
      form.append('lossDate', this.lossDate);
      form.append('lossLocation', this.lossLocation.trim());
      form.append('lossDescription', this.lossDescription.trim());
      if (this.remarks.trim()) {
        form.append('remarks', this.remarks.trim());
      }
      // Optional: only when >=1 cover checked; otherwise legacy path (backend defaults).
      const selected = this.showCovers() ? this.checkedCoverCodes() : [];
      if (selected.length > 0) {
        const covers = selected.map((code) => ({
          coverCode: code,
          claimedAmount: Number(this.coverAmounts[code]),
        }));
        form.append('covers', JSON.stringify(covers));
      }
      if (this.photoFiles) {
        for (const file of Array.from(this.photoFiles)) {
          form.append('photos', file);
        }
      }
      const view = await firstValueFrom(this.http.post<ClaimantClaimView>('/api/claims', form));
      this.result.set(view);
    } catch (err) {
      if (err instanceof HttpErrorResponse && err.status === 429) {
        this.error.set(
          serverMessage(err, 'Too many claims filed recently. Please wait before filing another.'),
        );
      } else {
        this.error.set(serverMessage(err, 'Something went wrong. Please try again.'));
      }
    } finally {
      this.submitting.set(false);
    }
  }
}
