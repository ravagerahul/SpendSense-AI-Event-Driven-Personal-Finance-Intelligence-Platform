/**
 * The closed category list. The same thirteen values the categorization prompt is given and
 * the SQL prompt is constrained to, so a filter dropdown can never offer a value that cannot
 * match a row.
 */
export const CATEGORIES = [
  'Food & Dining',
  'Groceries',
  'Transport',
  'Shopping',
  'Bills & Utilities',
  'Subscriptions',
  'Entertainment',
  'Health',
  'Travel',
  'Transfers',
  'Income',
  'Needs Manual Categorization',
  'Other',
] as const;
