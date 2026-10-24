CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS transactions (
    id UUID PRIMARY KEY,
    correlation_id UUID,
    source_event_id TEXT UNIQUE,
    raw_text TEXT,
    amount NUMERIC(14,2),
    currency TEXT DEFAULT 'INR',
    merchant TEXT,
    transaction_type TEXT,
    transaction_date DATE,
    account_last4 TEXT,
    category TEXT,
    extraction_confidence DOUBLE PRECISION,
    categorization_confidence DOUBLE PRECISION,
    categorization_reasoning TEXT,
    needs_review BOOLEAN NOT NULL DEFAULT FALSE,
    review_corrected BOOLEAN NOT NULL DEFAULT FALSE,
    corrected_category TEXT,
    embedding vector(768),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Brings a database created before phase 2 up to the shape above. No-ops on a fresh database.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS correlation_id UUID;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS extraction_confidence DOUBLE PRECISION;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS categorization_confidence DOUBLE PRECISION;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS review_corrected BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS corrected_category TEXT;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS embedding vector(768);

-- The categorizer's one-sentence justification. It was always produced and logged; storing it
-- is what lets the review queue say why a transaction was put there rather than only how
-- confident the model was. Nullable, and null on every row categorised before this column
-- existed - the review card has to handle its absence rather than assume it.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS categorization_reasoning TEXT;

-- Dropped for the same reason as the merchants index above: at this row count ivfflat omits
-- real matches instead of speeding anything up. RAG retrieval silently losing a relevant
-- transaction looks identical to there being none. See the note on merchants below.
DROP INDEX IF EXISTS idx_transactions_embedding;
DROP INDEX IF EXISTS idx_merchants_embedding;

CREATE INDEX IF NOT EXISTS idx_transactions_date
    ON transactions (transaction_date DESC);

CREATE INDEX IF NOT EXISTS idx_transactions_category
    ON transactions (category);

-- One row per distinct merchant, embedded on its name alone. Aggregation questions resolve
-- their fuzzy part ("flight booking") against this table to get exact merchant values, so the
-- SQL that follows can filter precisely and sum over every matching transaction. Searching
-- merchants rather than transactions is what removes the top-k truncation from aggregations:
-- the LIMIT applies to candidate names, not to the rows being added up.
CREATE TABLE IF NOT EXISTS merchants (
    id UUID PRIMARY KEY,
    merchant_name TEXT NOT NULL,
    embedding vector(768),
    first_seen TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Uniqueness is case-insensitive. Extraction can emit MAKEMYTRIP from one SMS and MakeMyTrip
-- from the next; as two rows they would each hold half the history and split every total
-- computed from this table. The display name stays exactly as extraction produced it, only
-- the uniqueness check is folded. Replaces the plain UNIQUE constraint on older databases.
ALTER TABLE merchants DROP CONSTRAINT IF EXISTS merchants_merchant_name_key;
CREATE UNIQUE INDEX IF NOT EXISTS idx_merchants_name_lower ON merchants (lower(merchant_name));

-- No ivfflat index here on purpose. ivfflat is an APPROXIMATE index: it buckets vectors into
-- `lists` and probes only `ivfflat.probes` (default 1) of them per query, so vectors in the
-- unprobed buckets are never considered. Measured on this table with lists = 100 and 5 rows,
-- a LIMIT 10 search returned 3 of 5 merchants and silently omitted the correct answer.
-- A missed merchant here is not a slow query, it is a wrong total, because the SQL that follows
-- can only filter on the names this search returned. Exact search over a few thousand merchants
-- costs under a millisecond, so until this table is genuinely large the index is pure downside.
-- When it does grow, add it back built on populated data, with lists ~ rows/1000 and
-- ivfflat.probes tuned, and re-check recall before trusting it.

CREATE TABLE IF NOT EXISTS anomalies (
    id UUID PRIMARY KEY,
    transaction_id UUID REFERENCES transactions(id),
    correlation_id UUID,
    anomaly_type TEXT NOT NULL,
    reason TEXT,
    severity TEXT NOT NULL,
    acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per reporting period, written by insights-service. The scalar columns are what the
-- dashboard sorts and compares on; the lists are JSONB because their shape is presentational
-- and will change as the UI does, which is not worth a migration each time.
CREATE TABLE IF NOT EXISTS insights (
    id UUID PRIMARY KEY,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    total_spend NUMERIC(14,2),
    previous_total_spend NUMERIC(14,2),
    change_pct DOUBLE PRECISION,
    top_categories JSONB,
    subscriptions JSONB,
    anomalies JSONB,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Regenerating a period overwrites it instead of appending, so the manual /generate endpoint
-- can be called repeatedly without filling the table with near-identical rows.
CREATE UNIQUE INDEX IF NOT EXISTS idx_insights_period ON insights (period_start, period_end);

CREATE TABLE IF NOT EXISTS corrections (
    id UUID PRIMARY KEY,
    transaction_id UUID REFERENCES transactions(id),
    original_category TEXT,
    corrected_category TEXT,
    corrected_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
