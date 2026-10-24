import type { Severity } from '../api/types';

const CLASS: Record<Severity, string> = {
  HIGH: 'badge--high',
  MEDIUM: 'badge--medium',
  LOW: 'badge--low',
};

/**
 * A severity label. Colour carries the meaning but never alone - the word is always there too,
 * which is what makes it readable to someone who cannot distinguish the three hues.
 *
 * An unrecognised value renders in neutral grey rather than throwing: severities come from the
 * backend, and a new rule type should not blank the page.
 */
export function SeverityBadge({ severity }: { severity: Severity | string }) {
  const className = CLASS[severity as Severity] ?? 'badge--unknown';
  return <span className={`badge ${className}`}>{severity}</span>;
}
