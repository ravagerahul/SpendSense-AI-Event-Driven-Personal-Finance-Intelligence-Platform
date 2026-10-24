import { useCallback, useState } from 'react';
import { acknowledgeAnomaly, getAnomalies } from '../api/dashboard';
import { ApiError } from '../api/client';
import type { AnomalyGroup } from '../api/types';
import { PageHeader } from '../components/Layout';
import { SeverityBadge } from '../components/SeverityBadge';
import { EmptyState, ErrorState, LoadingState } from '../components/states';
import { formatCurrency, formatDate } from '../lib/format';
import { useAsync } from '../lib/useAsync';

export function AnomaliesPage() {
  const [includeAcknowledged, setIncludeAcknowledged] = useState(false);
  const [failure, setFailure] = useState<unknown>();

  const load = useCallback(() => getAnomalies(includeAcknowledged), [includeAcknowledged]);
  const { data, error, loading, reload } = useAsync(load);

  async function acknowledge(anomalyId: string) {
    setFailure(undefined);
    try {
      await acknowledgeAnomaly(anomalyId);
      // Refetched rather than patched locally, because acknowledging one of a transaction's
      // findings changes whether the card still belongs in the list at all - and that decision
      // is the server's.
      reload();
    } catch (err) {
      setFailure(err);
    }
  }

  return (
    <>
      <PageHeader
        title="Anomalies"
        description="Unusual activity, grouped by transaction. One purchase can trip several rules, and each finding stands on its own."
      />

      <label className="toggle">
        <input
          type="checkbox"
          checked={includeAcknowledged}
          onChange={(e) => setIncludeAcknowledged(e.target.checked)}
        />
        Show acknowledged findings
      </label>

      {failure !== undefined && (
        <div className="turn__failure" role="alert">
          <p>
            {failure instanceof ApiError ? failure.message : 'Could not acknowledge that finding.'}
          </p>
          {failure instanceof ApiError && failure.correlationId && (
            <code className="state__trace">correlationId: {failure.correlationId}</code>
          )}
        </div>
      )}

      {loading && <LoadingState label="Loading anomalies" />}
      {!loading && error && <ErrorState error={error} onRetry={reload} />}

      {!loading && !error && data && data.length === 0 && (
        <EmptyState
          title={includeAcknowledged ? 'No anomalies recorded' : 'Nothing outstanding'}
          detail={
            includeAcknowledged
              ? 'No transaction has tripped an anomaly rule.'
              : 'Every finding has been acknowledged. Turn on the toggle above to see them.'
          }
        />
      )}

      {!loading &&
        !error &&
        data?.map((group) => (
          <AnomalyCard key={group.transactionId} group={group} onAcknowledge={acknowledge} />
        ))}
    </>
  );
}

function AnomalyCard({
  group,
  onAcknowledge,
}: {
  group: AnomalyGroup;
  onAcknowledge: (anomalyId: string) => void;
}) {
  return (
    <article className={`card card--${group.highestSeverity?.toLowerCase() ?? 'unknown'}`}>
      <div className="card__head">
        <div>
          <span className="card__merchant">{group.merchant ?? 'Unknown merchant'}</span>
          <span className="card__meta">
            {formatDate(group.transactionDate)}
            {group.category && ` · ${group.category}`}
          </span>
        </div>
        <div className="card__right">
          <span className="card__amount numeric">{formatCurrency(group.amount)}</span>
          {/* The card takes the worst finding's severity: a transaction that is both HIGH and
              LOW is a HIGH problem. */}
          <SeverityBadge severity={group.highestSeverity} />
        </div>
      </div>

      {group.anomalyCount > 1 && (
        <p className="card__count">
          {group.anomalyCount} findings on this one transaction
        </p>
      )}

      <ul className="findings">
        {group.anomalies.map((a) => (
          <li className={`finding${a.acknowledged ? ' finding--done' : ''}`} key={a.anomalyId}>
            <div className="finding__head">
              <span className="finding__type">{a.type.replace(/_/g, ' ')}</span>
              <SeverityBadge severity={a.severity} />
            </div>
            {/* The backend writes these for people to read. Restating them here would only
                lose the numbers they already contain. */}
            <p className="finding__reason">{a.reason}</p>
            <div className="finding__foot">
              {a.acknowledged ? (
                <span className="finding__done">Acknowledged</span>
              ) : (
                <button
                  type="button"
                  className="button button--small"
                  // The anomaly's own id, not the transaction's: acknowledging one finding
                  // must leave the other standing.
                  onClick={() => onAcknowledge(a.anomalyId)}
                >
                  Acknowledge
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>
    </article>
  );
}
