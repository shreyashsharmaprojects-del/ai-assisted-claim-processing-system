/**
 * Centralized presentation mapping for lifecycle states (+ trivially shared
 * presentation helpers).
 *
 * One place decides which semantic badge kind each state renders as — never per-screen
 * color overrides. Display labels are humanized sentence case derived 1:1 from the
 * verbatim token (`UNDER_REVIEW` -> "Under review", doctrine §5); that is formatting,
 * not paraphrase — logic, logs, and API payloads keep raw tokens.
 *
 * Also home to the trivially shared presentation helpers that were triplicated
 * across screens (evidence allowlist + ceiling, locale currency symbol, blob
 * download) so later screen passes can adopt them without touching behaviour.
 * Screen-specific constants with divergent values (DOC_LABELS, MAX_PHOTOS,
 * QueueClaimView) stay in their screens — see the shell report.
 */
import { formatMoney } from './format';

export type StatusKind = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'special';

/** Doctrine §5 central badge map. Unknown tokens fall through to neutral. */
export function statusKind(status: string | null | undefined): StatusKind {
  switch (status) {
    case 'UNASSIGNED':
    case 'CLOSED':
    case 'RETIRED':
    case 'CANCELLED':
    case 'DRAFT':
    case 'ARCHIVED':
      return 'neutral';
    case 'UNDER_REVIEW':
    case 'IN_PROGRESS':
    case 'ASSIGNED':
    case 'SUBMITTED':
      return 'info';
    case 'APPROVED':
    case 'PARTIALLY_APPROVED':
    case 'ACTIVE':
    case 'SENT':
    case 'PAID':
    case 'SETTLED':
    case 'RECEIVED':
    case 'PASSED':
    case 'COMPLETE':
      return 'success';
    case 'NEED_INFO':
    case 'PENDING':
    case 'ON_HOLD':
    case 'AWAITING_DOCUMENTS':
    case 'DUE_SOON':
      return 'warning';
    case 'DENIED':
    case 'REJECTED':
    case 'EXPIRED':
    case 'FAILED':
    case 'OVERDUE':
    case 'BREACHING':
      return 'danger';
    case 'ESCALATED_SUPERVISOR':
    case 'INCONCLUSIVE':
    case 'WAIVED':
      return 'special';
    // Audit actions + verification/timeline kinds route through the one badge
    // map (owner fix-pass; doctrine §5 — never per-screen color overrides).
    // Success: terminal good writes; warning: referral/reassign churn;
    // info: workflow-forward moves + reserve; DOCUMENT falls to neutral via
    // default (same as the old local timelineKindClass).
    case 'DECISION':
    case 'ASSESSMENT_RECORDED':
    case 'VERIFICATION_CREATED':
    case 'VERIFICATION_UPDATED':
    case 'VERIFICATION_COMPLETED':
    case 'CLAIM_REOPENED':
    case 'PROPOSALS_SAVED':
      return 'success';
    case 'CLAIM_REFERRED':
    case 'CLAIM_ESCALATED':
    case 'CLAIM_REASSIGNED':
      return 'warning';
    case 'NEED_INFO_SENT':
    case 'NEED_INFO_RESPONDED':
    case 'STAGE_SENT_BACK':
    case 'REVIEW_ADVANCED':
    case 'RESERVE_SET':
    case 'VERIFICATION_OPENED':
    case 'FILED':
    case 'NOTE':
      return 'info';
    default:
      // Future ESCALATED_* variants (e.g. level escalations) fall through to the
      // supervisor purple — escalation is one concept, one color.
      if (status != null && status.startsWith('ESCALATED')) {
        return 'special';
      }
      return 'neutral';
  }
}

/** e.g. 'UNDER_REVIEW' -> 'badge badge--info' (see global styles.css badge variants). */
export function badgeClass(status: string | null | undefined): string {
  return 'badge badge--' + statusKind(status);
}

/**
 * Humanized sentence-case label derived 1:1 from the verbatim status token
 * (doctrine §5): `UNDER_REVIEW` -> "Under review", `NEED_INFO` -> "Need info",
 * `ESCALATED_SUPERVISOR` -> "Escalated supervisor". Null/blank -> em dash
 * (doctrine §6: empty is never blank). Logic keeps raw tokens.
 */
export function statusLabel(status: string | null | undefined): string {
  if (status == null) {
    return '—';
  }
  const words = String(status)
    .split('_')
    .filter((w) => w !== '');
  if (words.length === 0) {
    return '—';
  }
  return words
    .map((w, i) => (i === 0 ? w.charAt(0).toUpperCase() + w.slice(1).toLowerCase() : w.toLowerCase()))
    .join(' ');
}

/**
 * Evidence upload allowlist mirror: images + PDF. Was an identical private
 * function in fnol, claim-status, and claim-detail — one copy now.
 */
export function isEvidenceFile(file: File): boolean {
  return file.type.startsWith('image/') || file.type === 'application/pdf';
}

/**
 * Evidence upload ceiling. The three screens each hardcoded 10 (as MAX_DOC_MB
 * / MAX_PHOTO_MB); same value, one name going forward.
 */
export const MAX_EVIDENCE_MB = 10;
export const MAX_EVIDENCE_BYTES = MAX_EVIDENCE_MB * 1024 * 1024;

/**
 * Currency symbol derived from the locale formatter (en-GB: ₹) for static
 * labels. Was an identical method on fnol, claim-detail, and authority —
 * screens can delegate here. Never concatenate it with amounts; format
 * amounts with formatMoney.
 */
export function currencySymbol(): string {
  const shaped = formatMoney(0)
    .replace(/[\d.,\s ]+/g, '')
    .trim();
  return shaped === '' ? '₹' : shaped;
}

/**
 * Blob download honoring Content-Disposition, with a sane fallback name.
 * Moved verbatim from claim-detail (the only screen with export downloads);
 * other export buttons can reuse it instead of copying it.
 */
export function downloadBlob(blob: Blob, disposition: string | null, fallbackName: string): void {
  const name =
    /filename[^;=\n]*=((["'])(.*?)\2|([^;\n]*))/.exec(disposition ?? '')?.[3]?.trim() ||
    /filename[^;=\n]*=((["'])(.*?)\2|([^;\n]*))/.exec(disposition ?? '')?.[4]?.trim() ||
    fallbackName;
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = name;
  anchor.click();
  URL.revokeObjectURL(url);
}
