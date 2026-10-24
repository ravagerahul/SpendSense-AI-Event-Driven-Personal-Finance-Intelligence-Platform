import { api } from './client';
import type {
  AnomalyGroup,
  CorrectionResult,
  Health,
  Insights,
  InsightsPlaceholder,
  QueryAnswer,
  Summary,
  Transaction,
  TransactionFilters,
} from './types';

/**
 * One function per dashboard-service endpoint, all nine. Pages call these and never axios
 * directly, so a contract change has exactly one place to be fixed.
 */

/** 1. Month total, category breakdown and the ten most recent transactions, in one request. */
export async function getSummary(): Promise<Summary> {
  const { data } = await api.get<Summary>('/api/dashboard/summary');
  return data;
}

/** 2. Filtered transaction list. Omitted filters are left off the query string entirely. */
export async function getTransactions(filters: TransactionFilters = {}): Promise<Transaction[]> {
  const { data } = await api.get<Transaction[]>('/api/dashboard/transactions', {
    params: filters,
  });
  return data;
}

/** 3. A natural-language question. Proxied to query-service, which routes it. */
export async function postQuery(question: string): Promise<QueryAnswer> {
  const { data } = await api.post<QueryAnswer>('/api/dashboard/query', { question });
  return data;
}

/** 4. Transactions the categorizer was not confident about. */
export async function getReview(): Promise<Transaction[]> {
  const { data } = await api.get<Transaction[]>('/api/dashboard/review');
  return data;
}

/**
 * 5. Fix a category. Proxied to storage-service, which also records the correction so
 * categorization-service can learn from it as a few-shot example.
 */
export async function correctTransaction(
  id: string,
  correctedCategory: string,
): Promise<CorrectionResult> {
  const { data } = await api.post<CorrectionResult>(`/api/dashboard/review/${id}/correct`, {
    correctedCategory,
  });
  return data;
}

/** 6. Anomalies, one entry per transaction carrying all of its findings. */
export async function getAnomalies(includeAcknowledged = false): Promise<AnomalyGroup[]> {
  const { data } = await api.get<AnomalyGroup[]>('/api/dashboard/anomalies', {
    params: { includeAcknowledged },
  });
  return data;
}

/**
 * 7. Dismiss one finding. The id is the anomaly's, not the transaction's: a transaction with
 * two findings has two independently acknowledgeable rows.
 */
export async function acknowledgeAnomaly(anomalyId: string): Promise<{ status: string }> {
  const { data } = await api.post<{ status: string }>(
    `/api/dashboard/anomalies/${anomalyId}/acknowledge`,
  );
  return data;
}

/**
 * 8. The latest weekly summary. Returns a placeholder rather than an error when none has been
 * generated yet; narrow it with `hasInsights` before reading the numbers.
 */
export async function getInsights(): Promise<Insights | InsightsPlaceholder> {
  const { data } = await api.get<Insights | InsightsPlaceholder>('/api/dashboard/insights');
  return data;
}

/** 9. The BFF's own health plus a ping of every other service. */
export async function getHealth(): Promise<Health> {
  const { data } = await api.get<Health>('/api/health');
  return data;
}
