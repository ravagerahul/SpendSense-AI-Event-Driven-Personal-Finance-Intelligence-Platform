package com.spendsense.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

    // Listed explicitly so the embedding vector is never serialized into API responses.
    private static final String COLUMNS = """
            id, correlation_id, source_event_id, raw_text, amount, currency, merchant,
            transaction_type, transaction_date, account_last4, category, extraction_confidence,
            categorization_confidence, categorization_reasoning, needs_review, review_corrected,
            corrected_category, created_at
            """;

    private final JdbcTemplate jdbc;

    public TransactionController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String, Object>> all() {
        return jdbc.queryForList(
                "SELECT " + COLUMNS + " FROM transactions ORDER BY created_at DESC LIMIT 100");
    }

    @GetMapping("/review")
    public List<Map<String, Object>> review() {
        return jdbc.queryForList(
                "SELECT " + COLUMNS + " FROM transactions WHERE needs_review ORDER BY created_at DESC");
    }

    /**
     * Records a manual category fix. The correction is kept in its own table so
     * categorization-service can feed past fixes back to the model as few-shot examples.
     */
    @PostMapping("/review/{id}/correct")
    @Transactional
    public ResponseEntity<?> correct(@PathVariable UUID id, @RequestBody CorrectionRequest body) {
        if (body == null || body.correctedCategory() == null || body.correctedCategory().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "correctedCategory is required"));
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT category FROM transactions WHERE id = ?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String originalCategory = (String) rows.get(0).get("category");
        String correctedCategory = body.correctedCategory().trim();

        jdbc.update("""
                INSERT INTO corrections (id, transaction_id, original_category, corrected_category)
                VALUES (?,?,?,?)
                """, UUID.randomUUID(), id, originalCategory, correctedCategory);

        // category is updated too, so every reader sees the corrected value without having to
        // know about corrected_category; the original is preserved in the corrections table.
        jdbc.update("""
                UPDATE transactions
                   SET category = ?, corrected_category = ?, review_corrected = TRUE, needs_review = FALSE
                 WHERE id = ?
                """, correctedCategory, correctedCategory, id);

        log.info("Corrected transactionId={} from '{}' to '{}'", id, originalCategory, correctedCategory);

        return ResponseEntity.ok(Map.of(
                "status", "corrected",
                "transactionId", id.toString(),
                "originalCategory", originalCategory == null ? "" : originalCategory,
                "correctedCategory", correctedCategory));
    }
}
