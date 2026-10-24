import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';

/**
 * The single axios instance. Everything the UI sends goes through here, so correlation ids and
 * error shaping are handled once rather than at every call site.
 *
 * dashboard-service is the only backend the frontend talks to - that is the whole point of the
 * BFF - so there is exactly one base URL.
 */
export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8087',
  headers: { 'Content-Type': 'application/json' },
  // Long enough for a chat question, which costs query-service four Gemini calls behind the
  // BFF, but not unbounded: a request that never settles leaves a spinner on screen forever.
  timeout: 120_000,
});

/** Where the correlation id is stashed on the request config so the error handler can read it. */
const CORRELATION_KEY = '__correlationId';

type TracedConfig = InternalAxiosRequestConfig & { [CORRELATION_KEY]?: string };

function newCorrelationId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  // randomUUID needs a secure context, which http://localhost is - but a LAN IP in dev is not.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

/**
 * Every request carries its own id. The backend's filter takes an inbound X-Correlation-Id
 * rather than minting its own, so one id spans the browser, the BFF and whatever it proxies
 * to - which is what makes a bug seen in the UI findable in the service logs.
 */
api.interceptors.request.use((config: TracedConfig) => {
  const correlationId = newCorrelationId();
  config.headers.set('X-Correlation-Id', correlationId);
  config[CORRELATION_KEY] = correlationId;
  return config;
});

/** A failed request, carrying the id needed to find it in the logs. */
export class ApiError extends Error {
  readonly correlationId: string | undefined;
  readonly status: number | undefined;
  readonly url: string | undefined;

  constructor(message: string, correlationId?: string, status?: number, url?: string) {
    super(message);
    this.name = 'ApiError';
    this.correlationId = correlationId;
    this.status = status;
    this.url = url;
  }
}

api.interceptors.response.use(
  (response) => response,
  (error: AxiosError<{ message?: string; error?: string }>) => {
    const config = error.config as TracedConfig | undefined;
    // Prefer the id the server echoed: if a proxy or the browser replaced ours, the server's
    // is the one that actually appears in the logs.
    const correlationId =
      (error.response?.headers?.['x-correlation-id'] as string | undefined) ??
      config?.[CORRELATION_KEY];

    const status = error.response?.status;
    const url = config?.url;

    let message: string;
    if (error.code === 'ECONNABORTED') {
      message = 'The request timed out.';
    } else if (!error.response) {
      // No response at all: the BFF is not running, or CORS refused the origin. Both look
      // identical to the browser, so the message has to name both.
      message = 'Could not reach dashboard-service. It may be stopped, or refusing this origin.';
    } else {
      message =
        error.response.data?.message ??
        error.response.data?.error ??
        `Request failed with status ${status}.`;
    }

    return Promise.reject(new ApiError(message, correlationId, status, url));
  },
);
