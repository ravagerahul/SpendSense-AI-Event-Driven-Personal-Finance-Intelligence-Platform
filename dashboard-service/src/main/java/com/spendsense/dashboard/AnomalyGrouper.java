package com.spendsense.dashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collapses anomaly rows into one entry per transaction.
 *
 * <p>The anomalies table holds one row per (transaction, anomaly type) on purpose: a single odd
 * purchase can genuinely trip several rules, and each hit carries its own reason and severity,
 * so neither may be discarded at write time. The ₹15,000 Swiggy charge is both an unusual amount
 * and part of a frequency spike, and both statements are true.
 *
 * <p>Presented ungrouped, though, that one purchase becomes several separate alerts and the list
 * reads as noise - the user cannot tell three odd charges from one charge that is odd three
 * ways. So the grouping happens here, at read time, where it belongs.
 *
 * <p>Deliberately a pure function over the rows rather than SQL with {@code json_agg}: the
 * severity ordering is a product decision about what to show first, and it is worth being able
 * to test it without a database.
 */
final class AnomalyGrouper {

    private AnomalyGrouper() {
    }

    /** HIGH before MEDIUM before LOW, so the worst thing about a transaction is what you see. */
    static int severityRank(String severity) {
        if (severity == null) return 3;
        return switch (severity) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            case "LOW" -> 2;
            default -> 3;
        };
    }

    /**
     * @param rows anomaly rows joined to their transaction, each carrying the transaction
     *             columns and one anomaly's columns
     * @return one entry per transaction, worst severity first, each holding every anomaly found
     *         for it
     */
    static List<Map<String, Object>> group(List<Map<String, Object>> rows) {
        // Insertion-ordered so the SQL's ordering survives until the final sort below.
        Map<Object, Map<String, Object>> byTransaction = new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            Object transactionId = row.get("transaction_id");

            Map<String, Object> entry = byTransaction.computeIfAbsent(transactionId, id -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("transactionId", String.valueOf(id));
                e.put("merchant", row.get("merchant"));
                e.put("amount", row.get("amount"));
                e.put("category", row.get("category"));
                e.put("transactionDate", str(row.get("transaction_date")));
                e.put("anomalies", new ArrayList<Map<String, Object>>());
                return e;
            });

            Map<String, Object> anomaly = new LinkedHashMap<>();
            anomaly.put("anomalyId", String.valueOf(row.get("anomaly_id")));
            anomaly.put("type", row.get("anomaly_type"));
            anomaly.put("severity", row.get("severity"));
            anomaly.put("reason", row.get("reason"));
            anomaly.put("acknowledged", row.get("acknowledged"));
            anomaly.put("createdAt", str(row.get("created_at")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) entry.get("anomalies");
            list.add(anomaly);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> entry : byTransaction.values()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) entry.get("anomalies");
            list.sort((a, b) -> Integer.compare(
                    severityRank((String) a.get("severity")), severityRank((String) b.get("severity"))));

            Map<String, Object> ordered = new LinkedHashMap<>();
            ordered.put("transactionId", entry.get("transactionId"));
            ordered.put("merchant", entry.get("merchant"));
            ordered.put("amount", entry.get("amount"));
            ordered.put("category", entry.get("category"));
            ordered.put("transactionDate", entry.get("transactionDate"));
            ordered.put("anomalyCount", list.size());
            // The entry's severity is its worst anomaly's: a transaction that is both HIGH and
            // LOW is a HIGH problem, and sorting the list above already put that one first.
            ordered.put("highestSeverity", list.isEmpty() ? null : list.get(0).get("severity"));
            // True only when every one of them has been acknowledged, so a part-handled
            // transaction does not disappear from the user's list.
            ordered.put("acknowledged", list.stream().allMatch(a -> Boolean.TRUE.equals(a.get("acknowledged"))));
            ordered.put("anomalies", list);
            out.add(ordered);
        }

        out.sort((a, b) -> Integer.compare(
                severityRank((String) a.get("highestSeverity")),
                severityRank((String) b.get("highestSeverity"))));
        return out;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
