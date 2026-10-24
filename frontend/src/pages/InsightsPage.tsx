import { useCallback } from 'react';
import { Link } from 'react-router-dom';
import { getInsights } from '../api/dashboard';
import { CategoryBars } from '../components/CategoryBars';
import { PageHeader } from '../components/Layout';
import { EmptyState, ErrorState, LoadingState } from '../components/states';
import { hasInsights } from '../api/types';
import { formatCurrency, formatDate, formatPercentChange } from '../lib/format';
import { useAsync } from '../lib/useAsync';

export function InsightsPage() {
  const { data, error, loading, reload } = useAsync(useCallback(() => getInsights(), []));

  if (loading) return <Frame><LoadingState label="Loading insights" /></Frame>;
  if (error) return <Frame><ErrorState error={error} onRetry={reload} /></Frame>;
  if (!data) return null;

  // The BFF returns a placeholder rather than an error before the first run, so an empty
  // state is the correct response here, not a failure.
  if (!hasInsights(data)) {
    return (
      <Frame>
        <EmptyState
          title="No insights generated yet"
          detail="Insights are generated weekly, on Sunday at midnight. They can also be produced on demand with POST /api/insights/generate."
        />
      </Frame>
    );
  }

  const anomalyCount = data.anomalies.length;

  return (
    <Frame>
      <p className="period">
        {formatDate(data.periodStart)} – {formatDate(data.periodEnd)}
        <span className="period__generated">generated {formatDate(data.generatedAt)}</span>
      </p>

      <section className="stat">
        <div className="stat__label">Total spend</div>
        <div className="stat__figure numeric">{formatCurrency(data.totalSpend)}</div>
        <div className="stat__sub">
          <Comparison changePct={data.changePct} previous={data.previousTotalSpend} />
        </div>
      </section>

      <section className="section">
        <h2>Top categories</h2>
        {data.topCategories.length > 0 ? (
          <CategoryBars slices={data.topCategories} />
        ) : (
          <EmptyState title="Nothing was spent in this period" />
        )}
      </section>

      <section className="section">
        <h2>Subscriptions</h2>
        {data.subscriptions.length > 0 ? (
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Merchant</th>
                <th scope="col" className="table__right">
                  Amount
                </th>
                <th scope="col" className="table__right">
                  Charges
                </th>
                <th scope="col">First seen</th>
                <th scope="col">Last seen</th>
              </tr>
            </thead>
            <tbody>
              {data.subscriptions.map((s) => (
                <tr key={`${s.merchant}-${s.amount}`}>
                  <td>{s.merchant}</td>
                  <td className="numeric table__right">{formatCurrency(s.amount)}</td>
                  <td className="numeric table__right">{s.occurrences}</td>
                  <td className="numeric">{formatDate(s.first_charge)}</td>
                  <td className="numeric">{formatDate(s.last_charge)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <EmptyState
            title="No recurring charges detected"
            detail="A merchant must charge the same amount on at least three distinct dates before it counts as a subscription. Two charges is a coincidence."
          />
        )}
      </section>

      <section className="section">
        <h2>Anomalies</h2>
        <p className="state__detail">
          {anomalyCount === 0 ? (
            'Nothing unusual in this period.'
          ) : (
            <>
              {anomalyCount} {anomalyCount === 1 ? 'anomaly was' : 'anomalies were'} recorded in
              this period. <Link to="/anomalies" className="link">Review them</Link>.
            </>
          )}
        </p>
      </section>
    </Frame>
  );
}

/**
 * The period-over-period comparison.
 *
 * A null changePct means the previous period had no spend at all. Rendering that as 0%, or as
 * a flat arrow, would assert that spending held steady - a claim the data does not support
 * and that happens to be false here. The absence of a baseline is itself the finding, so it
 * is stated.
 */
function Comparison({ changePct, previous }: { changePct: number | null; previous: number }) {
  if (changePct === null) {
    return (
      <>
        No comparison available
        <span className="stat__note">nothing was spent in the previous period</span>
      </>
    );
  }

  const direction = changePct > 0 ? 'more' : changePct < 0 ? 'less' : 'the same as';
  return (
    <>
      <span className="numeric">{formatPercentChange(changePct)}</span> {direction} than the
      previous period ({formatCurrency(previous)})
    </>
  );
}

function Frame({ children }: { children: React.ReactNode }) {
  return (
    <>
      <PageHeader
        title="Insights"
        description="The weekly summary: spend against the previous week, top categories and subscriptions."
      />
      {children}
    </>
  );
}
