package com.spendsense.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TransactionListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionListener.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final EmbeddingClient embeddings;

    public TransactionListener(JdbcTemplate jdbc, ObjectMapper mapper, KafkaTemplate<String, String> kafka,
                               EmbeddingClient embeddings) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.kafka = kafka;
        this.embeddings = embeddings;
    }

    // Store first, then notify: the row is committed before categorization or anomaly detection
    // runs. category stays NULL here; categorization-service fills it in later.
    @KafkaListener(topics = "transaction-extracted", groupId = "storage-service")
    public void onTransaction(String value) throws Exception {
        JsonNode t = mapper.readTree(value);
        String correlationId = text(t, "correlationId");

        BigDecimal amount = t.path("amount").isNumber() ? t.get("amount").decimalValue() : null;

        java.sql.Date date = null;
        String d = text(t, "transactionDate");
        if (d != null) {
            try {
                date = java.sql.Date.valueOf(LocalDate.parse(d));
            } catch (Exception e) {
                log.warn("Unparseable date '{}', storing null", d);
            }
        }

        UUID transactionId = UUID.randomUUID();

        // RETURNING tells us whether this was a genuine insert: on a duplicate source_event_id
        // the list comes back empty and no notification is published, so downstream services
        // never re-process an SMS.
        List<Map<String, Object>> inserted = jdbc.queryForList("""
                INSERT INTO transactions (id, correlation_id, source_event_id, raw_text, amount, currency,
                    merchant, transaction_type, transaction_date, account_last4, extraction_confidence)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (source_event_id) DO NOTHING
                RETURNING id
                """,
                transactionId, uuidOrNull(correlationId), text(t, "sourceEventId"), text(t, "rawText"),
                amount, text(t, "currency"), text(t, "merchant"), text(t, "transactionType"), date,
                text(t, "accountLast4"), t.path("extractionConfidence").asDouble(0.0));

        if (inserted.isEmpty()) {
            log.info("Duplicate sourceEventId={} ignored, no notification published",
                    text(t, "sourceEventId"));
            return;
        }

        ObjectNode notification = mapper.createObjectNode();
        notification.put("transactionId", transactionId.toString());
        notification.put("correlationId", correlationId);

        kafka.send("transaction-stored", transactionId.toString(), mapper.writeValueAsString(notification));
        log.info("Stored and notified correlationId={} transactionId={}", correlationId, transactionId);

        // Last, and deliberately: the row is already committed and downstream already notified,
        // so a failure here costs semantic search for this one row and nothing else.
        embed(transactionId, embedText(t));
        upsertMerchant(text(t, "merchant"));
    }

    /**
     * Records the merchant so aggregation questions can resolve against exact names. The row is
     * claimed first and embedded only if this call actually inserted it, so a repeat merchant
     * costs no Gemini quota at all. Best-effort, like the transaction embedding: a failure here
     * leaves the name searchable by SQL and invisible to similarity search, nothing worse.
     */
    private void upsertMerchant(String merchant) {
        if (merchant == null || merchant.isBlank()) return;

        // Stored as extraction emitted it; matched case-insensitively, so a later SWIGGY does
        // not become a second row competing with Swiggy for the same history.
        List<Map<String, Object>> inserted = jdbc.queryForList("""
                INSERT INTO merchants (id, merchant_name) VALUES (?, ?)
                ON CONFLICT (lower(merchant_name)) DO NOTHING
                RETURNING id
                """, UUID.randomUUID(), merchant);

        if (inserted.isEmpty()) return;

        try {
            String vector = embeddings.embed(merchant, EmbeddingClient.TASK_DOCUMENT);
            jdbc.update("UPDATE merchants SET embedding = ?::vector WHERE lower(merchant_name) = lower(?)",
                    vector, merchant);
            log.info("Embedded new merchant '{}'", merchant);
        } catch (Exception e) {
            log.warn("Merchant embedding failed for '{}', leaving NULL (run /api/merchants/backfill " +
                    "to retry): {}", merchant, e.getMessage());
        }
    }

    /**
     * The text that gets embedded. The extracted fields go in front of the raw SMS because the
     * cleaned merchant ("Swiggy") matches a question better than the raw payee ("swiggy@ybl").
     * category is deliberately absent: it is still NULL at this point.
     */
    static String embedText(JsonNode t) {
        String rawText = t.path("rawText").asText("");
        return (nullSafe(t, "transactionType") + " " + nullSafe(t, "amount") + " "
                + nullSafe(t, "currency") + " to " + nullSafe(t, "merchant") + " on "
                + nullSafe(t, "transactionDate") + " - " + rawText).trim();
    }

    private static String nullSafe(JsonNode t, String field) {
        JsonNode v = t.path(field);
        return (v.isMissingNode() || v.isNull()) ? "" : v.asText();
    }

    private void embed(UUID transactionId, String rawText) {
        if (rawText == null || rawText.isBlank()) return;
        try {
            String vector = embeddings.embed(rawText, EmbeddingClient.TASK_DOCUMENT);
            jdbc.update("UPDATE transactions SET embedding = ?::vector WHERE id = ?", vector, transactionId);
            log.info("Embedded transactionId={}", transactionId);
        } catch (Exception e) {
            log.warn("Embedding failed for transactionId={}, leaving NULL (RAG will not retrieve it " +
                    "until backfilled): {}", transactionId, e.getMessage());
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

    private String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText();
    }
}
