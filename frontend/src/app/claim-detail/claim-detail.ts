import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { accessToken, hasRole } from '../auth/auth.service';
import { badgeClass } from '../ui';
import { Toasts, serverMessage } from '../toasts';

interface AttachmentView {
  id: number;
  originalName: string;
}

interface NoteView {
  id: number;
  body: string;
  author: string | null;
}

interface AuditEntry {
  id: number;
  action: string;
  actorSub: string | null;
  rationale: string | null;
  createdAt: string;
}

interface InternalClaimView {
  claimNumber: string;
  status: string;
  level: string;
  policyNumber: string;
  productCode: string;
  coverage: unknown;
  holderName: string;
  lossDate: string;
  lossLocation: string;
  lossDescription: string;
  claimantRemarks: string | null;
  reserveAmount: number | null;
  assignedTo: string | null;
  attachments: AttachmentView[];
  notes: NoteView[];
}

/** The decision outcome returned by POST /decision. */
interface ClaimDecisionView {
  claimNumber: string;
  decision: 'APPROVED' | 'DENIED' | null;
  indemnityAmount: number | null;
  decisionRemarks: string | null;
  escalatedTo: 'L2' | 'SUPERVISOR' | null;
}

/** The adjuster's claim screen: full internal view, reserve, notes, photos, decision. */
@Component({
  imports: [FormsModule, RouterLink],
  selector: 'app-claim-detail',
  styleUrl: './claim-detail.css',
  templateUrl: './claim-detail.html',
})
export class ClaimDetail {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly toasts = inject(Toasts);

  protected readonly claimNumber = this.route.snapshot.paramMap.get('claimNumber') ?? '';
  protected readonly view = signal<InternalClaimView | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);
  protected readonly auditTrail = signal<AuditEntry[]>([]);
  protected readonly auditError = signal<string | null>(null);

  protected statusBadge(status: string): string {
    return badgeClass(status);
  }
  protected readonly decisionResult = signal<string | null>(null);
  protected readonly savingReserve = signal(false);
  protected readonly savingNote = signal(false);
  protected readonly deciding = signal(false);
  protected readonly reassigning = signal(false);

  protected reserveInput: number | null = null;
  protected noteInput = '';
  protected decisionAmount = '';
  protected decisionRationale = '';
  protected reassignLevel: 'L1' | 'L2' = 'L1';

  constructor() {
    void this.load();
  }

  coverageText(): string {
    const coverage = this.view()?.coverage;
    return coverage == null ? '' : JSON.stringify(coverage, null, 2);
  }

  /** Supervisor-only: reassign targets and the audit trail (both supervisor surfaces). */
  protected isSupervisor(): boolean {
    return hasRole('supervisor');
  }

  protected auditActionLabel(action: string): string {
    return actionClass(action);
  }

  /**
   * The decision panel shows to whoever can decide this claim right now: the assigned
   * adjuster on an open claim, or a supervisor on a supervisor-escalated claim (no
   * adjuster holds an ESCALATED_SUPERVISOR claim, so only the supervisor's escalation
   * surface sees it).
   */
  canDecide(): boolean {
    if (this.decisionResult() != null) {
      return false;
    }
    const status = this.view()?.status;
    if (status === 'UNDER_REVIEW') {
      return hasRole('adjuster_l1') || hasRole('adjuster_l2');
    }
    return status === 'ESCALATED_SUPERVISOR' && hasRole('supervisor');
  }

  /** True when this screen is the supervisor deciding an escalated claim. */
  private supervisorEscalationDecision(): boolean {
    return this.view()?.status === 'ESCALATED_SUPERVISOR' && hasRole('supervisor');
  }

  async load() {
    this.error.set(null);
    this.loaded.set(false);
    try {
      const headers = await this.authHeaders();
      if (!headers) {
        return;
      }
      const view = await firstValueFrom(
        this.http.get<InternalClaimView>(`/api/claims/${this.claimNumber}/full`, { headers }),
      );
      this.view.set(view);
      this.reserveInput = view.reserveAmount;
      if (this.isSupervisor()) {
        void this.loadAudit(headers);
      }
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not load this claim.'));
    } finally {
      this.loaded.set(true);
    }
  }

  private async loadAudit(headers: HttpHeaders): Promise<void> {
    this.auditError.set(null);
    try {
      const trail = await firstValueFrom(
        this.http.get<AuditEntry[]>(`/api/claims/${this.claimNumber}/audit`, { headers }),
      );
      this.auditTrail.set(trail);
    } catch {
      this.auditError.set('The audit trail could not be loaded.');
    }
  }

  /**
   * True when the reserve input holds a usable amount. The field is bound to a number
   * input, so Angular assigns a `number` (or `null` when cleared); a reserve of 0 is a
   * legitimate value, so the guard checks null/NaN rather than truthiness.
   */
  protected reserveReady(): boolean {
    return this.reserveInput !== null && !Number.isNaN(this.reserveInput);
  }

  async saveReserve() {
    this.error.set(null);
    const amount = this.reserveInput;
    if (amount === null) {
      this.error.set('Enter a reserve amount.');
      return;
    }
    if (Number.isNaN(amount)) {
      this.error.set('Reserve must be a number.');
      return;
    }
    this.savingReserve.set(true);
    const headers = await this.authHeaders();
    if (!headers) {
      this.savingReserve.set(false);
      return;
    }
    try {
      const view = await firstValueFrom(
        this.http.put<InternalClaimView>(
          `/api/claims/${this.claimNumber}/reserve`,
          { amount },
          { headers },
        ),
      );
      this.view.set(view);
      this.toasts.success('Reserve saved.');
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not save the reserve.'));
    } finally {
      this.savingReserve.set(false);
    }
  }

  async addNote() {
    this.error.set(null);
    this.savingNote.set(true);
    const headers = await this.authHeaders();
    if (!headers) {
      this.savingNote.set(false);
      return;
    }
    try {
      await firstValueFrom(
        this.http.post<NoteView>(
          `/api/claims/${this.claimNumber}/notes`,
          { body: this.noteInput.trim() },
          { headers },
        ),
      );
      this.noteInput = '';
      this.toasts.success('Note added.');
      await this.load();
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not add the note.'));
    } finally {
      this.savingNote.set(false);
    }
  }

  async approve() {
    await this.decide({
      decision: 'APPROVED',
      indemnityAmount: Number(this.decisionAmount),
      rationale: this.decisionRationale.trim(),
    });
  }

  async deny() {
    await this.decide({ decision: 'DENIED', rationale: this.decisionRationale.trim() });
  }

  async reassign() {
    this.error.set(null);
    this.reassigning.set(true);
    const headers = await this.authHeaders();
    if (!headers) {
      this.reassigning.set(false);
      return;
    }
    try {
      await firstValueFrom(
        this.http.post(
          `/api/claims/${this.claimNumber}/reassign`,
          { level: this.reassignLevel },
          { headers },
        ),
      );
      this.toasts.success(`Claim reassigned to ${this.reassignLevel}.`);
      await this.load();
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not reassign the claim.'));
    } finally {
      this.reassigning.set(false);
    }
  }

  private async decide(body: {
    decision: 'APPROVED' | 'DENIED';
    indemnityAmount?: number;
    rationale: string;
  }) {
    this.error.set(null);
    this.decisionResult.set(null);
    this.deciding.set(true);
    const headers = await this.authHeaders();
    if (!headers) {
      this.deciding.set(false);
      return;
    }
    try {
      // The supervisor's escalation decision is its own endpoint; the assigned
      // adjuster's decision is the other one.
      const path = this.supervisorEscalationDecision()
        ? `/api/claims/${this.claimNumber}/escalation-decision`
        : `/api/claims/${this.claimNumber}/decision`;
      const outcome = await firstValueFrom(
        this.http.post<ClaimDecisionView>(path, body, { headers }),
      );
      this.decisionResult.set(this.describe(outcome));
      if (outcome.escalatedTo == null) {
        // The claim closed (approved/denied); the assignee can still open it, now CLOSED.
        await this.load();
      }
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not record the decision.'));
    } finally {
      this.deciding.set(false);
    }
  }

  private describe(outcome: ClaimDecisionView): string {
    if (outcome.decision === 'APPROVED') {
      return `Approved for ${formatAmount(outcome.indemnityAmount)} — claim closed.`;
    }
    if (outcome.decision === 'DENIED') {
      const remarks = outcome.decisionRemarks == null ? '' : ` ${outcome.decisionRemarks}`;
      return `Denied — claim closed.${remarks}`;
    }
    if (outcome.escalatedTo === 'SUPERVISOR') {
      return 'This amount is above your authority — the claim has been escalated to a supervisor.';
    }
    return 'This amount is above your authority — the claim has been escalated to a Level 2 adjuster.';
  }

  async download(attachment: AttachmentView) {
    const headers = await this.authHeaders();
    if (!headers) {
      return;
    }
    try {
      const blob = await firstValueFrom(
        this.http.get(`/api/claims/${this.claimNumber}/attachments/${attachment.id}`, {
          headers,
          responseType: 'blob',
        }),
      );
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = attachment.originalName;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not download the photo.'));
    }
  }

  private async authHeaders(): Promise<HttpHeaders | null> {
    const token = await accessToken();
    if (!token) {
      this.error.set('You are not signed in.');
      return null;
    }
    return new HttpHeaders().set('Authorization', 'Bearer ' + token);
  }
}

function formatAmount(amount: number | null): string {
  return amount == null ? '' : '£' + amount.toFixed(2);
}

function actionClass(action: string): string {
  switch (action) {
    case 'DECISION':
      return 'badge badge--success';
    case 'CLAIM_ESCALATED':
      return 'badge badge--special';
    case 'CLAIM_REASSIGNED':
      return 'badge badge--warning';
    case 'RESERVE_SET':
      return 'badge badge--info';
    default:
      return 'badge badge--neutral';
  }
}
