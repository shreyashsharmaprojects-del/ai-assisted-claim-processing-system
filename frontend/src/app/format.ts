/**
 * Centralized display formatters for the claims workspace.
 *
 * One place formats every date, amount, and identifier — never inline in a
 * component. Rules follow the enterprise-ui screen-patterns §1:
 * unambiguous dates, right-aligned tabular money with the symbol always
 * shown, em dashes for empty values.
 */

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** `2026-09-01` or ISO datetime -> `01 Sep 2026`. Falls back to the raw value. */
export function formatDate(value: string | null | undefined): string {
  if (value == null || String(value).trim() === '') {
    return '—';
  }
  const raw = String(value).trim();
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(raw);
  if (m) {
    const month = MONTHS[Number(m[2]) - 1];
    if (month) {
      return `${m[3]} ${month} ${m[1]}`;
    }
  }
  const d = new Date(raw);
  if (!Number.isNaN(d.getTime())) {
    return `${String(d.getDate()).padStart(2, '0')} ${MONTHS[d.getMonth()]} ${d.getFullYear()}`;
  }
  return raw;
}

/** ISO datetime -> `01 Sep 2026, 14:32`. Date-only values render as dates. */
export function formatDateTime(value: string | null | undefined): string {
  if (value == null || String(value).trim() === '') {
    return '—';
  }
  const raw = String(value).trim();
  const d = new Date(raw.includes('T') ? raw : raw + 'T00:00:00');
  if (Number.isNaN(d.getTime())) {
    return formatDate(raw);
  }
  if (!raw.includes('T')) {
    return formatDate(raw);
  }
  return `${formatDate(raw)}, ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/** Amount in rupees with two decimals: `₹1500.00`. Null -> em dash. */
export function formatMoney(amount: number | null | undefined): string {
  if (amount == null || Number.isNaN(amount)) {
    return '—';
  }
  return '₹' + amount.toFixed(2);
}

/** Whole days between the given ISO date and now, for queue aging. Null-safe. */
export function ageInDays(value: string | null | undefined): number | null {
  if (value == null || String(value).trim() === '') {
    return null;
  }
  const d = new Date(String(value).includes('T') ? String(value) : String(value) + 'T00:00:00');
  if (Number.isNaN(d.getTime())) {
    return null;
  }
  return Math.max(0, Math.floor((Date.now() - d.getTime()) / 86_400_000));
}

/** `14 days` / `Today` / em dash — the queue Age column. */
export function formatAge(value: string | null | undefined): string {
  const days = ageInDays(value);
  if (days == null) {
    return '—';
  }
  if (days === 0) {
    return 'Today';
  }
  return `${days} day${days === 1 ? '' : 's'}`;
}

/** Empty-value guard: blank strings render as an em dash, never empty. */
export function orDash(value: string | null | undefined): string {
  if (value == null || String(value).trim() === '') {
    return '—';
  }
  return String(value);
}
