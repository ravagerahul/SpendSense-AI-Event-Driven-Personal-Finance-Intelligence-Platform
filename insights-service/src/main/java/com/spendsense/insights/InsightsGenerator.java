package com.spendsense.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the periodic spending summary. Read-mostly: it only writes its own insights row.
 *
 * <p>All date arithmetic happens in Java and reaches Postgres as bound date parameters. Doing
 * it in SQL would mean expressions like {@code ? - INTERVAL '7 days'}, where the driver sends
 * the parameter untyped and Postgres resolves it to an interval, producing
 * "operator does not exist: date >= interval" at runtime.
 */
@Service
public class InsightsGenerator {

    private static final Logger log = LoggerFactory.getLogger(InsightsGenerator.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Value("${insights.period-days:7}")
    private int periodDays;

    @Value("${insights.subscription.min-occurrences:3}")
    private int minOccurrences;

    public InsightsGenerator(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Weekly job. The same work is reachable on demand via POST /api/insights/generate. */
    @Scheduled(cron = "${insights.cron:0 0 0 * * SUN}")
    public void scheduled() {
        log.info("Scheduled insights run starting");
        try {
            generate();
        } catch (Exception e) {
            // A scheduled run that throws would otherwise vanish into the scheduler's logs.
            log.error("Scheduled insights run failed", e);
        }
    }

    @Transactional
    public Map<String, Object> generate() throws Exception {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(periodDays - 1L);
        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(periodDays - 1L);

        BigDecimal total = spendBetween(start, end);
        BigDecimal previous = spendBetween(prevStart, prevEnd);
        Double changePct = percentChange(previous, total);

        List<Map<String, Object>> topCategories = topCategories(start, end, total);
        List<Map<String, Object>> subscriptions = subscriptions();
        List<Map<String, Object>> anomalies = anomaliesSince(start);

        jdbc.update("""
                INSERT INTO insights (id, period_start, period_end, total_spend,
                    previous_total_spend, change_pct, top_categories, subscriptions, anomalies)
                VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb)
                ON CONFLICT (period_start, period_end) DO UPDATE SET
                    total_spend = EXCLUDED.total_spend,
                    previous_total_spend = EXCLUDED.previous_total_spend,
                    change_pct = EXCLUDED.change_pct,
                    top_categories = EXCLUDED.top_categories,
                    subscriptions = EXCLUDED.subscriptions,
                    anomalies = EXCLUDED.anomalies,
                    generated_at = now()
                """,
                UUID.randomUUID(), java.sql.Date.valueOf(start), java.sql.Date.valueOf(end),
                total, previous, changePct,
                mapper.writeValueAsString(topCategories),
                mapper.writeValueAsString(subscriptions),
                mapper.writeValueAsString(anomalies));

        log.info("Generated insights for {}..{}: total={} previous={} change={}%",
                start, end, total, previous, changePct);

        return latest();
    }

    /** The most recently generated period, or an empty marker if none exists yet. */
    public Map<String, Object> latest() throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT period_start, period_end, total_spend, previous_total_spend, change_pct,
                       top_categories, subscriptions, anomalies, generated_at
                  FROM insights
                 ORDER BY generated_at DESC
                 LIMIT 1
                """);

        if (rows.isEmpty()) {
            return Map.of("status", "no insights generated yet",
                    "hint", "POST /api/insights/generate");
        }

        Map<String, Object> row = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodStart", String.valueOf(row.get("period_start")));
        out.put("periodEnd", String.valueOf(row.get("period_end")));
        out.put("totalSpend", row.get("total_spend"));
        out.put("previousTotalSpend", row.get("previous_total_spend"));
        out.put("changePct", row.get("change_pct"));
        out.put("topCategories", readJson(row.get("top_categories")));
        out.put("subscriptions", readJson(row.get("subscriptions")));
        out.put("anomalies", readJson(row.get("anomalies")));
        out.put("generatedAt", String.valueOf(row.get("generated_at")));
        return out;
    }

    private BigDecimal spendBetween(LocalDate start, LocalDate end) {
        BigDecimal sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                  FROM transactions
                 WHERE transaction_type = 'DEBIT'
                   AND transaction_date BETWEEN ? AND ?
                """, BigDecimal.class, java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private List<Map<String, Object>> topCategories(LocalDate start, LocalDate end, BigDecimal total) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT category, SUM(amount) AS category_total, COUNT(*) AS transactions
                  FROM transactions
                 WHERE transaction_type = 'DEBIT'
                   AND category IS NOT NULL
                   AND transaction_date BETWEEN ? AND ?
                 GROUP BY category
                 ORDER BY category_total DESC
                 LIMIT 5
                """, java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal categoryTotal = (BigDecimal) r.get("category_total");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("category", r.get("category"));
            entry.put("total", categoryTotal);
            entry.put("transactions", r.get("transactions"));
            entry.put("percentOfSpend", percentOf(categoryTotal, total));
            out.add(entry);
        }
        return out;
    }

    /**
     * Recurring charges, detected across all history rather than the reporting period: a weekly
     * window is too short to see a monthly subscription repeat, so restricting it to the period
     * would find nothing.
     */
    private List<Map<String, Object>> subscriptions() {
        return jdbc.queryForList("""
                SELECT merchant, amount, COUNT(DISTINCT transaction_date) AS occurrences,
                       MIN(transaction_date) AS first_charge, MAX(transaction_date) AS last_charge
                  FROM transactions
                 WHERE transaction_type = 'DEBIT' AND merchant IS NOT NULL AND amount IS NOT NULL
                 GROUP BY merchant, amount
                HAVING COUNT(DISTINCT transaction_date) >= ?
                 ORDER BY occurrences DESC, amount DESC
                """, minOccurrences);
    }

    private List<Map<String, Object>> anomaliesSince(LocalDate start) {
        return jdbc.queryForList("""
                SELECT a.anomaly_type, a.severity, a.reason, a.acknowledged,
                       t.merchant, t.amount
                  FROM anomalies a
                  JOIN transactions t ON t.id = a.transaction_id
                 WHERE a.created_at >= ?
                 ORDER BY a.created_at DESC
                """, java.sql.Date.valueOf(start));
    }

    /** Null when there is no previous spend to compare against, rather than a misleading 0 or 100. */
    static Double percentChange(BigDecimal previous, BigDecimal current) {
        if (previous == null || previous.signum() == 0 || current == null) return null;
        return current.subtract(previous)
                .divide(previous, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    static Double percentOf(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() == 0) return null;
        return part.divide(whole, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private Object readJson(Object jsonb) throws Exception {
        return jsonb == null ? List.of() : mapper.readTree(jsonb.toString());
    }
}
