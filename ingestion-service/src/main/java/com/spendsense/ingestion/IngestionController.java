package com.spendsense.ingestion;

import com.spendsense.common.Correlation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class IngestionController {

    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);
    private static final String TOPIC = "raw-sms-received";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${ingestion.api-key:changeme}")
    private String expectedApiKey;

    public IngestionController(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping("/sms-ingest")
    public ResponseEntity<?> ingestSms(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestBody SmsPayload payload) {

        if (apiKey == null || !MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8), expectedApiKey.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejected request with invalid API key");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "invalid api key"));
        }

        if (payload == null || payload.getRawText() == null || payload.getRawText().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "rawText is required"));
        }

        String eventId = eventIdFor(payload);
        // The id this request is already being logged under becomes the pipeline's correlation
        // id, so the HTTP call and every downstream Kafka event share one id rather than two.
        // CorrelationIdFilter has either minted it or taken it from an inbound X-Correlation-Id,
        // which is how a caller threads its own id through the whole pipeline.
        String correlationId = Correlation.currentCorrelationId();
        if (correlationId == null) correlationId = UUID.randomUUID().toString();

        // From here on this request's log lines name the SMS as well as the trace.
        Correlation.set(correlationId, eventId);

        Map<String, Object> event = Map.of(
                "eventId", eventId,
                "correlationId", correlationId,
                "rawText", payload.getRawText(),
                "senderId", payload.getSenderId() == null ? "" : payload.getSenderId(),
                "receivedAt", payload.getReceivedAt() == null ? Instant.now().toString() : payload.getReceivedAt(),
                "ingestedAt", Instant.now().toString()
        );

        kafkaTemplate.send(TOPIC, eventId, event);
        log.info("Published correlationId={} eventId={} to topic={}", correlationId, eventId, TOPIC);

        return ResponseEntity.ok(Map.of(
                "status", "received", "eventId", eventId, "correlationId", correlationId));
    }

    // Same SMS + same receivedAt (e.g. a Shortcut retry) yields the same id, so storage's
    // ON CONFLICT (source_event_id) drops the duplicate. Without receivedAt we can't tell a
    // retry from a genuinely repeated SMS, so those stay unique.
    static String eventIdFor(SmsPayload payload) {
        if (payload.getReceivedAt() == null) return UUID.randomUUID().toString();
        String key = payload.getRawText() + "|" + payload.getReceivedAt();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }
}