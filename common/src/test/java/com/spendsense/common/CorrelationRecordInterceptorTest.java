package com.spendsense.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CorrelationRecordInterceptorTest {

    private final CorrelationRecordInterceptor interceptor =
            new CorrelationRecordInterceptor(new ObjectMapper());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private void intercept(String payload) {
        interceptor.intercept(new ConsumerRecord<>("t", 0, 0L, "k", payload), null);
    }

    @Test
    void liftsCorrelationAndEventIdOntoTheMdc() {
        intercept("""
                {"eventId":"e-1","correlationId":"c-1","rawText":"Rs.450 debited"}""");

        assertEquals("c-1", MDC.get(Correlation.CORRELATION_ID));
        assertEquals("e-1", MDC.get(Correlation.EVENT_ID));
    }

    @Test
    void acceptsEitherNameTheTopicsUseForTheUnitOfWork() {
        // transaction-stored is a bare notification: no eventId, only transactionId.
        intercept("""
                {"transactionId":"t-9","correlationId":"c-2"}""");
        assertEquals("t-9", MDC.get(Correlation.EVENT_ID));

        MDC.clear();
        intercept("""
                {"sourceEventId":"s-3","correlationId":"c-3"}""");
        assertEquals("s-3", MDC.get(Correlation.EVENT_ID));
    }

    @Test
    void prefersTheOriginalSmsIdOverTheEnvelopesOwnId() {
        // transaction-extracted mints a fresh eventId per message but keeps the SMS id in
        // sourceEventId. Logging the envelope's id would break the trace exactly here.
        intercept("""
                {"eventId":"envelope-7","sourceEventId":"sms-1","correlationId":"c-4"}""");

        assertEquals("sms-1", MDC.get(Correlation.EVENT_ID));
    }

    @Test
    void unparseablePayloadIsNotAnError() {
        // Tracing must never be the reason a message fails; the listener still gets the record.
        intercept("not json at all");
        assertNull(MDC.get(Correlation.CORRELATION_ID));
    }

    @Test
    void clearsAfterTheRecordSoTheNextOneOnThisThreadIsClean() {
        intercept("""
                {"eventId":"e-1","correlationId":"c-1"}""");
        interceptor.afterRecord(new ConsumerRecord<>("t", 0, 0L, "k", "{}"), null);

        assertNull(MDC.get(Correlation.CORRELATION_ID));
        assertNull(MDC.get(Correlation.EVENT_ID));
    }
}
