import { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { getTransactions } from '../api/dashboard';
import { PageHeader } from '../components/Layout';
import { EmptyState, ErrorState, LoadingState } from '../components/states';
import { CATEGORIES } from '../lib/categories';
import { formatCurrency, formatDate } from '../lib/format';
import { useAsync } from '../lib/useAsync';

/** The server clamps `limit` to this; mirrored here only to explain a truncated list. */
const SERVER_MAX_LIMIT = 500;
const DEFAULT_LIMIT = 100;

export function TransactionsPage() {
  // Filters live in the query string, not in component state, so a filtered view can be
  // linked to, bookmarked and survives a refresh or a back button.
  const [searchParams, setSearchParams] = useSearchParams();

  const category = searchParams.get('category') ?? '';
  const from = searchParams.get('from') ?? '';
  const to = searchParams.get('to') ?? '';
  const merchant = searchParams.get('merchant') ?? '';
  const limitParam = searchParams.get('limit') ?? '';

  const requestedLimit = Number(limitParam) || DEFAULT_LIMIT;
  const effectiveLimit = Math.max(1, Math.min(requestedLimit, SERVER_MAX_LIMIT));

  const load = useCallback(
    () =>
      getTransactions({
        category: category || undefined,
        from: from || undefined,
        to: to || undefined,
        merchant: merchant || undefined,
        limit: requestedLimit,
      }),
    [category, from, to, merchant, requestedLimit],
  );

  const { data, error, loading, reload } = useAsync(load);

  function setFilter(key: string, value: string) {
    const next = new URLSearchParams(searchParams);
    if (value) next.set(key, value);
    else next.delete(key);
    // replace, so adjusting four filters does not leave four entries to back out through.
    setSearchParams(next, { replace: true });
  }

  const hasFilters = Boolean(category || from || to || merchant || limitParam);

  return (
    <>
      <PageHeader
        title="Transactions"
        description="Every transaction, filterable by date, category and merchant."
      />

      <form className="filters" onSubmit={(e) => e.preventDefault()}>
        <label className="field">
          <span className="field__label">From</span>
          <input
            type="date"
            className="field__input"
            value={from}
            onChange={(e) => setFilter('from', e.target.value)}
          />
        </label>

        <label className="field">
          <span className="field__label">To</span>
          <input
            type="date"
            className="field__input"
            value={to}
            onChange={(e) => setFilter('to', e.target.value)}
          />
        </label>

        <label className="field">
          <span className="field__label">Category</span>
          <select
            className="field__input"
            value={category}
            onChange={(e) => setFilter('category', e.target.value)}
          >
            <option value="">All categories</option>
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </label>

        <label className="field field--grow">
          <span className="field__label">Merchant</span>
          <input
            type="search"
            className="field__input"
            placeholder="Search merchant"
            value={merchant}
            onChange={(e) => setFilter('merchant', e.target.value)}
          />
        </label>

        <label className="field field--narrow">
          <span className="field__label">Limit</span>
          <input
            type="number"
            min={1}
            className="field__input numeric"
            placeholder={String(DEFAULT_LIMIT)}
            value={limitParam}
            onChange={(e) => setFilter('limit', e.target.value)}
          />
        </label>

        {hasFilters && (
          <button
            type="button"
            className="button filters__clear"
            onClick={() => setSearchParams(new URLSearchParams(), { replace: true })}
          >
            Clear
          </button>
        )}
      </form>

      {loading && <LoadingState label="Loading transactions" rows={5} />}
      {!loading && error && <ErrorState error={error} onRetry={reload} />}

      {!loading && !error && data && (
        <>
          <p className="result-count">
            {data.length} {data.length === 1 ? 'transaction' : 'transactions'}
            {requestedLimit > SERVER_MAX_LIMIT && (
              <>
                {' · '}
                <span className="result-count__warn">
                  limit of {requestedLimit} was reduced to {SERVER_MAX_LIMIT} by the server
                </span>
              </>
            )}
            {/* A full page is indistinguishable from a complete result set, so say so rather
                than letting a truncated list read as the whole truth. */}
            {requestedLimit <= SERVER_MAX_LIMIT && data.length === effectiveLimit && (
              <>
                {' · '}
                <span className="result-count__warn">
                  this is the full limit of {effectiveLimit}, there may be more
                </span>
              </>
            )}
          </p>

          {data.length === 0 ? (
            <EmptyState
              title="No transactions match these filters"
              detail={
                hasFilters
                  ? 'Try widening the date range or clearing the merchant search.'
                  : 'Forward a bank SMS to ingestion-service and it will appear here.'
              }
            />
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">Date</th>
                  <th scope="col">Merchant</th>
                  <th scope="col">Category</th>
                  <th scope="col">Type</th>
                  <th scope="col" className="table__right">
                    Amount
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.map((t) => {
                  const credit = t.transaction_type === 'CREDIT';
                  return (
                    <tr key={t.id}>
                      <td className="numeric">{formatDate(t.transaction_date)}</td>
                      <td>{t.merchant ?? 'Unknown merchant'}</td>
                      <td>
                        {t.category ?? <span className="muted">Uncategorised</span>}
                        {t.needs_review && <span className="tag">needs review</span>}
                      </td>
                      <td>
                        {/* Three independent cues - the word, the sign and the colour - so the
                            direction of the money never depends on colour alone. */}
                        <span className={credit ? 'direction direction--in' : 'direction'}>
                          {credit ? 'Credit' : 'Debit'}
                        </span>
                      </td>
                      <td className={`numeric table__right${credit ? ' amount--credit' : ''}`}>
                        {credit ? '+' : '−'}
                        {formatCurrency(t.amount)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </>
      )}
    </>
  );
}
