import type { QueryAnswer } from '../api/types';
import { formatCurrency, formatDate, formatPercent } from '../lib/format';

/**
 * The "show your working" view: how the answer above was arrived at.
 *
 * Collapsed by default, because the answer is what was asked for. Expanded, it is laid out as
 * prose and tables rather than dumped as JSON - the interesting claim is that the number came
 * from arithmetic over every matching row, and a reader can only check that by seeing the
 * resolved merchants and the actual SQL.
 */
export function Working({ answer }: { answer: QueryAnswer }) {
  const isSql = answer.method === 'sql';

  return (
    <details className="working">
      <summary className="working__summary">
        How this was answered
        <span className="working__method">
          {isSql ? 'aggregation · SQL over every matching row' : 'semantic · similarity search'}
        </span>
      </summary>

      <div className="working__body">
        {answer.fallbackFrom === 'sql' && (
          <p className="working__note">
            The aggregation path ran first and matched no rows, so this fell back to similarity
            search. The query it attempted is below.
          </p>
        )}

        {isSql ? (
          <>
            <Step
              n={1}
              title="The question was classified as wanting a number"
              body="So it went to the aggregation path, where Postgres does the arithmetic over every matching row rather than over a retrieved sample."
            />

            {answer.resolvedMerchants && answer.resolvedMerchants.length > 0 && (
              <Step
                n={2}
                title="Fuzzy wording was resolved to real merchant names"
                body="The question was embedded and compared against the merchants table. These are the candidates offered to the query, ranked by closeness — not a filter, so the query is free to use some and ignore the rest."
              >
                <ul className="merchants">
                  {answer.resolvedMerchants.map((m) => (
                    <li className="merchants__row" key={m.merchant_name}>
                      <span>{m.merchant_name}</span>
                      <span className="merchants__bar" aria-hidden="true">
                        <span
                          className="merchants__fill"
                          style={{ width: `${Math.max(0, Math.min(1, m.similarity)) * 100}%` }}
                        />
                      </span>
                      <span className="merchants__score numeric">
                        {formatPercent(m.similarity * 100)}
                      </span>
                    </li>
                  ))}
                </ul>
              </Step>
            )}

            {answer.sql && (
              <Step
                n={3}
                title="A SELECT was generated and run"
                body="Validated as read-only before execution."
              >
                <pre className="sql">{answer.sql}</pre>
              </Step>
            )}

            {answer.rows && (
              <Step n={4} title="Postgres returned" body="">
                <RowTable rows={answer.rows} />
              </Step>
            )}
          </>
        ) : (
          <>
            <Step
              n={1}
              title="The question was classified as wanting particular transactions"
              body="So it went to the semantic path: the question was embedded and the nearest transactions retrieved by meaning. This path reads a limited number of rows, so it answers 'which ones', not 'how much in total'."
            />

            {answer.attemptedSql && (
              <Step
                n={2}
                title="The aggregation query that found nothing"
                body="Kept so a wrong filter is visible rather than silent."
              >
                <pre className="sql">{answer.attemptedSql}</pre>
              </Step>
            )}

            {answer.retrievedTransactions && answer.retrievedTransactions.length > 0 && (
              <Step
                n={answer.attemptedSql ? 3 : 2}
                title="Transactions retrieved, nearest first"
                body=""
              >
                <table className="table">
                  <thead>
                    <tr>
                      <th scope="col">Date</th>
                      <th scope="col">Merchant</th>
                      <th scope="col">Category</th>
                      <th scope="col" className="table__right">
                        Amount
                      </th>
                      <th scope="col" className="table__right">
                        Similarity
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {answer.retrievedTransactions.map((t, i) => (
                      <tr key={i}>
                        <td className="numeric">{formatDate(t.transaction_date)}</td>
                        <td>{t.merchant ?? 'Unknown merchant'}</td>
                        <td>{t.category ?? <span className="muted">Uncategorised</span>}</td>
                        <td className="numeric table__right">{formatCurrency(t.amount)}</td>
                        <td className="numeric table__right">
                          {formatPercent(t.similarity * 100)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Step>
            )}
          </>
        )}
      </div>
    </details>
  );
}

function Step({
  n,
  title,
  body,
  children,
}: {
  n: number;
  title: string;
  body: string;
  children?: React.ReactNode;
}) {
  return (
    <section className="step">
      <div className="step__head">
        <span className="step__n numeric">{n}</span>
        <h3 className="step__title">{title}</h3>
      </div>
      {body && <p className="step__body">{body}</p>}
      {children}
    </section>
  );
}

/**
 * The raw result set. Column names come from the generated SQL, so they cannot be known ahead
 * of time - but a one-cell result (the usual case for a total) reads far better as a figure
 * than as a one-by-one table.
 */
function RowTable({ rows }: { rows: Record<string, unknown>[] }) {
  if (rows.length === 0) return <p className="step__body muted">No rows.</p>;

  const columns = Object.keys(rows[0]);

  if (rows.length === 1 && columns.length === 1) {
    const value = rows[0][columns[0]];
    return (
      <p className="step__scalar">
        <span className="step__scalar-label">{columns[0]}</span>
        <span className="numeric">
          {typeof value === 'number' ? formatCurrency(value) : String(value)}
        </span>
      </p>
    );
  }

  return (
    <table className="table">
      <thead>
        <tr>
          {columns.map((c) => (
            <th scope="col" key={c}>
              {c}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row, i) => (
          <tr key={i}>
            {columns.map((c) => (
              <td className="numeric" key={c}>
                {row[c] === null ? <span className="muted">null</span> : String(row[c])}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
