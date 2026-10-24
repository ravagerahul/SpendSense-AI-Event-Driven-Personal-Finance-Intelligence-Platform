import type { CategorySlice } from '../api/types';
import { formatCurrency, formatPercent } from '../lib/format';

/**
 * Horizontal bars for the category breakdown, labelled directly.
 *
 * A pie is the wrong shape for this data: at 67/32/0.8 the small slices are a sliver and an
 * invisible line, and reading them means bouncing between a legend and the wedges. Bars share
 * a common baseline, so the comparison is a single left-to-right scan and the 0.8% category
 * is still a visible, labelled row.
 *
 * Built with CSS rather than a charting library on purpose: this is a labelled list with a
 * length encoding, and the labels, type scale and alignment all have to match the rest of the
 * page exactly. Recharts is kept for genuine time series, where it earns its weight.
 *
 * Bars are tinted by rank rather than given a colour each. A rainbow would imply the colours
 * mean something; here only length carries data.
 */
export function CategoryBars({ slices }: { slices: CategorySlice[] }) {
  // Scaled against the largest value, not the total, so the biggest bar fills the track and
  // the small ones stay comparable instead of all collapsing to a stub.
  const max = Math.max(...slices.map((s) => s.total), 0);

  return (
    <ul className="bars">
      {slices.map((slice, i) => {
        const width = max > 0 ? (slice.total / max) * 100 : 0;
        return (
          <li className="bars__row" key={slice.category}>
            <div className="bars__label">
              <span>{slice.category}</span>
              <span className="bars__value numeric">
                {formatCurrency(slice.total)}
                <span className="bars__share">{formatPercent(slice.percentOfSpend)}</span>
              </span>
            </div>
            <div className="bars__track">
              <div
                className="bars__fill"
                // A hairline minimum: a 0.8% bar would otherwise round to nothing and the row
                // would look like a rendering bug rather than a small number.
                style={{
                  width: `max(2px, ${width}%)`,
                  opacity: Math.max(0.35, 1 - i * 0.18),
                }}
              />
            </div>
            <div className="bars__meta">
              {slice.transactions} {slice.transactions === 1 ? 'transaction' : 'transactions'}
            </div>
          </li>
        );
      })}
    </ul>
  );
}
