/**
 * Shapes returned by dashboard-service. Written against the responses the service actually
 * produces rather than against the documented contract, so the two can be compared.
 *
 * Note the casing split, which is real and deliberate rather than an oversight to tidy away:
 * hand-built response maps use camelCase, while rows that come straight out of `queryForList`
 * carry Postgres' snake_case column names. Transaction rows are the latter.
 */

export type Severity = 'HIGH' | 'MEDIUM' | 'LOW';

/** A transaction row, exactly as Postgres names its columns. */
export interface Transaction {
  id: string;
  correlation_id: string | null;
  raw_text: string | null;
  amount: number | null;
  currency: string | null;
  merchant: string | null;
  transaction_type: 'DEBIT' | 'CREDIT' | null;
  transaction_date: string | null;
  account_last4: string | null;
  category: string | null;
  extraction_confidence: number | null;
  categorization_confidence: number | null;
  /** The categorizer's one-sentence justification. Null on rows categorised before it was stored. */
  categorization_reasoning: string | null;
  needs_review: boolean;
  review_corrected: boolean;
  corrected_category: string | null;
  created_at: string;
}

export interface CategorySlice {
  category: string;
  total: number;
  transactions: number;
  /** null when nothing was spent in the period: there is no share of an empty total. */
  percentOfSpend: number | null;
}

export interface Summary {
  periodStart: string;
  periodEnd: string;
  totalSpend: number;
  creditTotal: number;
  transactionCount: number;
  categoryBreakdown: CategorySlice[];
  recentTransactions: Transaction[];
}

export interface TransactionFilters {
  category?: string;
  from?: string;
  to?: string;
  merchant?: string;
  limit?: number;
}

/**
 * The chat answer. `method` says which path answered: `sql` computed a number over every
 * matching row, `rag` retrieved particular transactions by meaning. The remaining fields
 * depend on which, so they are all optional.
 */
export interface RetrievedTransaction {
  merchant: string | null;
  amount: number | null;
  currency: string | null;
  transaction_type: string | null;
  transaction_date: string | null;
  category: string | null;
  raw_text: string | null;
  /** 1 - cosine distance. Higher is closer. */
  similarity: number;
}

export interface QueryAnswer {
  question: string;
  method?: 'sql' | 'rag';
  answer?: string;
  sql?: string;
  rows?: Record<string, unknown>[];
  resolvedMerchants?: { merchant_name: string; similarity: number }[];
  retrievedTransactions?: RetrievedTransaction[];
  /** Present when the SQL path found nothing and similarity search answered instead. */
  fallbackFrom?: string;
  attemptedSql?: string;
  /**
   * query-service answers a question it could not handle with HTTP 200 and this field rather
   * than an error status, so the UI has to check for it: a 200 is not proof of an answer.
   */
  error?: string;
}

export interface CorrectionResult {
  status: string;
  transactionId: string;
  originalCategory: string;
  correctedCategory: string;
}

export interface Anomaly {
  anomalyId: string;
  type: string;
  severity: Severity;
  reason: string;
  acknowledged: boolean;
  createdAt: string;
}

/**
 * One transaction and every anomaly found for it. The grouping is the point: a single odd
 * purchase can trip several rules, and listing those separately reads as several problems.
 */
export interface AnomalyGroup {
  transactionId: string;
  merchant: string | null;
  amount: number | null;
  category: string | null;
  transactionDate: string | null;
  anomalyCount: number;
  highestSeverity: Severity;
  /** True only when every one of its anomalies has been acknowledged. */
  acknowledged: boolean;
  anomalies: Anomaly[];
}

export interface Insights {
  periodStart: string;
  periodEnd: string;
  totalSpend: number;
  previousTotalSpend: number;
  /** null when the previous period had no spend. Render "no comparison", never 0%. */
  changePct: number | null;
  topCategories: CategorySlice[];
  subscriptions: {
    merchant: string;
    amount: number;
    occurrences: number;
    first_charge: string;
    last_charge: string;
  }[];
  anomalies: Record<string, unknown>[];
  generatedAt: string;
}

/** What /api/dashboard/insights returns before the first run has ever happened. */
export interface InsightsPlaceholder {
  status: string;
  hint: string;
}

export function hasInsights(value: Insights | InsightsPlaceholder): value is Insights {
  return (value as Insights).periodStart !== undefined;
}

export interface DependencyHealth {
  url: string;
  status: string;
  reportedName?: string;
  error?: string;
}

export interface Health {
  status: string;
  service: string;
  timestamp: string;
  /** e.g. "7/7" */
  dependenciesUp: string;
  services: Record<string, DependencyHealth>;
}
