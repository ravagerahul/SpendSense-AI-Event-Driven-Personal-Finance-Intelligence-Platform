package com.spendsense.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class IngestionControllerTest {

    private SmsPayload payload(String text, String receivedAt) {
        SmsPayload p = new SmsPayload();
        p.setRawText(text);
        p.setReceivedAt(receivedAt);
        return p;
    }

    @Test
    void sameSmsAndTimestampGiveSameEventId() {
        assertEquals(
                IngestionController.eventIdFor(payload("Rs.50 debited", "2026-10-19T10:00:00Z")),
                IngestionController.eventIdFor(payload("Rs.50 debited", "2026-10-19T10:00:00Z")));
    }

    @Test
    void differentTimestampGivesDifferentEventId() {
        assertNotEquals(
                IngestionController.eventIdFor(payload("Rs.50 debited", "2026-10-19T10:00:00Z")),
                IngestionController.eventIdFor(payload("Rs.50 debited", "2026-10-19T10:05:00Z")));
    }

    @Test
    void missingTimestampStaysUnique() {
        assertNotEquals(
                IngestionController.eventIdFor(payload("Rs.50 debited", null)),
                IngestionController.eventIdFor(payload("Rs.50 debited", null)));
    }
}
