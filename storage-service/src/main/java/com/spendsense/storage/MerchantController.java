package com.spendsense.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/merchants")
public class MerchantController {

    private static final Logger log = LoggerFactory.getLogger(MerchantController.class);

    /** One embedding call per second, to stay well inside the free-tier rate limit. */
    private static final long CALL_INTERVAL_MS = 1000;

    private final JdbcTemplate jdbc;
    private final EmbeddingClient embeddings;

    public MerchantController(JdbcTemplate jdbc, EmbeddingClient embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    @GetMapping
    public List<Map<String, Object>> all() {
        return jdbc.queryForList("""
                SELECT merchant_name, (embedding IS NOT NULL) AS embedded, first_seen
                  FROM merchants
                 ORDER BY merchant_name
                """);
    }

    /**
     * Brings the merchants table in line with the transactions already stored: inserts any name
     * that is missing, then embeds every row still lacking a vector. Safe to re-run - it only
     * spends quota on rows that are actually missing an embedding, so a second call after a
     * partial failure retries just the stragglers.
     */
    @PostMapping("/backfill")
    public Map<String, Object> backfill() throws InterruptedException {
        // DISTINCT ON (lower(merchant)) rather than DISTINCT merchant: if the transactions table
        // holds both SWIGGY and Swiggy, plain DISTINCT yields two rows that collide with each
        // other inside this single statement. Folding first picks one spelling to store.
        int inserted = jdbc.update("""
                INSERT INTO merchants (id, merchant_name)
                SELECT gen_random_uuid(), d.merchant
                  FROM (SELECT DISTINCT ON (lower(merchant)) merchant
                          FROM transactions
                         WHERE merchant IS NOT NULL AND merchant <> ''
                         ORDER BY lower(merchant), merchant) d
                ON CONFLICT (lower(merchant_name)) DO NOTHING
                """);

        List<String> pending = jdbc.queryForList(
                "SELECT merchant_name FROM merchants WHERE embedding IS NULL ORDER BY merchant_name",
                String.class);

        int embedded = 0;
        List<String> failed = new ArrayList<>();

        for (String name : pending) {
            try {
                String vector = embeddings.embed(name, EmbeddingClient.TASK_DOCUMENT);
                jdbc.update("UPDATE merchants SET embedding = ?::vector WHERE lower(merchant_name) = lower(?)",
                        vector, name);
                embedded++;
            } catch (Exception e) {
                failed.add(name);
                log.warn("Backfill embedding failed for '{}': {}", name, e.getMessage());
            }
            Thread.sleep(CALL_INTERVAL_MS);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", inserted);
        result.put("embedded", embedded);
        result.put("failed", failed);
        result.put("totalMerchants", jdbc.queryForObject("SELECT count(*) FROM merchants", Integer.class));

        log.info("Merchant backfill: inserted={} embedded={} failed={}", inserted, embedded, failed.size());
        return result;
    }
}
