import { useCallback, useState } from 'react';
import { correctTransaction, getReview } from '../api/dashboard';
import { ApiError } from '../api/client';
import type { Transaction } from '../api/types';
import { PageHeader } from '../components/Layout';
import { EmptyState, ErrorState, LoadingState } from '../components/states';
import { CATEGORIES } from '../lib/categories';
import { formatCurrency, formatDate, formatPercent } from '../lib/format';
import { useAsync } from '../lib/useAsync';

const MANUAL = 'Needs Manual Categorization';

interface Confirmation {
  id: string;
  merchant: string;
  from: string;
  to: string;
}

export function ReviewPage() {
  const { data, error, loading, reload } = useAsync(useCallback(() => getReview(), []));

  // Corrected rows are removed here rather than by refetching: the list is small, the server
  // has already committed the change, and a refetch would make the row flicker before leaving.
  const [corrected, setCorrected] = useState<Set<string>>(new Set());
  const [confirmations, setConfirmations] = useState<Confirmation[]>([]);
  const [failure, setFailure] = useState<unknown>();

  function onCorrected(c: Confirmation) {
    setCorrected((prev) => new Set(prev).add(c.id));
    setConfirmations((prev) => [c, ...prev]);
    setFailure(undefined);
  }

  const rows = (data ?? []).filter((t) => !corrected.has(t.id));

  return (
    <>
      <PageHeader
        title="Review"
        description="Transactions the categorizer was unsure about. Corrections feed back to it as examples."
      />

      {confirmations.map((c) => (
        <p className="confirm" key={c.id} role="status">
          {c.merchant}: <strong>{c.from}</strong> → <strong>{c.to}</strong>. Saved as an example
          for future categorization.
        </p>
      ))}

      {failure !== undefined && (
        <div className="turn__failure" role="alert">
          <p>
            {failure instanceof ApiError ? failure.message : 'The correction could not be saved.'}
          </p>
          {failure instanceof ApiError && failure.correlationId && (
            <code className="state__trace">correlationId: {failure.correlationId}</code>
          )}
        </div>
      )}

      {loading && <LoadingState label="Loading the review queue" />}
      {!loading && error && <ErrorState error={error} onRetry={reload} />}

      {!loading && !error && data && rows.length === 0 && (
        <EmptyState
          title="Nothing needs review"
          detail={
            confirmations.length > 0
              ? 'Everything flagged has been corrected. Transactions reappear here when the categorizer is unsure about a new one.'
              : 'No transaction is currently below the confidence threshold, and none was categorised as needing manual attention.'
          }
        />
      )}

      {rows.map((t) => (
        <ReviewCard key={t.id} transaction={t} onCorrected={onCorrected} onFailure={setFailure} />
      ))}
    </>
  );
}

function ReviewCard({
  transaction,
  onCorrected,
  onFailure,
}: {
  transaction: Transaction;
  onCorrected: (c: Confirmation) => void;
  onFailure: (e: unknown) => void;
}) {
  const current = transaction.category ?? '';
  const [choice, setChoice] = useState(current);
  const [saving, setSaving] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (saving || !choice) return;
    setSaving(true);
    try {
      const result = await correctTransaction(transaction.id, choice);
      onCorrected({
        id: transaction.id,
        merchant: transaction.merchant ?? 'Unknown merchant',
        from: result.originalCategory || 'uncategorised',
        to: result.correctedCategory,
      });
    } catch (err) {
      onFailure(err);
      setSaving(false);
    }
  }

  return (
    <article className="card">
      <div className="card__head">
        <div>
          <span className="card__merchant">{transaction.merchant ?? 'Unknown merchant'}</span>
          <span className="card__meta">{formatDate(transaction.transaction_date)}</span>
        </div>
        <span className="card__amount numeric">{formatCurrency(transaction.amount)}</span>
      </div>

      {/* A queue that does not say why a row is in it is just a list. */}
      <p className="card__why">{whyFlagged(transaction)}</p>

      {transaction.categorization_reasoning && (
        <p className="card__reasoning">
          <span className="card__reasoning-label">Categorizer said</span>
          {transaction.categorization_reasoning}
        </p>
      )}

      {transaction.raw_text && <p className="card__raw">{transaction.raw_text}</p>}

      <form className="card__action" onSubmit={submit}>
        <label className="field">
          <span className="field__label">Category</span>
          <select
            className="field__input"
            value={choice}
            onChange={(e) => setChoice(e.target.value)}
            disabled={saving}
          >
            {/* The current value is preselected, so submitting without touching the dropdown
                confirms the model's guess rather than silently changing it. */}
            {current === '' && <option value="">Choose a category</option>}
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </label>
        <button type="submit" className="button button--primary" disabled={saving || !choice}>
          {saving ? 'Saving…' : 'Save correction'}
        </button>
      </form>
    </article>
  );
}

/**
 * Why this row is in the queue. Two different causes land here and they want different fixes,
 * so they are not collapsed into one message. The categorizer's own sentence is shown
 * separately, where the row carries one.
 */
function whyFlagged(t: Transaction): string {
  const confidence = t.categorization_confidence;

  if (t.category === MANUAL) {
    return 'The categorizer judged this to be a personal name rather than a business, and could not place it from context.';
  }

  if (confidence !== null && confidence !== undefined) {
    return `The categorizer chose ${t.category ?? 'nothing'} with ${formatPercent(
      confidence * 100,
    )} confidence, below the threshold for accepting it unchecked.`;
  }

  return 'This transaction was flagged for review but carries no confidence score.';
}
