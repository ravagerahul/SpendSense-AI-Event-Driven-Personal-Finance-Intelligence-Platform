import { useCallback, useEffect, useRef, useState } from 'react';

export interface AsyncState<T> {
  data: T | undefined;
  error: unknown;
  loading: boolean;
  reload: () => void;
}

/**
 * Runs an async function and tracks the three states every page needs. Written once here so
 * each page does not re-derive its own loading/error bookkeeping, and so they all behave the
 * same when a request fails.
 *
 * @param run  the request. It must be stable (wrap it in useCallback), because it is the
 *             dependency that decides when to refetch - an inline arrow would refetch on
 *             every render.
 */
export function useAsync<T>(run: () => Promise<T>): AsyncState<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [nonce, setNonce] = useState(0);

  // Guards against a slow first request landing after a faster second one and overwriting it
  // with stale data - easy to trigger here by editing a filter twice in quick succession.
  const latest = useRef(0);

  useEffect(() => {
    const ticket = ++latest.current;
    let cancelled = false;

    setLoading(true);
    setError(undefined);

    run().then(
      (result) => {
        if (cancelled || ticket !== latest.current) return;
        setData(result);
        setLoading(false);
      },
      (err) => {
        if (cancelled || ticket !== latest.current) return;
        setError(err);
        setLoading(false);
      },
    );

    return () => {
      cancelled = true;
    };
  }, [run, nonce]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);

  return { data, error, loading, reload };
}
