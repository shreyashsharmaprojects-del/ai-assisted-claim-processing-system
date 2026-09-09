import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
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
  verificationId?: number | null;
}

interface NoteView {
  id: number;
  body: string;
  author: string | null;
}

/** One required-document checklist row (staff shape: full row incl. decidedBy). */
interface RequiredDocRow {
  checkId: number;
  docKey: string;
  displayName: string;
  status: string;
  attachmentId: number | null;
  decidedBy: string | null;
  decidedAt: string | null;
}

interface AuditEntry {
  id: number;
  action: string;
  actorSub: string | null;
  rationale: string | null;
  createdAt: string;
}

/** One row of the claim timeline (V17 unified feed). */
interface TimelineEntry {
  kind: string;
  actor: string | null;
  detail: string | null;
  attachmentName: string | null;
  attachmentId: number | null;
  verificationId: number | null;
  at: string | null;
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
  /** S5 (V21): optimistic-concurrency version; every money write echoes it back as expectedVersion. */
  version?: number | null;
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

/** Mirrors the server evidence allowlist: images + PDF (10MB per file). */
function isEvidenceFile(file: File): boolean {
  return file.type.startsWith('image/') || file.type === 'application/pdf';
}

const MAX_DOC_MB = 10;

/**
 * The adjuster's claim workspace. One next step per stage — Review (validity),
 * Verification (the default checklist, then assessment), Decision (per-cover
 * outcomes) — with the claim timeline underneath: one sequential feed of every
 * note, document, check and decision on the claim, Jira-activity style. Loss
 * details, reserve and policy/coverage ride a slim reference rail that never
 * competes with the action. Legacy single-figure (cover-less) claims keep the
 * exact V1 decision form.
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
  /** V17: the unified claim timeline (notes + documents + checks + milestones). */
  protected readonly timeline = signal<TimelineEntry[]>([]);
  /** S3: the required-documents checklist (staff rows; empty until loaded). */
  protected readonly requiredDocs = signal<RequiredDocRow[]>([]);
  protected readonly reqDocsError = signal<string | null>(null);
  protected readonly reqDocsBusy = signal(false);
  protected reqLinkFor: Record<number, string> = {};
  protected reqWaiveFor: Record<number, string> = {};
  protected showWaiveFor: Record<number, boolean> = {};

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
   * S5: shown after a 409 CONFLICT — another writer bumped the claim version,
   * so the form's expectedVersion went stale. Set after the refetch, cleared
   * on the next full load; the retry goes out against the fresh version.
   */
  protected readonly conflictNotice = signal<string | null>(null);
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

  // V18: the stage the workspace shows. Defaults to the claim's true stage;
  // the stepper lets the adjuster revisit earlier stages read-only to update
  // their view of what happened (forms stay gated on the true stage).
  protected readonly viewedStage = signal<Stage | null>(null);
  protected readonly sendingBack = signal(false);
  protected sendBackRationale = '';
  protected showSendBackForm = false;

  // Verification inputs (per-row completion + the extra follow-up form).
  // Jira-style checklist: open rows expand in place (completed rows collapse
  // to one line); expansion is local UI state keyed by verification id, so a
  // reload keeps whichever rows the adjuster opened.
  protected readonly verExpanded: Record<number, boolean> = {};
  protected extraVerType: 'DIGITAL' | 'PHYSICAL' | 'DOCUMENT' | 'CLAUSE' = 'DIGITAL';
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
  /** S4: advisory doc-type for the upload (a required-doc docKey, or 'Other'). */
  protected attachDocType = 'Other';
  /** S4: id (as string) of the existing attachment this upload supersedes. */
  protected attachReplaces = '';

  constructor() {
    void this.load();
  }

  coverageText(): string {
    const coverage = this.view()?.coverage;
    return coverage == null ? '' : JSON.stringify(coverage, null, 2);
  }

  /** Coverage-only facts for the summary row (policy/product/holder are
   * their own facts in the template — known keys get rows, anything else
   * collapses to one line). */
  protected coverageFacts(): Array<{ label: string; value: string }> {
    const coverage = this.view()?.coverage;
    const facts: Array<{ label: string; value: string }> = [];
    if (coverage != null && typeof coverage === 'object' && !Array.isArray(coverage)) {
      const known = new Set(['type', 'plan', 'sum_insured']);
      const record = coverage as Record<string, unknown>;
      const type = record['type'];
      if (typeof type === 'string' && type.trim()) {
        facts.push({ label: 'Cover type', value: type });
      }
      const plan = record['plan'];
      if (typeof plan === 'string' && plan.trim()) {
        facts.push({ label: 'Plan', value: plan });
      }
      const sum = record['sum_insured'];
      if (typeof sum === 'number') {
        facts.push({ label: 'Sum insured', value: this.money(sum) });
      }
      const rest = Object.entries(record)
        .filter(([key]) => !known.has(key))
        .map(([key, value]) => `${key}: ${coverageScalar(value)}`);
      if (rest.length > 0) {
        facts.push({ label: 'Other', value: rest.join(' · ') });
      }
    }
    return facts;
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

  protected stageIndex(): number {
    return this.stage() === 'REVIEW' ? 0 : this.stage() === 'VERIFICATION' ? 1 : 2;
  }

  protected stageReached(step: Stage): boolean {
    const order: Stage[] = ['REVIEW', 'VERIFICATION', 'DECISION'];
    return order.indexOf(step) <= order.indexOf(this.stage());
  }

  /**
   * V18: the stage on screen — the adjuster's chosen revisit, or the claim's
   * true stage. Revisit is read-only context (earlier work, figures so far);
   * actions stay on the true stage, so nothing can be edited out of order.
   */
  protected shownStage(): Stage {
    return this.viewedStage() ?? this.stage();
  }

  protected viewStage(step: Stage): void {
    this.error.set(null);
    this.viewedStage.set(step === this.stage() ? null : step);
  }

  protected backToCurrentStage(): void {
    this.error.set(null);
    this.viewedStage.set(null);
  }

  protected isViewingEarlier(): boolean {
    return this.viewedStage() != null && this.viewedStage() !== this.stage();
  }

  /** One-line summary of what an earlier stage holds (for the revisit panels). */
  protected revisitSummary(step: Stage): string {
    switch (step) {
      case 'REVIEW':
        return 'Covers filed, claimed amounts and limits — the triage picture.';
      case 'VERIFICATION':
        return `${this.checklistDone()} of ${this.checklistTotal()} checks complete.`;
      case 'DECISION':
        return `Assessed ${this.money(this.assessedTotal())} so far.`;
    }
  }

  /** Send-back target label: one step back from the true stage. */
  protected sendBackTarget(): Stage {
    return this.stage() === 'DECISION' ? 'VERIFICATION' : 'REVIEW';
  }

  protected sendBackTargetLabel(): string {
    return this.sendBackTarget() === 'VERIFICATION' ? 'verification' : 'review';
  }

  /** V18: send the claim one step back with a reason (proposals clear, history stays). */
  async sendBack() {
    this.error.set(null);
    this.decisionResult.set(null);
    if (!this.sendBackRationale.trim()) {
      this.error.set('A reason is required to send a claim back.');
      return;
    }
    this.sendingBack.set(true);
    const headers = await this.authHeaders();
    if (!headers) {
      this.sendingBack.set(false);
      return;
    }
    try {
      const view = await firstValueFrom(
        this.http.post<StagedView>(
          `/api/claims/${this.claimNumber}/send-back`,
          { rationale: this.sendBackRationale.trim() },
          { headers },
        ),
      );
      this.sendBackRationale = '';
      this.showSendBackForm = false;
      this.viewedStage.set(null);
      this.toasts.success(`Sent back to ${this.sendBackTargetLabel()}.`);
      this.applyView(view);
      await this.loadTimeline(headers);
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not send the claim back.'));
    } finally {
      this.sendingBack.set(false);
    }
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

  /** V17: documents linked to one verification check (per-check evidence). */
  protected verAttachments(id: number): AttachmentView[] {
    return this.attachmentViews().filter((a) => a.verificationId === id);
  }

  /** Timeline rows: the unified feed (empty until loaded — never null). */
  protected timelineEntries(): TimelineEntry[] {
    return this.timeline();
  }

  /** Feed filter — frontend-only, the feed itself stays chronological. */
  protected readonly timelineFilter = signal<'ALL' | 'NOTES' | 'DOCUMENTS' | 'CHECKS' | 'MILESTONES'>('ALL');

  protected setTimelineFilter(filter: 'ALL' | 'NOTES' | 'DOCUMENTS' | 'CHECKS' | 'MILESTONES'): void {
    this.timelineFilter.set(filter);
  }

  /** Visual group for a feed row: notes read, documents download, checks track, rest is history. */
  protected timelineGroup(kind: string): 'note' | 'document' | 'check' | 'milestone' {
    switch (kind) {
      case 'NOTE':
        return 'note';
      case 'DOCUMENT':
        return 'document';
      case 'VERIFICATION_OPENED':
      case 'VERIFICATION_COMPLETED':
        return 'check';
      default:
        return 'milestone';
    }
  }

  protected filteredTimeline(): TimelineEntry[] {
    const filter = this.timelineFilter();
    if (filter === 'ALL') {
      return this.timeline();
    }
    return this.timeline().filter((e) => {
      const group = this.timelineGroup(e.kind);
      switch (filter) {
        case 'NOTES':
          return group === 'note';
        case 'DOCUMENTS':
          return group === 'document';
        case 'CHECKS':
          return group === 'check';
        case 'MILESTONES':
          return group === 'milestone';
      }
    });
  }

  protected timelineCount(group: 'ALL' | 'NOTES' | 'DOCUMENTS' | 'CHECKS' | 'MILESTONES'): number {
    if (group === 'ALL') {
      return this.timeline().length;
    }
    const want =
      group === 'NOTES'
        ? 'note'
        : group === 'DOCUMENTS'
          ? 'document'
          : group === 'CHECKS'
            ? 'check'
            : 'milestone';
    return this.timeline().filter((e) => this.timelineGroup(e.kind) === want).length;
  }

  /** Avatar initials for a feed actor ("Aisha Verma" → "AV", claimant keeps the dot). */
  protected timelineInitials(actor: string | null | undefined): string {
    if (!actor) {
      return '•';
    }
    const clean = actor.replace(' (claimant)', '').trim();
    const parts = clean.split(/\s+/).filter(Boolean);
    if (parts.length === 0) {
      return '•';
    }
    if (parts.length === 1) {
      return parts[0].slice(0, 2).toUpperCase();
    }
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }

  protected timelineIsClaimant(actor: string | null | undefined): boolean {
    return (actor ?? '').includes('(claimant)');
  }

  /** Friendly label for a timeline row kind. */
  protected timelineKindLabel(kind: string): string {
    switch (kind) {
      case 'FILED':
        return 'Filed';
      case 'NOTE':
        return 'Note';
      case 'DOCUMENT':
        return 'Document';
      case 'VERIFICATION_OPENED':
        return 'Check opened';
      case 'VERIFICATION_COMPLETED':
        return 'Check completed';
      default:
        return 'Update';
    }
  }

  protected timelineKindClass(kind: string): string {
    switch (kind) {
      case 'FILED':
        return 'badge badge--info';
      case 'NOTE':
        return 'badge badge--neutral';
      case 'DOCUMENT':
        return 'badge badge--special';
      case 'VERIFICATION_OPENED':
      case 'VERIFICATION_COMPLETED':
        return 'badge badge--success';
      default:
        return 'badge badge--neutral';
    }
  }

  protected timelineTime(value: string | null | undefined): string {
    return formatDateTime(value);
  }

  /** Download a timeline document row by its attachment id. */
  protected timelineDoc(entry: TimelineEntry): AttachmentView | null {
    if (entry.attachmentId == null) {
      return null;
    }
    return this.attachmentViews().find((a) => a.id === entry.attachmentId) ?? null;
  }

  /** Display name: the human label when set, else the stored filename. */
  protected docName(attachment: AttachmentView): string {
    return attachment.label?.trim() ? attachment.label : attachment.originalName;
  }

  /** S4: doc-type options = the claim's required docs (docKey value) + "Other". */
  protected attachDocTypeOptions(): Array<{ value: string; label: string }> {
    const options = this.requiredDocs().map((row) => ({
      value: row.docKey,
      label: row.displayName,
    }));
    options.push({ value: 'Other', label: 'Other' });
    return options;
  }

  /** S4: existing attachments on the claim, offered as "replaces" targets. */
  protected attachReplacesOptions(): AttachmentView[] {
    return this.attachmentViews();
  }

  /** S4: name shown for one "replaces" option (filename, never blank). */
  protected attachReplacesName(attachment: AttachmentView): string {
    return attachment.originalName?.trim()
      ? attachment.originalName
      : (attachment.label?.trim() ? attachment.label : `Attachment #${attachment.id}`);
  }

  /** S4: parses "(supersedes: <originalName>)" from backend timeline text. */
  protected supersedesName(detail: string | null | undefined): string | null {
    if (!detail) {
      return null;
    }
    const match = /\(supersedes:\s*([^)]+)\)/.exec(detail);
    const name = match?.[1]?.trim();
    return name ? name : null;
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
    this.conflictNotice.set(null);
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
      // V17: the timeline loads with the claim (same auth, best-effort — the
      // workspace works without it, milestones still show in the audit panel).
      void this.loadTimeline(headers);
      // S3: the required-documents checklist (same auth, best-effort — the
      // workspace works without it).
      void this.loadRequiredDocs(headers);
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

  /** S5: the version the current form state was loaded at (body field `expectedVersion`). */
  private loadedVersion(): number | null {
    return this.view()?.version ?? null;
  }

  /** S5: the single 409 shape the backend returns on a stale write. */
  private isConflict(err: unknown): boolean {
    if (err instanceof HttpErrorResponse && err.status === 409) {
      const body = err.error as { error?: string } | null;
      return body != null && typeof body === 'object' && body.error === 'CONFLICT';
    }
    return false;
  }

  /**
   * S5: on 409, refetch the claim (the retry then goes out against the fresh
   * version) and raise the warning banner. Returns true when the error was a
   * conflict (handled); false otherwise.
   */
  private async handleConflict(headers: HttpHeaders, err: unknown): Promise<boolean> {
    if (!this.isConflict(err)) {
      return false;
    }
    try {
      await this.reloadStaged(headers);
      await this.loadTimeline(headers);
    } catch {
      // The banner still explains what happened even when the refetch fails.
    }
    this.conflictNotice.set('Someone changed this claim — reloaded the latest');
    return true;
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

  /** V17: refreshes the unified timeline feed (called after every write). */
  private async loadTimeline(headers: HttpHeaders): Promise<void> {
    try {
      const feed = await firstValueFrom(
        this.http.get<TimelineEntry[]>(`/api/claims/${this.claimNumber}/timeline`, {
          headers,
        }),
      );
      this.timeline.set(feed ?? []);
    } catch {
      this.timeline.set([]);
    }
  }

  // --- required documents (S3) -------------------------------------------------

  /** Received over total on the staff checklist (WAIVED counts as decided, not received). */
  protected reqDocsReceived(): number {
    return this.requiredDocs().filter((d) => d.status === 'RECEIVED').length;
  }

  protected reqDocsTotal(): number {
    return this.requiredDocs().length;
  }

  /** Link-target attachments for one row: claim files not verification-bound. */
  protected reqDocCandidates(): AttachmentView[] {
    return this.attachmentViews().filter((a) => a.verificationId == null);
  }

  /** Label for the link picker option (human label, else filename). */
  protected reqDocOptionName(attachment: AttachmentView): string {
    return this.docName(attachment);
  }

  private async loadRequiredDocs(headers: HttpHeaders): Promise<void> {
    this.reqDocsError.set(null);
    try {
      const res = await firstValueFrom(
        this.http.get<{
          documentsReceived: number;
          documentsTotal: number;
          items: RequiredDocRow[];
        }>(`/api/claims/${this.claimNumber}/required-documents`, { headers }),
      );
      this.requiredDocs.set(res?.items ?? []);
    } catch {
      this.requiredDocs.set([]);
      this.reqDocsError.set('The required-documents checklist could not be loaded.');
    }
  }

  /** Assignee-only: link a claim file as the evidence for one checklist row. */
  async linkRequiredDoc(checkId: number) {
    const raw = (this.reqLinkFor[checkId] ?? '').trim();
    if (!raw) {
      this.error.set('Choose a document to link.');
      return;
    }
    this.error.set(null);
    this.reqDocsBusy.set(true);
    const headers = await this.authHeaders();
    if (!headers) {
      this.reqDocsBusy.set(false);
      return;
    }
    try {
      await firstValueFrom(
        this.http.post(
          `/api/claims/${this.claimNumber}/required-documents/${checkId}/link`,
          { attachmentId: Number(raw) },
          { headers },
        ),
      );
      this.reqLinkFor[checkId] = '';
      this.toasts.success('Document linked.');
      await this.loadRequiredDocs(headers);
      await this.loadTimeline(headers);
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not link the document.'));
    } finally {
      this.reqDocsBusy.set(false);
    }
  }

  /** Assignee-only: waive one checklist row with a rationale. */
  async waiveRequiredDoc(checkId: number) {
    const rationale = (this.reqWaiveFor[checkId] ?? '').trim();
    if (!rationale) {
      this.error.set('A rationale is required to waive a required document.');
      return;
    }
    this.error.set(null);
    this.reqDocsBusy.set(true);
    const headers = await this.authHeaders();
    if (!headers) {
      this.reqDocsBusy.set(false);
      return;
    }
    try {
      await firstValueFrom(
        this.http.post(
          `/api/claims/${this.claimNumber}/required-documents/${checkId}/waive`,
          { rationale },
          { headers },
        ),
      );
      this.reqWaiveFor[checkId] = '';
      this.showWaiveFor[checkId] = false;
      this.toasts.success('Document waived.');
      await this.loadRequiredDocs(headers);
      await this.loadTimeline(headers);
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not waive the document.'));
    } finally {
      this.reqDocsBusy.set(false);
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

  /** A row starts expanded while it still needs work, collapsed once done. */
  protected isVerExpanded(ver: VerificationView): boolean {
    const manual = this.verExpanded[ver.id];
    if (manual !== undefined) {
      return manual;
    }
    return ver.status !== 'COMPLETE' && ver.status !== 'CANCELLED';
  }

  protected toggleVerExpanded(ver: VerificationView): void {
    this.verExpanded[ver.id] = !this.isVerExpanded(ver);
  }

  /** Jira-subtask style status word for a checklist row. */
  protected verState(ver: VerificationView): 'done' | 'working' | 'todo' | 'cancelled' {
    if (ver.status === 'COMPLETE') {
      return 'done';
    }
    if (ver.status === 'CANCELLED') {
      return 'cancelled';
    }
    return this.isVerExpanded(ver) ? 'working' : 'todo';
  }

  protected verStateLabel(ver: VerificationView): string {
    switch (this.verState(ver)) {
      case 'done':
        return ver.outcome ?? 'Done';
      case 'working':
        return 'In progress';
      case 'cancelled':
        return 'Cancelled';
      default:
        return 'To do';
    }
  }

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
      // Done rows collapse to one line — the checklist stays scannable.
      this.verExpanded[id] = false;
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
          {
            covers,
            rationale: this.assessmentRationale.trim(),
            expectedVersion: this.loadedVersion(),
          },
          { headers },
        ),
      );
      this.toasts.success('Assessment recorded — claim moved to decision.');
      this.applyView(view);
    } catch (err) {
      if (await this.handleConflict(headers, err)) {
        return;
      }
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
      // adjuster's decision is the other one. Both are S5 version-checked, so
      // both send the loaded version.
      const path = this.supervisorEscalationDecision()
        ? `/api/claims/${this.claimNumber}/escalation-decision`
        : `/api/claims/${this.claimNumber}/decision`;
      const outcome = await firstValueFrom(
        this.http.post<ClaimDecisionView>(
          path,
          { ...body, expectedVersion: this.loadedVersion() },
          { headers },
        ),
      );
      this.decisionResult.set(this.describe(outcome));
      if (outcome.escalatedTo == null) {
        // The claim closed (approved/denied); the assignee can still open it, now CLOSED.
        await this.load();
      }
    } catch (err) {
      if (await this.handleConflict(headers, err)) {
        return;
      }
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
        this.http.post<StagedView>(
          path,
          {
            covers,
            rationale: this.decisionRationale.trim(),
            expectedVersion: this.loadedVersion(),
          },
          {
            headers,
          },
        ),
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
      if (await this.handleConflict(headers, err)) {
        return;
      }
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
   * a File, so the element is the source of truth. Pass a verification id to
   * link the file to one check (per-check evidence on the timeline).
   */
  async uploadDoc(fileInput: HTMLInputElement, verificationId?: number) {
    this.error.set(null);
    const file = fileInput.files?.[0];
    if (!file) {
      this.error.set('Choose a file to attach.');
      return;
    }
    if (!isEvidenceFile(file)) {
      this.error.set(`"${file.name}" must be image or PDF files.`);
      return;
    }
    if (file.size > MAX_DOC_MB * 1024 * 1024) {
      this.error.set(`"${file.name}" is over ${MAX_DOC_MB} MB — choose a smaller file.`);
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
      if (verificationId != null) {
        form.append('verificationId', String(verificationId));
      }
      // S4: advisory docType + supersede link (backend param names: docType, replacesId).
      if (this.attachDocType && this.attachDocType !== 'Other') {
        form.append('docType', this.attachDocType);
      }
      const replacesId = Number(this.attachReplaces);
      if (this.attachReplaces.trim() && Number.isFinite(replacesId)) {
        form.append('replacesId', String(replacesId));
      }
      await firstValueFrom(
        this.http.post(`/api/claims/${this.claimNumber}/attachments`, form, {
          headers,
        }),
      );
      fileInput.value = '';
      this.attachReplaces = '';
      this.toasts.success('Document attached.');
      await this.mergeAttachments(headers);
      await this.loadTimeline(headers);
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
          { amount, expectedVersion: this.loadedVersion() },
          { headers },
        ),
      );
      this.applyView(view);
      this.toasts.success('Reserve saved.');
    } catch (err) {
      if (await this.handleConflict(headers, err)) {
        return;
      }
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

function actionClass(action: string): string {  switch (action) {
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

/** One-line scalar for an unknown coverage key (objects/arrays collapse). */
function coverageScalar(value: unknown): string {
  if (value == null) {
    return '—';
  }
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  return '…';
}
