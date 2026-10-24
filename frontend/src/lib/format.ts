/**
 * Formatting helpers. Centralised because a number rendered two different ways on two screens
 * reads as two different numbers.
 */

/**
 * Indian digit grouping: ₹1,23,456.00, not ₹123,456.00. The last three digits group together
 * and everything above them groups in pairs (lakh, crore), which `en-IN` implements for us -
 * hand-rolled grouping gets this wrong above 99,999 almost every time.
 */
const inr = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function formatCurrency(value: number | string | null | undefined): string {
  const n = toNumber(value);
  // An em dash, not ₹0.00: a missing amount and a zero amount are different facts.
  if (n === null) return '—';
  return inr.format(n);
}

/** Whole rupees, for axis ticks and other places where the paise are noise. */
const inrCompact = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 0,
});

export function formatCurrencyShort(value: number | string | null | undefined): string {
  const n = toNumber(value);
  if (n === null) return '—';
  return inrCompact.format(n);
}

/** Signed, so a change reads as a direction and not just a magnitude. */
export function formatPercentChange(value: number | null | undefined): string {
  if (value === null || value === undefined) return 'no comparison';
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toFixed(1)}%`;
}

export function formatPercent(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${value.toFixed(1)}%`;
}

/** A plain date, parsed as a local calendar date rather than a UTC instant. */
export function formatDate(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value.length === 10 ? `${value}T00:00:00` : value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  }).format(date);
}

/**
 * Postgres numerics arrive as numbers through JSON, but a string is possible if a column is
 * ever read as text, so both are accepted rather than silently rendering "NaN".
 */
function toNumber(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}
