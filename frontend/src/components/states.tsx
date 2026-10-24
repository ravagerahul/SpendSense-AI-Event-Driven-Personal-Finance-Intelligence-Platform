import { ApiError } from '../api/client';

/**
 * The three states every page is in when it is not showing data. Kept together because they
 * share one vertical rhythm: swapping between them must not shift the page.
 */

export function LoadingState({ label = 'Loading', rows = 3 }: { label?: string; rows?: number }) {
  return (
    // aria-busy plus a visually-hidden label, so a screen reader is told the page is working
    // rather than being read an empty region.
    <div className="state" role="status" aria-busy="true" aria-label={label}>
      {Array.from({ length: rows }, (_, i) => (
        <span
          className="skeleton"
          key={i}
          // Uneven widths; a stack of identical bars looks like a rendering fault.
          style={{ width: `${[72, 54, 63][i % 3]}%` }}
        />
      ))}
    </div>
  );
}

/**
 * A failed request. The correlation id is the reason this component exists: the same id is on
 * the BFF's log line and on every service it called, so what the user saw can be found in the
 * logs without guessing at timestamps.
 */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const apiError = error instanceof ApiError ? error : null;
  const message =
    apiError?.message ?? (error instanceof Error ? error.message : 'Something went wrong.');

  return (
    <div className="state state--error" role="alert">
      <span className="state__title">
        {apiError?.status ? `Request failed (${apiError.status})` : 'Request failed'}
      </span>
      <p className="state__detail">{message}</p>

      {apiError?.correlationId && (
        <>
          <code className="state__trace">correlationId: {apiError.correlationId}</code>
          <span className="state__hint">
            Search the service logs for this id to see the full path of this request.
          </span>
        </>
      )}

      {onRetry && (
        <button type="button" className="button" onClick={onRetry} style={{ marginTop: '0.5rem' }}>
          Try again
        </button>
      )}
    </div>
  );
}

/**
 * Nothing to show. Distinguished from an error on purpose: "no anomalies" is good news, and
 * rendering it like a failure trains people to ignore real failures.
 */
export function EmptyState({ title, detail }: { title: string; detail?: string }) {
  return (
    <div className="state">
      <span className="state__title">{title}</span>
      {detail && <p className="state__detail">{detail}</p>}
    </div>
  );
}
