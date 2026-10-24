package com.spendsense.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionListenerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildsEmbedTextFromExtractedFields() throws Exception {
        String json = """
                {"transactionType":"DEBIT","amount":450.00,"currency":"INR","merchant":"Swiggy",
                 "transactionDate":"2026-09-12","rawText":"Rs.450.00 debited to swiggy@ybl"}
                """;
        // Jackson reads 450.00 as a double, so the amount renders as 450.0 here. Harmless:
        // this text is only ever fed to the embedding model, never shown or parsed.
        assertEquals("DEBIT 450.0 INR to Swiggy on 2026-09-12 - Rs.450.00 debited to swiggy@ybl",
                TransactionListener.embedText(mapper.readTree(json)));
    }

    @Test
    void missingFieldsDoNotBreakEmbedText() throws Exception {
        String json = """
                {"merchant":null,"rawText":"Rs.50 debited"}
                """;
        String text = TransactionListener.embedText(mapper.readTree(json));
        assertTrue(text.contains("Rs.50 debited"));
        assertTrue(text.startsWith("to ") || text.contains("to  on"));
    }
}
