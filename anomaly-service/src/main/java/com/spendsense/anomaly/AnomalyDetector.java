package com.spendsense.anomaly;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The four detection rules, run against spending history in Postgres. Deliberately
 * deterministic SQL and no LLM: the rules are arithmetic, so a model would only add cost,
 * latency and non-reproducibility.
 *
 * <p>merchant is compared with lower(merchant) = lower(?) rather than ILIKE, because a
 * merchant name containing % or _ would otherwise be treated as a wildcard pattern.
 */
@Component
public class AnomalyDetector {

    private final JdbcTemplate jdbc;

    @Value("${anomaly.unusual-amount.min-history:3}")
    private int minHistory;

    public AnomalyDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Anomaly> detect(Map<String, Object> txn) {
        List<Anomaly> found = new ArrayList<>();

        UUID id = (UUID) txn.get("id");
        String merchant = (String) txn.get("merchant");
        BigDecimal amount = (BigDecimal) txn.get("amount");
        String type = (String) txn.get("transaction_type");
        String category = (String) txn.get("category");
        Date date = (Date) txn.get("transaction_date");
        Timestamp createdAt = (Timestamp) txn.get("created_at");

        unusualAmount(id, merchant, category, amount, type, date).ifPresent(found::add);

        // The remaining three rules all key off the merchant, so an unidentified payee
        // cannot be compared against anything.
        if (merchant != null && !merchant.isBlank()) {
            unknownMerchant(id, merchant, date).ifPresent(found::add);
            duplicate(id, merchant, amount, date, createdAt).ifPresent(found::add);
            frequencySpike(merchant, date).ifPresent(found::add);
        }

        return found;
    }

    /** amount > 3x the 90-day average for this merchant (or category, when merchant is null). */
    private java.util.Optional<Anomaly> unusualAmount(UUID id, String merchant, String category,
                                                      BigDecimal amount, String type, Date date) {
        if (amount == null || date == null) return java.util.Optional.empty();

        boolean byMerchant = merchant != null && !merchant.isBlank();
        String peer = byMerchant ? merchant : category;
        if (peer == null) return java.util.Optional.empty();

        String column = byMerchant ? "merchant" : "category";
        Map<String, Object> stats = jdbc.queryForMap("""
                SELECT COUNT(*) AS n, AVG(amount) AS avg_amount
                  FROM transactions
                 WHERE id <> ?
                   AND lower(%s) = lower(?)
                   AND transaction_type = ?
                   AND amount IS NOT NULL
                   AND transaction_date >= ?::date - INTERVAL '90 days'
                   AND transaction_date <= ?
                """.formatted(column), id, peer, type, date, date);

        long n = ((Number) stats.get("n")).longValue();
        BigDecimal avg = (BigDecimal) stats.get("avg_amount");
        if (n < minHistory || avg == null || avg.signum() <= 0) return java.util.Optional.empty();

        double ratio = amount.divide(avg, 4, RoundingMode.HALF_UP).doubleValue();
        String severity = severityForRatio(ratio);
        if (severity == null) return java.util.Optional.empty();

        return java.util.Optional.of(new Anomaly(Anomaly.UNUSUAL_AMOUNT,
                "%s of %s is %.1fx the 90-day average of %s for %s (%d prior transactions)"
                        .formatted(type, rupees(amount), ratio, rupees(avg), peer, n),
                severity));
    }

    /** No previous transaction to this merchant in 180 days. */
    private java.util.Optional<Anomaly> unknownMerchant(UUID id, String merchant, Date date) {
        if (date == null) return java.util.Optional.empty();

        Long seen = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM transactions
                 WHERE id <> ?
                   AND lower(merchant) = lower(?)
                   AND transaction_date >= ?::date - INTERVAL '180 days'
                   AND transaction_date <= ?
                """, Long.class, id, merchant, date, date);

        if (seen == null || seen > 0) return java.util.Optional.empty();

        return java.util.Optional.of(new Anomaly(Anomaly.UNKNOWN_MERCHANT,
                "First transaction to %s in the last 180 days".formatted(merchant),
                Anomaly.LOW));
    }

    /** Same merchant + amount + date, stored within 10 minutes of this one. */
    private java.util.Optional<Anomaly> duplicate(UUID id, String merchant, BigDecimal amount,
                                                  Date date, Timestamp createdAt) {
        if (amount == null || date == null || createdAt == null) return java.util.Optional.empty();

        Long twins = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM transactions
                 WHERE id <> ?
                   AND lower(merchant) = lower(?)
                   AND amount = ?
                   AND transaction_date = ?
                   AND created_at BETWEEN ?::timestamptz - INTERVAL '10 minutes'
                                     AND ?::timestamptz + INTERVAL '10 minutes'
                """, Long.class, id, merchant, amount, date, createdAt, createdAt);

        if (twins == null || twins == 0) return java.util.Optional.empty();

        return java.util.Optional.of(new Anomaly(Anomaly.DUPLICATE,
                "%d other transaction(s) of %s to %s on %s within 10 minutes"
                        .formatted(twins, rupees(amount), merchant, date),
                Anomaly.HIGH));
    }

    /** More than 3 transactions to this merchant on one day, when the usual rate is at most 1/day. */
    private java.util.Optional<Anomaly> frequencySpike(String merchant, Date date) {
        if (date == null) return java.util.Optional.empty();

        Long today = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM transactions
                 WHERE lower(merchant) = lower(?)
                   AND transaction_date = ?
                """, Long.class, merchant, date);

        if (today == null || today <= 3) return java.util.Optional.empty();

        // Transactions per active day before today. No prior history counts as 0, so a brand
        // new merchant charging four times in a day still trips the rule.
        Double perDay = jdbc.queryForObject("""
                SELECT COALESCE(COUNT(*)::numeric / NULLIF(COUNT(DISTINCT transaction_date), 0), 0)
                  FROM transactions
                 WHERE lower(merchant) = lower(?)
                   AND transaction_date < ?
                   AND transaction_date >= ?::date - INTERVAL '90 days'
                """, Double.class, merchant, date, date);

        if (perDay != null && perDay > 1.0) return java.util.Optional.empty();

        return java.util.Optional.of(new Anomaly(Anomaly.FREQUENCY_SPIKE,
                "%d transactions to %s on %s, against a usual rate of %.2f per active day"
                        .formatted(today, merchant, date, perDay == null ? 0.0 : perDay),
                Anomaly.MEDIUM));
    }

    /** HIGH above 5x, MEDIUM above 3x, no anomaly otherwise. Null means the rule did not fire. */
    static String severityForRatio(double ratio) {
        if (ratio > 5.0) return Anomaly.HIGH;
        if (ratio > 3.0) return Anomaly.MEDIUM;
        return null;
    }

    private static String rupees(BigDecimal amount) {
        return "₹" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
