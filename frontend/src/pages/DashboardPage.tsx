import { useCallback } from 'react';
import { getSummary } from '../api/dashboard';
import { CategoryBars } from '../components/CategoryBars';
import { PageHeader } from '../components/Layout';
import { EmptyState, ErrorState, LoadingState } from '../components/states';
import { formatCurrency, formatDate } from '../lib/format';
import { useAsync } from '../lib/useAsync';

export function DashboardPage() {
  const { data, error, loading, reload } = useAsync(useCallback(() => getSummary(), []));

  if (loading) return <Frame><LoadingState label="Loading this month's summary" /></Frame>;
  if (error) return <Frame><ErrorState error={error} onRetry={reload} /></Frame>;
  if (!data) return null;

  const hasSpend = data.transactionCount > 0;

  return (
    <Frame>
      <section className="stat">
        <div className="stat__label">Total spend</div>
        <div className="stat__figure numeric">{formatCurrency(data.totalSpend)}</div>
        <div className="stat__sub">
          {data.transactionCount} {data.transactionCount === 1 ? 'transaction' : 'transactions'}
          {' · '}
          {formatDate(data.periodStart)} – {formatDate(data.periodEnd)}
        </div>
      </section>

      <section className="section">
        <h2>Where it went</h2>
        {data.categoryBreakdown.length > 0 ? (
          <CategoryBars slices={data.categoryBreakdown} />
        ) : (
          <EmptyState
            title="No categorised spending this month"
            detail="Transactions appear here once categorization-service has assigned them a category."
          />
        )}
      </section>

      <section className="section">
        <h2>Recent transactions</h2>
        {data.recentTransactions.length > 0 ? (
          <ul className="feed">
            {data.recentTransactions.map((t) => (
              <li className="feed__row" key={t.id}>
                <span className="feed__date numeric">{formatDate(t.transaction_date)}</span>
                <span className="feed__merchant">{t.merchant ?? 'Unknown merchant'}</span>
                <span className="feed__category">{t.category ?? 'Uncategorised'}</span>
                <span className="feed__amount numeric">
                  {/* Credits are signed rather than coloured, so the direction of the money
                      survives a greyscale print and a colour-blind reader. */}
                  {t.transaction_type === 'CREDIT' ? '+' : '−'}
                  {formatCurrency(t.amount)}
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState
            title="No transactions yet"
            detail="Forward a bank SMS to ingestion-service and it will appear here."
          />
        )}
      </section>

      {!hasSpend && data.recentTransactions.length > 0 && (
        <p className="note">
          Nothing was spent in this calendar month, though older transactions exist. The total
          above covers {formatDate(data.periodStart)} onwards only.
        </p>
      )}
    </Frame>
  );
}

function Frame({ children }: { children: React.ReactNode }) {
  return (
    <>
      <PageHeader
        title="Dashboard"
        description="This month's total, the category breakdown and the ten most recent transactions."
      />
      {children}
    </>
  );
}
