package com.spendsense.extraction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiClientTest {

    @Test
    void masksAvailableBalance() {
        String masked = GeminiClient.maskBalance("Rs.450.00 debited. Avl Bal Rs 12,345.67. -HDFC");
        assertTrue(masked.contains("balance hidden"));
        assertFalse(masked.contains("12,345"));
    }

    @Test
    void keepsTransactionAmount() {
        String masked = GeminiClient.maskBalance("Rs.450.00 debited from A/c XX1234 to swiggy@ybl");
        assertEquals("Rs.450.00 debited from A/c XX1234 to swiggy@ybl", masked);
    }
}
