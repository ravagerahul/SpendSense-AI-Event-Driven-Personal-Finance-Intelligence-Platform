package com.spendsense.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The BFF. One entry point for the frontend, so the React app never has to know that there are
 * eight services behind it, and so a single screen is one request rather than four.
 *
 * <p>Reads Postgres directly for data and proxies only where the real work lives elsewhere:
 * the chat question (query-service routes, embeds and generates SQL) and the review correction
 * (storage-service owns the corrections write that feeds categorization). Forwarding a plain
 * SELECT would add a network hop and a second thing that can be down, for nothing.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    /** Listed explicitly so the 768-value embedding is never serialized into a response. */
    private static final String TRANSACTION_COLUMNS = """
            id, correlation_id, raw_text, amount, currency, merchant, transaction_type,
            transaction_date, account_last4, category, extraction_confidence,
            categorization_confidence, categorization_reasoning, needs_review, review_corrected,
            corrected_category, created_at
            """;

    private static final int MAX_LIMIT = 500;

    private final JdbcTemplate jdbc;
    private final ServiceProxy proxy;
    private final ObjectMapper mapper;

    @Value("${dashboard.query-service.url}")
    private String queryServiceUrl;

    @Value("${dashboard.storage-service.url}")
    private String storageServiceUrl;

    public DashboardController(JdbcTemplate jdbc, ServiceProxy proxy, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.proxy = proxy;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- summary

    /**
     * Everything the dashboard screen needs, in one response: this calendar month's total, the
     * category breakdown, and the ten most recent transactions. Three round trips from the
     * browser for one screen is exactly what the BFF exists to avoid.
     *
     * <p>Month boundaries are computed in Java and bound as date parameters. Doing it in SQL
     * means {@code ? - INTERVAL '1 month'}, where the driver sends the parameter untyped and
     * Postgres resolves the expression to an interval, failing with "operator does not exist:
     * date >= interval".
     */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);

        BigDecimal total = debitTotal(monthStart, today);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodStart", monthStart.toString());
        out.put("periodEnd", today.toString());
        out.put("totalSpend", total);
        out.put("creditTotal", creditTotal(monthStart, today));
        out.put("transactionCount", jdbc.queryForObject("""
                SELECT COUNT(*) FROM transactions
                 WHERE transaction_type = 'DEBIT' AND transaction_date BETWEEN ? AND ?
                """, Integer.class, date(monthStart), date(today)));
        out.put("categoryBreakdown", categoryBreakdown(monthStart, today, total));
        out.put("recentTransactions", jdbc.queryForList(
                "SELECT " + TRANSACTION_COLUMNS + " FROM transactions ORDER BY created_at DESC LIMIT 10"));
        return out;
    }

    private BigDecimal debitTotal(LocalDate from, LocalDate to) {
        BigDecimal sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM transactions
                 WHERE transaction_type = 'DEBIT' AND transaction_date BETWEEN ? AND ?
                """, BigDecimal.class, date(from), date(to));
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private BigDecimal creditTotal(LocalDate from, LocalDate to) {
        BigDecimal sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM transactions
                 WHERE transaction_type = 'CREDIT' AND transaction_date BETWEEN ? AND ?
                """, BigDecimal.class, date(from), date(to));
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private List<Map<String, Object>> categoryBreakdown(LocalDate from, LocalDate to, BigDecimal total) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT category, SUM(amount) AS category_total, COUNT(*) AS transactions
                  FROM transactions
                 WHERE transaction_type = 'DEBIT' AND category IS NOT NULL
                   AND transaction_date BETWEEN ? AND ?
                 GROUP BY category
                 ORDER BY category_total DESC
                """, date(from), date(to));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("category", r.get("category"));
            entry.put("total", r.get("category_total"));
            entry.put("transactions", r.get("transactions"));
            entry.put("percentOfSpend", percentOf((BigDecimal) r.get("category_total"), total));
            out.add(entry);
        }
        return out;
    }

    /** Null rather than 0 when nothing was spent: there is no share of an empty total. */
    static Double percentOf(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() == 0) return null;
        return part.divide(whole, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    // ----------------------------------------------------------- transactions

    /**
     * Filtered transaction list. Every filter is optional and absent ones are left out of the
     * SQL entirely rather than being rendered as a clause that matches everything, so the query
     * plan matches the question asked.
     */
    @GetMapping("/transactions")
    public List<Map<String, Object>> transactions(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String merchant,
            @RequestParam(required = false, defaultValue = "100") int limit) {

        StringBuilder sql = new StringBuilder("SELECT " + TRANSACTION_COLUMNS + " FROM transactions WHERE 1=1");
        List<Object> args = new ArrayList<>();

        if (notBlank(category)) {
            // ILIKE, not =: "food" should find "Food & Dining" without the caller having to
            // know the exact stored casing.
            sql.append(" AND category ILIKE ?");
            args.add(category.trim());
        }
        if (notBlank(from)) {
            sql.append(" AND transaction_date >= ?");
            args.add(date(LocalDate.parse(from.trim())));
        }
        if (notBlank(to)) {
            sql.append(" AND transaction_date <= ?");
            args.add(date(LocalDate.parse(to.trim())));
        }
        if (notBlank(merchant)) {
            // A substring search, because this is the UI's merchant search box rather than an
            // exact lookup. The wildcards go in the argument, never in the SQL string.
            sql.append(" AND merchant ILIKE ?");
            args.add("%" + merchant.trim() + "%");
        }

        sql.append(" ORDER BY transaction_date DESC, created_at DESC LIMIT ?");
        // Clamped so a caller cannot ask for the whole table, by accident or otherwise.
        args.add(Math.max(1, Math.min(limit, MAX_LIMIT)));

        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // ------------------------------------------------------------------- chat

    /**
     * Proxied, not reimplemented: the question router, the merchant resolution and the SQL
     * generation all live in query-service. The correlation id travels with it, so one chat
     * question traces from here into that service under a single id.
     */
    @PostMapping("/query")
    public Map<String, Object> query(@RequestBody Map<String, Object> body) {
        return proxy.post(queryServiceUrl, "/api/query", body);
    }

    // ----------------------------------------------------------------- review

    @GetMapping("/review")
    public List<Map<String, Object>> review() {
        return jdbc.queryForList("SELECT " + TRANSACTION_COLUMNS + """
                 FROM transactions
                 WHERE needs_review
                 ORDER BY categorization_confidence ASC NULLS FIRST, created_at DESC
                """);
    }

    /**
     * Proxied to storage-service, which owns the two-table write: the corrections row that
     * categorization-service later reads back as a few-shot example, and the update to the
     * transaction. Duplicating that here would mean two implementations of the feedback loop.
     */
    @PostMapping("/review/{id}/correct")
    public Map<String, Object> correct(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return proxy.post(storageServiceUrl, "/api/transactions/review/" + id + "/correct", body);
    }

    // -------------------------------------------------------------- anomalies

    /**
     * One entry per transaction, each carrying every anomaly found for it. See
     * {@link AnomalyGrouper} for why this grouping happens at read time rather than at write
     * time.
     */
    @GetMapping("/anomalies")
    public List<Map<String, Object>> anomalies(
            @RequestParam(required = false, defaultValue = "false") boolean includeAcknowledged) {

        String filter = includeAcknowledged ? "" : " WHERE NOT a.acknowledged";

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id AS anomaly_id, a.transaction_id, a.anomaly_type, a.reason, a.severity,
                       a.acknowledged, a.created_at,
                       t.merchant, t.amount, t.category, t.transaction_date
                  FROM anomalies a
                  JOIN transactions t ON t.id = a.transaction_id
                """ + filter + """
                 ORDER BY a.created_at DESC
                """);

        return AnomalyGrouper.group(rows);
    }

    /**
     * Acknowledges one anomaly, by its own id rather than its transaction's. A transaction with
     * two findings has two independently acknowledgeable rows, and the grouped response above
     * carries each {@code anomalyId} so the UI can dismiss one and leave the other standing.
     */
    @PostMapping("/anomalies/{id}/acknowledge")
    public ResponseEntity<?> acknowledge(@PathVariable UUID id) {
        int updated = jdbc.update("UPDATE anomalies SET acknowledged = TRUE WHERE id = ?", id);

        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }

        log.info("Acknowledged anomalyId={}", id);
        return ResponseEntity.ok(Map.of("status", "acknowledged", "anomalyId", id.toString()));
    }

    // --------------------------------------------------------------- insights

    /**
     * The latest generated summary, read straight from the table. insights-service is a
     * scheduled job rather than a live dependency, so the dashboard should still show last
     * week's numbers while that service is stopped.
     */
    @GetMapping("/insights")
    public Map<String, Object> insights() throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT period_start, period_end, total_spend, previous_total_spend, change_pct,
                       top_categories, subscriptions, anomalies, generated_at
                  FROM insights
                 ORDER BY generated_at DESC
                 LIMIT 1
                """);

        if (rows.isEmpty()) {
            return Map.of("status", "no insights generated yet",
                    "hint", "POST http://localhost:8086/api/insights/generate");
        }

        Map<String, Object> row = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodStart", String.valueOf(row.get("period_start")));
        out.put("periodEnd", String.valueOf(row.get("period_end")));
        out.put("totalSpend", row.get("total_spend"));
        out.put("previousTotalSpend", row.get("previous_total_spend"));
        // Passed through as-is. null means "no previous period to compare against"; coercing it
        // to 0 would claim spending held steady when in fact nothing is known.
        out.put("changePct", row.get("change_pct"));
        out.put("topCategories", readJson(row.get("top_categories")));
        out.put("subscriptions", readJson(row.get("subscriptions")));
        out.put("anomalies", readJson(row.get("anomalies")));
        out.put("generatedAt", String.valueOf(row.get("generated_at")));
        return out;
    }

    private Object readJson(Object jsonb) throws Exception {
        return jsonb == null ? List.of() : mapper.readTree(jsonb.toString());
    }

    // ---------------------------------------------------------------- helpers

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static java.sql.Date date(LocalDate d) {
        return java.sql.Date.valueOf(d);
    }
}
