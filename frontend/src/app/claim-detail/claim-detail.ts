import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { accessToken, hasRole } from '../auth/auth.service';
import { badgeClass } from '../ui';
import { formatDate, formatDateTime, formatMoney, orDash } from '../format';
import { Toasts, serverMessage } from '../toasts';

interface AttachmentView {
  id: number;
  originalName: string;
  label: string | null;
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

/** One cover row on the staged view (internal: full money trail + proposals). */
interface StagedCover {
  coverCode: string;
  displayName?: string | null;
  claimedAmount: number | null;
  subLimit?: number | null;
  aboveLimit?: boolean | null;
  assessedAmount?: number | null;
  approvedAmount?: number | null;
  deductibleAmount?: number | null;
  adjustmentAmount?: number | null;
  netPayable?: number | null;
  decision?: string | null;
  decisionRemarks?: string | null;
  proposal?: boolean | null;
}

interface VerificationView {
  id: number;
  type: string | null;
  status: string;
  outcome: string | null;
  notes: string | null;
  evidenceRefs: string | null;
  performedBy: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

/**
 * The staged full view (V2-4/V2-5/V2-6): the V1 internal fields plus the workflow
 * stage, NEED_INFO state, per-cover money/outcomes, verification history, and the
 * actor's authority context. Covers/verifications are null when empty (V2-2 style).
 * Attachments/notes ride a best-effort merge from the V1 full view (the staged
 * endpoint omits them): absent until merged, so every read must be null-safe.
 */
interface StagedView {
  claimNumber: string;
  status: string;
  stage?: string | null;
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
  attachments?: AttachmentView[] | null;
  notes?: NoteView[] | null;
  needInfoReason?: string | null;
  needInfoPriorStage?: string | null;
  covers?: StagedCover[] | null;
  claimedTotal?: number | null;
  verifications?: VerificationView[] | null;
  authorityLimit?: number | null;
  authorityBasis?: string | null;
  proposalsSaved?: boolean | null;
  proposedTotal?: number | null;
}

/** The legacy decision outcome returned by POST /decision. */
interface ClaimDecisionView {
  claimNumber: string;
  decision: 'APPROVED' | 'DENIED' | null;
  indemnityAmount: number | null;
  decisionRemarks: string | null;
  escalatedTo: 'L2' | 'SUPERVISOR' | null;
}

interface ReferView {
  claimNumber: string;
  status: string;
  level: string;
  assignedTo: string | null;
  escalatedTo: string | null;
}

type Stage = 'REVIEW' | 'VERIFICATION' | 'DECISION';

/** Friendly titles + what-to-do hints for each verification type. */
const VER_META: Record<string, { title: string; hint: string }> = {
  PHYSICAL: {
    title: 'Physical verification',
    hint: 'Inspect the damage, visit the site, confirm the loss happened as described.',
  },
  DOCUMENT: {
    title: 'Document verification',
    hint: 'Check the bills, reports and papers against the claimed amounts.',
  },
  CLAUSE: {
    title: 'Clause verification',
    hint: 'Confirm the policy wording covers this loss — waiting periods, exclusions, limits.',
  },
  DIGITAL: {
    title: 'Digital verification',
    hint: 'Follow-up systems check — fraud flags, duplicates, data cross-checks.',
  },
};

/**
 * The adjuster's claim workspace. One next step per stage — Review (validity),
 * Verification (the default checklist, then assessment), Decision (per-cover
 * outcomes) — with the supporting tools (documents, refer, reserve, notes) in a
 * slim side rail that never competes with the action. Legacy single-figure
 * (cover-less) claims keep the exact V1 decision form.
 */
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
  protected readonly view = signal<StagedView | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);
  protected readonly auditTrail = signal<AuditEntry[]>([]);
  protected readonly auditError = signal<string | null>(null);

  protected statusBadge(status: string): string {
    return badgeClass(status);
  }

  protected lossDateText(): string {
    return formatDate(this.view()?.lossDate);
  }

  protected reserveText(): string {
    return formatMoney(this.view()?.reserveAmount);
  }

  protected readonly decisionResult = signal<string | null>(null);
  /**
   * True once this actor refers the claim upwards: the claim has left their
   * hands (a reload would 404), so action panels hide while the confirmation
   * stays on screen.
   */
  protected readonly leftHands = signal(false);
  protected readonly savingReserve = signal(false);
  protected readonly savingNote = signal(false);
  protected readonly deciding = signal(false);
  protected readonly reassigning = signal(false);
  protected readonly reviewing = signal(false);
  protected readonly savingVerification = signal(false);
  protected readonly assessing = signal(false);
  protected readonly referring = signal(false);
  protected readonly uploading = signal(false);

  protected reserveInput: number | null = null;
  protected noteInput = '';
  protected decisionAmount = '';
  protected decisionRationale = '';
  protected reassignLevel: 'L1' | 'L2' = 'L1';

  // Review inputs.
  protected reviewRationale = '';
  protected reviewRequestedItems = '';
  protected showRejectForm = false;
  protected showNeedInfoForm = false;

  // Verification inputs (per-row completion + the extra follow-up form).
  protected extraVerType: 'DIGITAL' | 'PHYSICAL' = 'DIGITAL';
  protected verificationNotes = '';
  protected verOutcome: Record<number, string> = {};
  protected verNotes: Record<number, string> = {};
  protected verEvidence: Record<number, string> = {};

  // Assessment + cover-decision inputs, keyed by cover code.
  protected assessInputs: Record<string, string> = {};
  protected approveInputs: Record<string, string> = {};
  protected coverDecision: Record<string, 'APPROVED' | 'REJECTED'> = {};
  protected coverRemarks: Record<string, string> = {};
  protected assessmentRationale = '';

  // Referral inputs.
  protected referTarget = '';
  protected referReason = '';

  // Document upload inputs (one persistent form in the side rail).
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
    'Policy copy',
    'Claim form',
    'Other…',
  ];
  protected docChoice = 'Hospital bill';
  protected docCustom = '';

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

  // --- stage helpers ----------------------------------------------------------

  /** The workflow stage; legacy rows default to REVIEW. */
  protected stage(): Stage {
    const raw = this.view()?.stage;
    if (raw === 'VERIFICATION' || raw === 'DECISION') {
      return raw;
    }
    return 'REVIEW';
  }

  protected isNeedInfo(): boolean {
    return this.view()?.status === 'NEED_INFO';
  }

  protected needInfoText(): string {
    return orDash(this.view()?.needInfoReason);
  }

  /** True once the staged backend answers (covers and/or verifications present). */
  protected hasCovers(): boolean {
    return (this.view()?.covers ?? []).length > 0;
  }

  /** Legacy single-figure claim: the V1 decision form, no stages to work. */
  protected isLegacy(): boolean {
    return !this.hasCovers();
  }

  protected covers(): StagedCover[] {
    return this.view()?.covers ?? [];
  }

  protected verifications(): VerificationView[] {
    return this.view()?.verifications ?? [];
  }

  protected openVerifications(): VerificationView[] {
    return this.verifications().filter((v) => v.status !== 'CANCELLED');
  }

  /** Checklist progress: completed rows over open rows (cancelled rows retire). */
  protected checklistDone(): number {
    return this.openVerifications().filter((v) => v.status === 'COMPLETE').length;
  }

  protected checklistTotal(): number {
    return this.openVerifications().length;
  }

  /** Assessment unlocks only when every open verification row is COMPLETE. */
  protected allVerComplete(): boolean {
    const total = this.checklistTotal();
    return total > 0 && this.checklistDone() === total;
  }

  /** Friendly names of the still-open checklist rows (for the guard + button). */
  protected pendingVerNames(): string {
    return this.openVerifications()
      .filter((v) => v.status !== 'COMPLETE')
      .map((v) => this.verTitle(v.type).toLowerCase())
      .join(', ');
  }

  /** True once this claim has stored assessed figures (the assessment step is done). */
  protected assessedDone(): boolean {
    return this.covers().some((c) => c.assessedAmount != null);
  }

  protected verTitle(type: string | null): string {
    return (type != null && VER_META[type]?.title) || 'Verification';
  }

  protected verHint(type: string | null): string {
    return (type != null && VER_META[type]?.hint) || '';
  }

  protected verStatusBadge(status: string): string {
    if (status === 'COMPLETE') {
      return 'badge badge--success';
    }
    if (status === 'CANCELLED') {
      return 'badge badge--neutral';
    }
    return 'badge badge--info';
  }

  protected stageIndex(): number {
    return this.stage() === 'REVIEW' ? 0 : this.stage() === 'VERIFICATION' ? 1 : 2;
  }

  protected stageReached(step: Stage): boolean {
    const order: Stage[] = ['REVIEW', 'VERIFICATION', 'DECISION'];
    return order.indexOf(step) <= order.indexOf(this.stage());
  }

  /** One line under the stepper: what this stage asks of the adjuster. */
  protected stageTask(): string {
    if (this.isLegacy()) {
      return 'Decide this claim: approve within your authority or deny with a reason.';
    }
    switch (this.stage()) {
      case 'REVIEW':
        return 'Check the claim is valid, then advance it — or reject it, or ask the claimant for more.';
      case 'VERIFICATION':
        return this.allVerComplete()
          ? 'All checks complete — record the assessment to move to decision.'
          : `Work the checklist below (${this.checklistDone()} of ${this.checklistTotal()} complete).`;
      case 'DECISION':
        return 'Set each cover outcome, then submit — or refer upwards if it is above your authority.';
    }
  }

  // --- money helpers ----------------------------------------------------------

  protected money(value: number | null | undefined): string {
    return formatMoney(value);
  }

  protected assessedTotal(): number {
    return this.covers().reduce((sum, c) => sum + (c.assessedAmount ?? 0), 0);
  }

  protected approvedTotal(): number {
    let total = 0;
    for (const c of this.covers()) {
      if (this.coverDecision[c.coverCode] === 'APPROVED') {
        const amount = Number(this.approveInputs[c.coverCode]);
        if (!Number.isNaN(amount) && amount > 0) {
          total += amount;
        }
      }
    }
    return total;
  }

  protected netTotal(): number {
    let total = 0;
    for (const c of this.covers()) {
      if (this.coverDecision[c.coverCode] === 'APPROVED') {
        const approved = Number(this.approveInputs[c.coverCode]);
        if (!Number.isNaN(approved) && approved > 0) {
          total += Math.max(approved - (c.deductibleAmount ?? 0), 0);
        }
      }
    }
    return total;
  }

  /** The live authority hint: proposed aggregate vs the actor's limit. */
  protected authorityHint(): string {
    const view = this.view();
    const proposed = view?.proposedTotal ?? this.approvedTotal();
    const limit = view?.authorityLimit;
    if (limit == null) {
      return `${formatMoney(proposed)} proposed — your authority limit is unavailable.`;
    }
    return `${formatMoney(proposed)} proposed against your ${formatMoney(limit)} limit`;
  }

  protected overAuthority(): boolean {
    const view = this.view();
    if (view?.proposalsSaved) {
      return true;
    }
    const limit = view?.authorityLimit;
    if (limit == null) {
      return false;
    }
    return this.approvedTotal() > limit;
  }

  // --- gating -----------------------------------------------------------------

  /**
   * The decision panel shows to whoever can decide this claim right now: the assigned
   * adjuster on an open claim, or a supervisor on a supervisor-escalated claim (no
   * adjuster holds an ESCALATED_SUPERVISOR claim, so only the supervisor's escalation
   * surface sees it). A shown result banner closes the form — except while saved
   * proposals keep the claim open at DECISION (the gate banner + refer box live
   * inside the panel, so hiding it would strand the referral).
   */
  canDecide(): boolean {
    if (this.leftHands()) {
      return false;
    }
    if (this.decisionResult() != null && !this.view()?.proposalsSaved) {
      return false;
    }
    const status = this.view()?.status;
    if (status === 'NEED_INFO') {
      return false;
    }
    if (status === 'UNDER_REVIEW') {
      return hasRole('adjuster_l1') || hasRole('adjuster_l2') || hasRole('adjuster_l3');
    }
    return status === 'ESCALATED_SUPERVISOR' && hasRole('supervisor');
  }

  /** True when this screen is the supervisor deciding an escalated claim. */
  private supervisorEscalationDecision(): boolean {
    return this.view()?.status === 'ESCALATED_SUPERVISOR' && hasRole('supervisor');
  }

  /** Notes for the timeline; the staged wire omits them until merged (never null). */
  protected noteViews(): NoteView[] {
    return this.view()?.notes ?? [];
  }

  /** Documents for the side-rail panel; the staged wire omits them until merged. */
  protected attachmentViews(): AttachmentView[] {
    return this.view()?.attachments ?? [];
  }

  /** Display name: the human label when set, else the stored filename. */
  protected docName(attachment: AttachmentView): string {
    return attachment.label?.trim() ? attachment.label : attachment.originalName;
  }

  /** The label the upload form will store (dropdown choice or the custom name). */
  protected docLabelForUpload(): string | null {
    if (this.docChoice === 'Other…') {
      return this.docCustom.trim() || null;
    }
    return this.docChoice;
  }

  // --- load -------------------------------------------------------------------

  async load() {
    this.error.set(null);
    this.loaded.set(false);
    try {
      const headers = await this.authHeaders();
      if (!headers) {
        return;
      }
      // Prefer the staged view; fall back to the V1 full view when the backend
      // predates the staged endpoints (defensive: keeps the page usable).
      let view: StagedView;
      let staged = false;
      try {
        view = await firstValueFrom(
          this.http.get<StagedView>(`/api/claims/${this.claimNumber}/staged`, {
            headers,
          }),
        );
        staged = true;
      } catch {
        view = await firstValueFrom(
          this.http.get<StagedView>(`/api/claims/${this.claimNumber}/full`, {
            headers,
          }),
        );
      }
      if (staged && (view.attachments == null || view.notes == null)) {
        // The staged endpoint omits photos/notes: merge them from the V1 full
        // view (same claim, same auth — best-effort, the page works without).
        try {
          const full = await firstValueFrom(
            this.http.get<StagedView>(`/api/claims/${this.claimNumber}/full`, {
              headers,
            }),
          );
          view.attachments ??= full.attachments ?? [];
          view.notes ??= full.notes ?? [];
        } catch {
          view.attachments ??= [];
          view.notes ??= [];
        }
      }
      this.applyView(view);
      if (this.isSupervisor()) {
        void this.loadAudit(headers);
      }
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not load this claim.'));
    } finally {
      this.loaded.set(true);
    }
  }

  private applyView(view: StagedView): void {
    this.view.set(view);
    this.reserveInput = view.reserveAmount;
    // Seed the per-cover editors from stored figures (assessed/approved persist).
    for (const cover of view.covers ?? []) {
      if (this.assessInputs[cover.coverCode] === undefined) {
        this.assessInputs[cover.coverCode] =
          cover.assessedAmount != null ? String(cover.assessedAmount) : '';
      }
      if (this.approveInputs[cover.coverCode] === undefined) {
        this.approveInputs[cover.coverCode] =
          cover.approvedAmount != null ? String(cover.approvedAmount) : '';
      }
      if (this.coverDecision[cover.coverCode] === undefined) {
        this.coverDecision[cover.coverCode] =
          cover.decision === 'REJECTED' ? 'REJECTED' : 'APPROVED';
      }
      if (this.coverRemarks[cover.coverCode] === undefined) {
        this.coverRemarks[cover.coverCode] = cover.decisionRemarks ?? '';
      }
    }
    // A saved-proposals response survives reloads: surface the gate banner.
    if (view.proposalsSaved) {
      this.decisionResult.set(
        `Above your authority — proposals saved (${formatMoney(view.proposedTotal)}). Reduce, reject, or refer upwards.`,
      );
    }
  }

  private async reloadStaged(headers: HttpHeaders): Promise<void> {
    const view = await firstValueFrom(
      this.http.get<StagedView>(`/api/claims/${this.claimNumber}/staged`, { headers }),
    );
    this.applyView(view);
  }

  /** Refreshes just the document list after an upload (the staged wire omits it). */
  private async mergeAttachments(headers: HttpHeaders): Promise<void> {
    const current = this.view();
    if (!current) {
      return;
    }
    try {
      const full = await firstValueFrom(
        this.http.get<StagedView>(`/api/claims/${this.claimNumber}/full`, {
          headers,
        }),
      );
      current.attachments = full.attachments ?? [];
    } catch {
      current.attachments ??= [];
    }
    this.view.set({ ...current });
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

  // --- review -----------------------------------------------------------------

  async reviewAdvance() {
    await this.review('ADVANCE', this.reviewRationale.trim());
  }

  async reviewReject() {
    await this.review('REJECT', this.reviewRationale.trim());
  }

  async reviewNeedInfo() {
    await this.review('NEED_INFO', this.reviewRequestedItems.trim());
  }

  private async review(action: 'ADVANCE' | 'REJECT' | 'NEED_INFO', text: string) {
    this.error.set(null);
    this.decisionResult.set(null);
    if (!text) {
      this.error.set(
        action === 'NEED_INFO'
          ? 'Describe what you need from the claimant.'
          : 'A rationale is required.',
      );
      return;
    }
    this.reviewing.set(true);
    const headers = await this.authHeaders();
    if (!headers) {
      this.reviewing.set(false);
      return;
    }
    try {
      const body =
        action === 'NEED_INFO'
          ? { action, requestedItems: text }
          : { action, rationale: text };
      const view = await firstValueFrom(
        this.http.post<StagedView>(`/api/claims/${this.claimNumber}/review`, body, {
          headers,
        }),
      );
      this.reviewRationale = '';
      this.reviewRequestedItems = '';
      this.showRejectForm = false;
      this.showNeedInfoForm = false;
      if (action === 'REJECT') {
        this.decisionResult.set('Rejected at review — claim closed.');
      } else if (action === 'NEED_INFO') {
        this.decisionResult.set('Sent back to the claimant — claim parked as NEED_INFO.');
      } else {
        this.toasts.success('Advanced to verification.');
      }
      this.applyView(view);
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not record the review.'));
    } finally {
      this.reviewing.set(false);
    }
  }

  // --- verifications ------------------------------------------------------------

  async createVerification() {
    this.error.set(null);
    this.savingVerification.set(true);
    const headers = await this.authHeaders();
    if (!headers) {
      this.savingVerification.set(false);
      return;
    }
    try {
      await firstValueFrom(
        this.http.post(
          `/api/claims/${this.claimNumber}/verifications`,
          { type: this.extraVerType, notes: this.verificationNotes.trim() || null },
          { headers },
        ),
      );
      this.verificationNotes = '';
      this.toasts.success('Verification opened.');
      await this.reloadStaged(headers);
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not open the verification.'));
    } finally {
      this.savingVerification.set(false);
    }
  }

  async saveVerification(id: number) {
    this.error.set(null);
    this.savingVerification.set(true);
    const headers = await this.authHeaders();
    if (!headers) {
      this.savingVerification.set(false);
      return;
    }
    try {
      await firstValueFrom(
        this.http.put(
          `/api/claims/${this.claimNumber}/verifications/${id}`,
          {
            status: 'COMPLETE',
            outcome: this.verOutcome[id] || null,
            notes: (this.verNotes[id] ?? '').trim() || null,
            evidenceRefs: (this.verEvidence[id] ?? '').trim() || null,
          },
          { headers },
        ),
      );
      this.toasts.success('Verification completed.');
      await this.reloadStaged(headers);
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not save the verification.'));
    } finally {
      this.savingVerification.set(false);
    }
  }

  protected verificationTimeText(value: string | null | undefined): string {
    return formatDateTime(value);
  }

  // --- assessment ---------------------------------------------------------------

  async saveAssessment() {
    this.error.set(null);
    this.decisionResult.set(null);
    if (!this.assessmentRationale.trim()) {
      this.error.set('A rationale is required to record an assessment.');
      return;
    }
    this.assessing.set(true);
    const headers = await this.authHeaders();
    if (!headers) {
      this.assessing.set(false);
      return;
    }
    try {
      const covers = this.covers().map((c) => ({
        coverCode: c.coverCode,
        assessedAmount: Number(this.assessInputs[c.coverCode]),
      }));
      const view = await firstValueFrom(
        this.http.put<StagedView>(
          `/api/claims/${this.claimNumber}/assessment`,
          { covers, rationale: this.assessmentRationale.trim() },
          { headers },
        ),
      );
      this.toasts.success('Assessment recorded — claim moved to decision.');
      this.applyView(view);
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not record the assessment.'));
    } finally {
      this.assessing.set(false);
    }
  }

  // --- legacy single-figure decision --------------------------------------------

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
      return `Approved for ${formatMoney(outcome.indemnityAmount)} — claim closed.`;
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

  // --- cover-level decision + referral ------------------------------------------

  async submitCoverDecision() {
    this.error.set(null);
    this.decisionResult.set(null);
    if (!this.decisionRationale.trim()) {
      this.error.set('A rationale is required.');
      return;
    }
    this.deciding.set(true);
    const headers = await this.authHeaders();
    if (!headers) {
      this.deciding.set(false);
      return;
    }
    try {
      const covers = this.covers().map((c) => {
        const decision = this.coverDecision[c.coverCode] ?? 'APPROVED';
        if (decision === 'REJECTED') {
          return {
            coverCode: c.coverCode,
            decision,
            remarks: (this.coverRemarks[c.coverCode] ?? '').trim() || null,
          };
        }
        return {
          coverCode: c.coverCode,
          decision,
          approvedAmount: Number(this.approveInputs[c.coverCode]),
          remarks: (this.coverRemarks[c.coverCode] ?? '').trim() || null,
        };
      });
      const path = this.supervisorEscalationDecision()
        ? `/api/claims/${this.claimNumber}/escalation-cover-decision`
        : `/api/claims/${this.claimNumber}/cover-decision`;
      const view = await firstValueFrom(
        this.http.post<StagedView>(path, { covers, rationale: this.decisionRationale.trim() }, {
          headers,
        }),
      );
      if (view.status === 'CLOSED') {
        this.decisionResult.set('Decision recorded — claim closed.');
      } else if (view.proposalsSaved) {
        this.decisionResult.set(
          `Above your authority — proposals saved (${formatMoney(view.proposedTotal)}). Reduce, reject, or refer upwards.`,
        );
      } else {
        this.decisionResult.set('Decision recorded.');
      }
      this.applyView(view);
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not record the decision.'));
    } finally {
      this.deciding.set(false);
    }
  }

  async referNamed() {
    const targetId = Number(this.referTarget);
    if (!this.referTarget.trim() || Number.isNaN(targetId)) {
      this.error.set('Enter the senior adjuster id to refer to.');
      return;
    }
    await this.refer({ targetAdjusterId: targetId, reason: this.referReason.trim() });
  }

  async referAuto() {
    await this.refer({ auto: true, reason: this.referReason.trim() });
  }

  private async refer(body: { targetAdjusterId?: number; auto?: boolean; reason: string }) {
    this.error.set(null);
    if (!body.reason) {
      this.error.set('A reason is required to refer a claim.');
      return;
    }
    this.referring.set(true);
    const headers = await this.authHeaders();
    if (!headers) {
      this.referring.set(false);
      return;
    }
    try {
      const outcome = await firstValueFrom(
        this.http.post<ReferView>(`/api/claims/${this.claimNumber}/refer`, body, {
          headers,
        }),
      );
      const where =
        outcome.escalatedTo === 'SUPERVISOR'
          ? 'the supervisor'
          : `a ${outcome.escalatedTo} adjuster (${outcome.assignedTo ?? 'reassigned'})`;
      // The claim has left this actor's hands (a reload would 404): keep the
      // confirmation on screen instead of refetching.
      this.leftHands.set(true);
      this.decisionResult.set(`Referred upwards to ${where}.`);
      this.toasts.success('Claim referred upwards.');
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not refer the claim.'));
    } finally {
      this.referring.set(false);
    }
  }

  // --- documents ------------------------------------------------------------------

  /**
   * Attaches a document to the open claim (multipart file + the chosen label).
   * The file input is passed straight from the template — ngModel cannot hold
   * a File, so the element is the source of truth.
   */
  async uploadDoc(fileInput: HTMLInputElement) {
    this.error.set(null);
    const file = fileInput.files?.[0];
    if (!file) {
      this.error.set('Choose a file to attach.');
      return;
    }
    this.uploading.set(true);
    const headers = await this.authHeaders();
    if (!headers) {
      this.uploading.set(false);
      return;
    }
    try {
      const form = new FormData();
      form.append('file', file, file.name);
      const label = this.docLabelForUpload();
      if (label) {
        form.append('label', label);
      }
      await firstValueFrom(
        this.http.post(`/api/claims/${this.claimNumber}/attachments`, form, {
          headers,
        }),
      );
      fileInput.value = '';
      this.toasts.success('Document attached.');
      await this.mergeAttachments(headers);
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not attach the document.'));
    } finally {
      this.uploading.set(false);
    }
  }

  // --- reserve + notes (unchanged) ------------------------------------------------

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
        this.http.put<StagedView>(
          `/api/claims/${this.claimNumber}/reserve`,
          { amount },
          { headers },
        ),
      );
      this.applyView(view);
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

function actionClass(action: string): string {
  switch (action) {
    case 'DECISION':
      return 'badge badge--success';
    case 'CLAIM_ESCALATED':
    case 'CLAIM_REFERRED':
      return 'badge badge--special';
    case 'CLAIM_REASSIGNED':
      return 'badge badge--warning';
    case 'RESERVE_SET':
      return 'badge badge--info';
    default:
      return 'badge badge--neutral';
  }
}
