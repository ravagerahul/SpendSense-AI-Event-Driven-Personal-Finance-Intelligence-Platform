package com.spendsense.common;

import org.slf4j.MDC;

/**
 * The two identifiers every log line carries, held in SLF4J's MDC so no logging call has to
 * pass them explicitly.
 *
 * <p>MDC is thread-local, and both the Tomcat worker pool and the Kafka consumer pool reuse
 * their threads. A value left behind on one request reappears, silently and wrongly, on the
 * next one to land on that thread. Everything that calls {@link #set} must therefore call
 * {@link #clear} in a finally block; the filter and the record interceptor in this package
 * are the only two places that do.
 */
public final class Correlation {

    /** Lets a caller thread its own id through instead of being given a fresh one. */
    public static final String HEADER = "X-Correlation-Id";

    public static final String CORRELATION_ID = "correlationId";
    public static final String EVENT_ID = "eventId";

    private Correlation() {
    }

    public static void set(String correlationId, String eventId) {
        if (correlationId != null && !correlationId.isBlank()) {
            MDC.put(CORRELATION_ID, correlationId);
        }
        if (eventId != null && !eventId.isBlank()) {
            MDC.put(EVENT_ID, eventId);
        }
    }

    public static void clear() {
        MDC.remove(CORRELATION_ID);
        MDC.remove(EVENT_ID);
    }

    public static String currentCorrelationId() {
        return MDC.get(CORRELATION_ID);
    }
}
