package com.spendsense.dashboard;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnomalyGrouperTest {

    private static final String SWIGGY_TXN = "eb9d7a4d-d0dd-4b11-a9dc-b12d7538c932";

    private static Map<String, Object> row(String transactionId, String merchant, String amount,
                                           String type, String severity, boolean acknowledged) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("anomaly_id", UUID.randomUUID());
        r.put("transaction_id", transactionId);
        r.put("anomaly_type", type);
        r.put("severity", severity);
        r.put("reason", type + " reason");
        r.put("acknowledged", acknowledged);
        r.put("created_at", "2026-10-20T10:00:00Z");
        r.put("merchant", merchant);
        r.put("amount", new BigDecimal(amount));
        r.put("category", "Food & Dining");
        r.put("transaction_date", "2026-10-20");
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void oneTransactionWithTwoFindingsIsOneEntryNotTwo() {
        // The real ₹15,000 Swiggy charge: genuinely both an unusual amount and part of a
        // frequency spike. Ungrouped it would read as two separate odd charges.
        List<Map<String, Object>> grouped = AnomalyGrouper.group(List.of(
                row(SWIGGY_TXN, "Swiggy", "15000.00", "UNUSUAL_AMOUNT", "HIGH", false),
                row(SWIGGY_TXN, "Swiggy", "15000.00", "FREQUENCY_SPIKE", "MEDIUM", false)));

        assertEquals(1, grouped.size());

        Map<String, Object> entry = grouped.get(0);
        assertEquals(SWIGGY_TXN, entry.get("transactionId"));
        assertEquals(2, entry.get("anomalyCount"));
        assertEquals("HIGH", entry.get("highestSeverity"));

        List<Map<String, Object>> anomalies = (List<Map<String, Object>>) entry.get("anomalies");
        assertEquals(2, anomalies.size());
        assertEquals(List.of("UNUSUAL_AMOUNT", "FREQUENCY_SPIKE"),
                anomalies.stream().map(a -> a.get("type")).toList());
    }

    @Test
    void distinctTransactionsStayDistinct() {
        List<Map<String, Object>> grouped = AnomalyGrouper.group(List.of(
                row(SWIGGY_TXN, "Swiggy", "15000.00", "UNUSUAL_AMOUNT", "HIGH", false),
                row("3ae2e61e-64b2-4dca-9a0f-6e8600c6ad1c", "MakeMyTrip", "8450.00",
                        "UNKNOWN_MERCHANT", "LOW", false)));

        assertEquals(2, grouped.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void worstSeverityLeadsBothTheEntryAndItsList() {
        // Fed in LOW-first to prove the ordering is imposed here, not inherited from the query.
        List<Map<String, Object>> grouped = AnomalyGrouper.group(List.of(
                row("t-1", "Zomato", "350.00", "UNKNOWN_MERCHANT", "LOW", false),
                row("t-1", "Zomato", "350.00", "DUPLICATE", "HIGH", false),
                row("t-2", "CoffeeDay", "130.00", "FREQUENCY_SPIKE", "MEDIUM", false)));

        // The HIGH transaction sorts ahead of the MEDIUM one.
        assertEquals("t-1", grouped.get(0).get("transactionId"));
        assertEquals("HIGH", grouped.get(0).get("highestSeverity"));

        List<Map<String, Object>> anomalies = (List<Map<String, Object>>) grouped.get(0).get("anomalies");
        assertEquals("DUPLICATE", anomalies.get(0).get("type"));
    }

    @Test
    void entryCountsAsAcknowledgedOnlyWhenEveryFindingIs() {
        // Half-handled must not vanish from the list: the second finding is still outstanding.
        List<Map<String, Object>> half = AnomalyGrouper.group(List.of(
                row(SWIGGY_TXN, "Swiggy", "15000.00", "UNUSUAL_AMOUNT", "HIGH", true),
                row(SWIGGY_TXN, "Swiggy", "15000.00", "FREQUENCY_SPIKE", "MEDIUM", false)));
        assertFalse((Boolean) half.get(0).get("acknowledged"));

        List<Map<String, Object>> all = AnomalyGrouper.group(List.of(
                row(SWIGGY_TXN, "Swiggy", "15000.00", "UNUSUAL_AMOUNT", "HIGH", true),
                row(SWIGGY_TXN, "Swiggy", "15000.00", "FREQUENCY_SPIKE", "MEDIUM", true)));
        assertTrue((Boolean) all.get(0).get("acknowledged"));
    }

    @Test
    void emptyInputIsEmptyOutput() {
        assertTrue(AnomalyGrouper.group(List.of()).isEmpty());
    }

    @Test
    void unknownSeveritySortsLastRatherThanCrashing() {
        assertTrue(AnomalyGrouper.severityRank("HIGH") < AnomalyGrouper.severityRank("MEDIUM"));
        assertTrue(AnomalyGrouper.severityRank("LOW") < AnomalyGrouper.severityRank("WEIRD"));
        assertTrue(AnomalyGrouper.severityRank("LOW") < AnomalyGrouper.severityRank(null));
    }
}
