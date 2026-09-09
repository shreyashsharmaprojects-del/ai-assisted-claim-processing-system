/**
 * Centralized presentation mapping for claim lifecycle states.
 *
 * One place decides which semantic badge kind each state renders as — never per-screen
 * color overrides. The raw status token stays the badge label (operators read the state
 * machines in these exact terms); the kind drives color, the label is never color alone.
 */
export type StatusKind = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'special';

export function statusKind(status: string | null | undefined): StatusKind {
  switch (status) {
    case 'UNASSIGNED':
    case 'RETIRED':
      return 'neutral';
    case 'UNDER_REVIEW':
      return 'info';
    case 'NEED_INFO':
      return 'warning';
    case 'APPROVED':
    case 'ACTIVE':
    case 'SENT':
      return 'success';
    case 'PARTIALLY_APPROVED':
      return 'success';
    case 'DENIED':
    case 'EXPIRED':
    case 'FAILED':
      return 'danger';
    case 'PENDING':
      return 'warning';
    case 'ESCALATED_SUPERVISOR':
      return 'special';
    case 'CLOSED':
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
