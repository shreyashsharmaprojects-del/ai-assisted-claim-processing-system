/**
 * Centralized display formatters for the claims workspace.
 *
 * One place formats every date, amount, and identifier — never inline in a
 * component. Rules follow the enterprise-ui screen-patterns §1:
 * unambiguous dates, right-aligned tabular money with the symbol always
 * shown, em dashes for empty values.
 *
 * S11 (V3): every formatter is locale-aware via the module-level LOCALE (read from
 * the runtime app config, default 'en-GB'). Components must use these helpers —
 * never `₹`-concat, `toFixed(2)`, or hand-rolled date slicing.
 */

/** Display locale: the runtime app config's locale, defaulting to en-GB. */
export const LOCALE: string =
  typeof (globalThis as unknown as { __CLAIMS_LOCALE__?: unknown }).__CLAIMS_LOCALE__ === 'string'
    ? ((globalThis as unknown as { __CLAIMS_LOCALE__: string }).__CLAIMS_LOCALE__ as string)
    : 'en-GB';

const DATE_FMT = new Intl.DateTimeFormat(LOCALE, {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
});

const DATETIME_FMT = new Intl.DateTimeFormat(LOCALE, {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

const MONEY_FMT = new Intl.NumberFormat(LOCALE, {
  style: 'currency',
  currency: 'INR',
});

/** ISO `yyyy-MM-dd` (or datetime) -> locale date, e.g. `01 Sept 2026`. Falls back to raw. */
export function formatDate(value: string | null | undefined): string {
  if (value == null || String(value).trim() === '') {
    return '—';
  }
  const raw = String(value).trim();
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(raw);
  if (m) {
    const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
    if (!Number.isNaN(d.getTime())) {
      return DATE_FMT.format(d);
    }
  }
  const d = new Date(raw);
  if (!Number.isNaN(d.getTime())) {
    return DATE_FMT.format(d);
  }
  return raw;
}

/** ISO datetime -> locale date + time, e.g. `01 Sept 2026, 14:32`. Date-only renders as dates. */
export function formatDateTime(value: string | null | undefined): string {
  if (value == null || String(value).trim() === '') {
    return '—';
  }
  const raw = String(value).trim();
  if (!raw.includes('T')) {
    return formatDate(raw);
  }
  const d = new Date(raw);
  if (Number.isNaN(d.getTime())) {
    return formatDate(raw);
  }
  return DATETIME_FMT.format(d);
}

/** Amount in rupees, locale currency shape (en-GB: `₹1,500.00`). Null -> em dash. */
export function formatMoney(amount: number | null | undefined): string {
  if (amount == null || Number.isNaN(amount)) {
    return '—';
  }
  return MONEY_FMT.format(amount);
}

/**
 * Export/query-param date: the API contract stays `yyyy-MM-dd` (zero-padded, UTC) —
 * centralized here so components never slice ISO strings inline.
 */
export function formatISODate(date: Date): string {
  const y = date.getUTCFullYear();
  const m = String(date.getUTCMonth() + 1).padStart(2, '0');
  const d = String(date.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/**
 * Plain two-decimal editor text for a numeric limit (no currency symbol — numeric
 * inputs carry the symbol in their label). Centralized here so no component
 * hand-rolls `toFixed(2)` — the only `toFixed` in the app lives in this file.
 */
export function plainAmount(value: number): string {
  if (!Number.isFinite(value)) {
    return '';
  }
  return value.toFixed(2);
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
