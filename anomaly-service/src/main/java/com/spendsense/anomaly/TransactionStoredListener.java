package com.spendsense.anomaly;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TransactionStoredListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionStoredListener.class);

    private final JdbcTemplate jdbc;
    private final AnomalyDetector detector;
    private final ObjectMapper mapper;

    public TransactionStoredListener(JdbcTemplate jdbc, AnomalyDetector detector, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.detector = detector;
        this.mapper = mapper;
    }

    /**
     * Reads the stored row and its history, then records any rule hits. The group id differs
     * from categorization-service so both services receive every notification.
     *
     * <p>Retries go to separate retry topics rather than blocking this consumer. The rules
     * themselves cannot fail, but Postgres can be briefly unreachable, and the design promise
     * is that a downstream service catches up rather than silently dropping a transaction.
     */
    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 10_000, multiplier = 2.0),
            exclude = JsonProcessingException.class)
    @KafkaListener(topics = "transaction-stored", groupId = "anomaly-service")
    public void onTransactionStored(String value) throws Exception {
        JsonNode n = mapper.readTree(value);
        UUID transactionId = UUID.fromString(n.path("transactionId").asText());
        String correlationId = n.path("correlationId").asText(null);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, merchant, amount, transaction_type, category, transaction_date, created_at
                  FROM transactions
                 WHERE id = ?
                """, transactionId);

        if (rows.isEmpty()) {
            log.warn("No transaction found for transactionId={} correlationId={}", transactionId, correlationId);
            return;
        }

        List<Anomaly> found = detector.detect(rows.get(0));

        if (found.isEmpty()) {
            log.info("No anomalies for correlationId={} transactionId={}", correlationId, transactionId);
            return;
        }

        for (Anomaly a : found) {
            // A replayed notification must not pile up duplicate rows, and re-inserting would
            // also wipe an acknowledged flag the user had already set. One row per
            // (transaction, type) is the invariant.
            int written = jdbc.update("""
                    INSERT INTO anomalies (id, transaction_id, correlation_id, anomaly_type, reason, severity)
                    SELECT ?, ?, ?, ?, ?, ?
                     WHERE NOT EXISTS (
                           SELECT 1 FROM anomalies WHERE transaction_id = ? AND anomaly_type = ?)
                    """,
                    UUID.randomUUID(), transactionId, uuidOrNull(correlationId), a.type(), a.reason(),
                    a.severity(), transactionId, a.type());

            if (written == 0) {
                log.info("Anomaly {} already recorded for transactionId={}, skipping", a.type(), transactionId);
            } else {
                log.info("Anomaly {} severity={} correlationId={} transactionId={}: {}",
                        a.type(), a.severity(), correlationId, transactionId, a.reason());
            }
        }
    }

    private UUID uuidOrNull(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            log.warn("Unparseable correlationId '{}', storing null", value);
            return null;
        }
    }
}
