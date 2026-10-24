package com.spendsense.categorization;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A synchronous way to ask the categorizer what it would do, without a Kafka message and
 * without touching a row. It exists for the evaluation suite: scoring categorization by
 * pushing SMS through the pipeline would score extraction at the same time, and a wrong
 * merchant would be indistinguishable from a wrong category.
 *
 * <p>It shares {@link CategorizationDecider} with the Kafka listener rather than re-reading
 * the prompt or the threshold, so a measured score is a score of the deployed behaviour. It is
 * read-only against the database (it reads past corrections, exactly as the listener does) and
 * writes nothing, so running an eval cannot change stored data.
 *
 * <p>Not reachable from outside the host: Caddy routes only /api/sms-ingest and the BFF, and
 * categorization-service publishes no port.
 */
@RestController
@RequestMapping("/api/categorize")
public class CategorizationController {

    public record CategorizeRequest(String merchant, BigDecimal amount, String transactionType) {}

    private final CategorizationDecider decider;

    public CategorizationController(CategorizationDecider decider) {
        this.decider = decider;
    }

    @PostMapping
    public ResponseEntity<?> categorize(@RequestBody CategorizeRequest body) throws Exception {
        if (body == null || body.merchant() == null || body.merchant().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "merchant is required"));
        }
        return ResponseEntity.ok(decider.decide(body.merchant(), body.amount(), body.transactionType()));
    }
}
