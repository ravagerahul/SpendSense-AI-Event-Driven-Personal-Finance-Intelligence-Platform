package com.spendsense.categorization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TransactionStoredListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionStoredListener.class);

    private final JdbcTemplate jdbc;
    private final CategorizationDecider decider;
    private final ObjectMapper mapper;

    public TransactionStoredListener(JdbcTemplate jdbc, CategorizationDecider decider, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.decider = decider;
        this.mapper = mapper;
    }

    // The event carries only an id, so the full row is read from Postgres here. Gemini failures
    // are retried on separate retry topics (30s, 60s, 120s) rather than blocking this consumer.
    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 30_000, multiplier = 2.0),
            exclude = JsonProcessingException.class)
    @KafkaListener(topics = "transaction-stored", groupId = "categorization-service")
    public void onTransactionStored(String value) throws Exception {
        JsonNode n = mapper.readTree(value);
        UUID transactionId = UUID.fromString(n.path("transactionId").asText());
        String correlationId = n.path("correlationId").asText(null);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT merchant, amount, transaction_type, category FROM transactions WHERE id = ?",
                transactionId);

        if (rows.isEmpty()) {
            log.warn("No transaction found for transactionId={} correlationId={}", transactionId, correlationId);
            return;
        }

        Map<String, Object> row = rows.get(0);
        if (row.get("category") != null) {
            log.info("transactionId={} already categorized as '{}', skipping",
                    transactionId, row.get("category"));
            return;
        }

        // The decision itself - few-shot examples, the model call, the off-list fallback and the
        // review threshold - lives in CategorizationDecider, so the evaluation suite measures
        // exactly this logic rather than a second copy of it. This listener only persists.
        CategorizationDecider.Decision d = decider.decide(
                (String) row.get("merchant"),
                (BigDecimal) row.get("amount"),
                (String) row.get("transaction_type"));

        // Written in the same UPDATE as the category it justifies, so the two can never disagree
        // about which decision the sentence explains. The review queue reads it back to say why
        // a transaction was flagged; before it was stored, the queue could only report a number.
        jdbc.update("""
                UPDATE transactions
                   SET category = ?, categorization_confidence = ?, needs_review = ?,
                       categorization_reasoning = ?
                 WHERE id = ?
                """, d.category(), d.confidence(), d.needsReview(), d.reasoning(), transactionId);

        log.info("Categorized correlationId={} transactionId={} as '{}' confidence={} needsReview={} ({})",
                correlationId, transactionId, d.category(), d.confidence(), d.needsReview(), d.reasoning());
    }
}
