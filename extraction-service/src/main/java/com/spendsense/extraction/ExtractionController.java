package com.spendsense.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A synchronous way to ask the extractor what it reads out of one SMS, with no Kafka message,
 * no database row and no downstream work. It exists for the evaluation suite, and it is the
 * same {@link GeminiClient#analyze} call the Kafka listener makes - same prompt, same balance
 * masking, same retries - so a measured score is a score of the deployed behaviour.
 *
 * <p>Scoring extraction through the pipeline instead costs four model calls per message
 * (extraction, two embeddings, categorization) and takes twenty seconds of rate-limited waiting
 * per case, which makes running the suite five times to measure variance impossible rather than
 * merely slow. It also conflates components: a row that never arrived could be extraction, Kafka,
 * storage or the embedding call, and the eval cannot tell which.
 *
 * <p>Read-only in every sense: it publishes nothing and writes nothing. Not routed through
 * Caddy, and it must not be - it is a measurement endpoint. {@code docker-compose.eval.yml}
 * publishes it on 127.0.0.1 for the duration of a run.
 */
@RestController
@RequestMapping("/api/extract")
public class ExtractionController {

    public record ExtractRequest(String rawText) {}

    private final GeminiClient gemini;

    public ExtractionController(GeminiClient gemini) {
        this.gemini = gemini;
    }

    @PostMapping
    public ResponseEntity<?> extract(@RequestBody ExtractRequest body) throws Exception {
        if (body == null || body.rawText() == null || body.rawText().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "rawText is required"));
        }
        JsonNode result = gemini.analyze(body.rawText());
        return ResponseEntity.ok(result);
    }
}
