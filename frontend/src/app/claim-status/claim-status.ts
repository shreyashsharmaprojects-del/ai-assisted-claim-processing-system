import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { badgeClass } from '../ui';
import { formatDate, formatMoney } from '../format';
import { Toasts, serverMessage } from '../toasts';

/** One filed cover on the claimant view (camelCase, claimant-safe keys only). */
interface FiledCover {
  coverCode: string;
  claimedAmount: number;
  aboveLimit: boolean;
  displayName?: string | null;
  subLimit?: number | null;
  decision?: string | null;
  approvedAmount?: number | null;
  decisionRemarks?: string | null;
}

interface ClaimantClaimView {
  claimNumber: string;
  status: string;
  steps: string[];
  lossDate?: string | null;
  /** Present only once the claim is decided (omitted from the wire when null). */
  decision?: 'APPROVED' | 'PARTIALLY_APPROVED' | 'DENIED' | null;
  indemnityAmount?: number | null;
  decisionRemarks?: string | null;
  covers?: FiledCover[] | null;
  claimedTotal?: number | null;
  netPayableTotal?: number | null;
  /** The adjuster's requested items while the claim waits on the claimant. */
  needInfoReason?: string | null;
}

/** The claimant's own claim status screen: only claimant-visible data. */
@Component({
  imports: [FormsModule, RouterLink],
  selector: 'app-claim-status',
  styleUrl: './claim-status.css',
  templateUrl: './claim-status.html',
})
export class ClaimStatus {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly toasts = inject(Toasts);

  protected readonly view = signal<ClaimantClaimView | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);
  protected readonly responding = signal(false);
  protected readonly uploading = signal(false);

  /** Document names the claimant picks from (or free-texts via Other). */
  protected readonly DOC_LABELS = [
    'Hospital bill',
    'Discharge summary',
    'Prescriptions',
    'Diagnostic report',
    'Police report (FIR)',
    'Repair estimate',
    'Repair invoice',
    'Damage photos',
    'ID proof',
    'Other…',
  ];
  protected responseMessage = '';
  protected docChoice = 'Hospital bill';
  protected docCustom = '';

  protected statusBadge(status: string): string {
    return badgeClass(status);
  }

  constructor() {
    void this.load();
  }

  /** The approved amount, rendered in pounds with two decimals. */
  protected approvedAmountText(): string {
    return formatMoney(this.netPayable() ?? this.view()?.indemnityAmount);
  }

  /** Net payable on an approved-like closure (null while open / legacy). */
  protected netPayable(): number | null | undefined {
    return this.view()?.netPayableTotal;
  }

  /** True on a partially-approved closure (mixed per-cover outcomes). */
  protected isPartial(): boolean {
    return this.view()?.decision === 'PARTIALLY_APPROVED';
  }

  /** Claimed amount per cover / filed total, in rupees with two decimals. */
  protected money(value: number | null | undefined): string {
    return formatMoney(value);
  }

  /** Filing date line under the banner; the wire may omit it on older backends. */
  protected filedText(): string {
    const lossDate = this.view()?.lossDate;
    return lossDate ? formatDate(lossDate) : 'recently';
  }

  /** True while the claim waits on the claimant (the action panel shows). */
  protected needsInfo(): boolean {
    return this.view()?.status === 'NEED_INFO';
  }

  /** The adjuster's requested items (their own round-trip text, wall-safe). */
  protected needInfoText(): string {
    return this.view()?.needInfoReason?.trim() || 'More information is needed.';
  }

  /** The label the document upload will store (dropdown choice or custom name). */
  protected docLabelForUpload(): string | null {
    if (this.docChoice === 'Other…') {
      return this.docCustom.trim() || null;
    }
    return this.docChoice;
  }

  /** Sends the NEED_INFO text response; the claim returns to the adjuster. */
  async respondToNeedInfo() {
    const claimNumber = this.view()?.claimNumber;
    if (!claimNumber || !this.responseMessage.trim()) {
      this.error.set('Write a short reply before sending.');
      return;
    }
    this.error.set(null);
    this.responding.set(true);
    try {
      const view = await firstValueFrom(
        this.http.post<ClaimantClaimView>(
          `/api/claims/${claimNumber}/need-info-response`,
          { message: this.responseMessage.trim() },
        ),
      );
      this.responseMessage = '';
      this.view.set(view);
      this.toasts.success('Sent — your claim is back with the adjuster.');
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not send your reply.'));
    } finally {
      this.responding.set(false);
    }
  }

  /** Uploads a document answering the NEED_INFO request (labelled by choice/name). */
  async uploadDoc(fileInput: HTMLInputElement) {
    const claimNumber = this.view()?.claimNumber;
    const file = fileInput.files?.[0];
    if (!claimNumber) {
      return;
    }
    if (!file) {
      this.error.set('Choose a file to attach.');
      return;
    }
    this.error.set(null);
    this.uploading.set(true);
    try {
      const form = new FormData();
      form.append('file', file, file.name);
      const label = this.docLabelForUpload();
      if (label) {
        form.append('label', label);
      }
      await firstValueFrom(
        this.http.post(`/api/claims/${claimNumber}/documents`, form),
      );
      fileInput.value = '';
      this.toasts.success('Document attached — now send your reply below.');
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not attach the document.'));
    } finally {
      this.uploading.set(false);
    }
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
