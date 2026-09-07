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
      return 'neutral';
    case 'UNDER_REVIEW':
      return 'info';
    case 'APPROVED':
      return 'success';
    case 'DENIED':
      return 'danger';
    case 'ESCALATED_SUPERVISOR':
      return 'special';
    case 'CLOSED':
    default:
      return 'neutral';
  }
}

/** e.g. 'UNDER_REVIEW' -> 'badge badge--info' (see global styles.css badge variants). */
export function badgeClass(status: string | null | undefined): string {
  return 'badge badge--' + statusKind(status);
}
