package com.spendsense.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Lifts the correlation id out of each Kafka payload and into the MDC before the listener
 * runs.
 *
 * <p>Doing it here rather than inside each listener means the ids are also on the framework's
 * own log lines - offset commits, retry scheduling, the "seeking to" messages - and on
 * anything a listener logs before it has finished parsing. It also means a new listener gets
 * tracing for free instead of having to remember a try/finally.
 *
 * <p>Every topic in this pipeline carries the id in the body rather than in a header, so the
 * payload is parsed here. A payload that is not JSON, or has no id, simply leaves the MDC
 * empty: tracing must never be the reason a message fails.
 */
public class CorrelationRecordInterceptor implements RecordInterceptor<Object, Object> {

    private final ObjectMapper mapper;

    public CorrelationRecordInterceptor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        Correlation.set(correlationId(record.value()), eventId(record.value()));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        // Called whether the listener succeeded or threw. The consumer pool reuses threads.
        Correlation.clear();
    }

    private String correlationId(Object value) {
        JsonNode n = parse(value);
        return n == null ? null : text(n, "correlationId");
    }

    /**
     * Topics name the unit of work differently: raw-sms-received has eventId, the extracted
     * event mints a fresh eventId of its own and keeps the original under sourceEventId, and
     * the stored notification carries only transactionId.
     *
     * <p>sourceEventId is preferred over eventId precisely because of the middle case: the
     * field logged should identify the SMS, not the envelope it happens to be travelling in,
     * so the same value appears at every hop that still knows it.
     */
    private String eventId(Object value) {
        JsonNode n = parse(value);
        if (n == null) return null;
        String id = text(n, "sourceEventId");
        if (id == null) id = text(n, "eventId");
        if (id == null) id = text(n, "transactionId");
        return id;
    }

    private JsonNode parse(Object value) {
        if (!(value instanceof String s) || s.isBlank()) return null;
        try {
            JsonNode n = mapper.readTree(s);
            return n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return (v.isMissingNode() || v.isNull() || v.asText().isBlank()) ? null : v.asText();
    }
}
