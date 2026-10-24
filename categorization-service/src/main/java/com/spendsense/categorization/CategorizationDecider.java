package com.spendsense.categorization;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything that turns a merchant, an amount and a direction into a stored category: the
 * few-shot examples, the model call, the off-list fallback and the review threshold.
 *
 * <p>It is separate from {@link TransactionStoredListener} so that the decision can be made
 * without a Kafka message and without a row to update - which is what the evaluation suite
 * needs. Scoring categorization by pushing SMS through the pipeline would score extraction at
 * the same time and make a wrong merchant indistinguishable from a wrong category. The listener
 * keeps the persistence and nothing else, so the measured path and the production path cannot
 * drift apart.
 */
@Component
public class CategorizationDecider {

    private static final Logger log = LoggerFactory.getLogger(CategorizationDecider.class);

    static final String MANUAL = "Needs Manual Categorization";

    static final Set<String> CATEGORIES = Set.of(
            "Food & Dining", "Groceries", "Transport", "Shopping", "Bills & Utilities",
            "Subscriptions", "Entertainment", "Health", "Travel", "Transfers", "Income",
            MANUAL, "Other");

    /** What the categorizer concluded, before anyone decides whether to store it. */
    public record Decision(String category, double confidence, String reasoning, boolean needsReview) {}

    private final JdbcTemplate jdbc;
    private final GeminiCategorizer gemini;

    @Value("${review.threshold:0.7}")
    private double reviewThreshold;

    public CategorizationDecider(JdbcTemplate jdbc, GeminiCategorizer gemini) {
        this.jdbc = jdbc;
        this.gemini = gemini;
    }

    public Decision decide(String merchant, BigDecimal amount, String transactionType) throws Exception {
        JsonNode result = gemini.categorize(merchant, amount, transactionType, recentCorrections());

        String category = result.path("category").asText(null);
        double confidence = result.path("confidence").asDouble(0.0);
        String reasoning = result.path("reasoning").asText(null);

        // An off-list answer is not trustworthy enough to store as-is.
        if (category == null || !CATEGORIES.contains(category)) {
            log.warn("Gemini returned unknown category '{}' for merchant '{}', using Other", category, merchant);
            // The model's sentence justified the category being discarded, so keeping it would
            // leave the review queue showing an explanation for a value no longer stored.
            reasoning = "Categorizer returned '" + category + "', which is not one of the "
                    + "allowed categories, so this was stored as Other and flagged for review.";
            category = "Other";
            confidence = 0.0;
        }

        boolean needsReview = confidence < reviewThreshold || MANUAL.equals(category);
        return new Decision(category, confidence, reasoning, needsReview);
    }

    private List<Map<String, Object>> recentCorrections() {
        return jdbc.queryForList("""
                SELECT t.merchant, c.corrected_category
                  FROM corrections c
                  JOIN transactions t ON t.id = c.transaction_id
                 WHERE t.merchant IS NOT NULL
                 ORDER BY c.corrected_at DESC
                 LIMIT 10
                """);
    }
}
