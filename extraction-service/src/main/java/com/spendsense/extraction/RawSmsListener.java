package com.spendsense.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class RawSmsListener {

    private static final Logger log = LoggerFactory.getLogger(RawSmsListener.class);

    private final GeminiClient gemini;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;

    public RawSmsListener(GeminiClient gemini, ObjectMapper mapper, KafkaTemplate<String, String> kafka) {
        this.gemini = gemini;
        this.mapper = mapper;
        this.kafka = kafka;
    }

    // Failures (Gemini 429/5xx, timeouts) are retried on separate retry topics with growing delays
    // (30s, 60s, 120s) without blocking this consumer. A message that exhausts them, or is
    // unparseable, goes to raw-sms-failed via the DLT handler below.
    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 30_000, multiplier = 2.0),
            exclude = JsonProcessingException.class)
    @KafkaListener(topics = "raw-sms-received", groupId = "extraction-service")
    public void onRawSms(String value) throws Exception {
        JsonNode in = mapper.readTree(value);
        String sourceEventId = in.path("eventId").asText();
        String correlationId = in.path("correlationId").asText(null);
        String rawText = in.path("rawText").asText();

        JsonNode r = gemini.analyze(rawText);

        if (!r.path("isTransaction").asBoolean(false)) {
            log.info("Skipping non-transaction SMS correlationId={} eventId={}", correlationId, sourceEventId);
            return;
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("eventId", UUID.randomUUID().toString());
        out.put("correlationId", correlationId);
        out.put("sourceEventId", sourceEventId);
        out.put("rawText", rawText);
        out.set("amount", r.get("amount"));
        out.set("currency", r.get("currency"));
        out.set("merchant", r.get("merchant"));
        out.set("transactionType", r.get("transactionType"));
        out.set("transactionDate", r.get("transactionDate"));
        out.set("accountLast4", r.get("accountLast4"));
        out.put("extractionConfidence", r.path("confidence").asDouble(0.0));

        kafka.send("transaction-extracted", sourceEventId, mapper.writeValueAsString(out));
        log.info("Published transaction-extracted correlationId={} sourceEventId={}",
                correlationId, sourceEventId);
    }

    @DltHandler
    public void onExhausted(String value,
                            @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) String reason) {
        String eventId = "unknown";
        String correlationId = null;
        try {
            JsonNode in = mapper.readTree(value);
            eventId = in.path("eventId").asText("unknown");
            correlationId = in.path("correlationId").asText(null);
        } catch (Exception ignored) {
            // Unparseable payload: keep the placeholders and still preserve rawText below.
        }

        ObjectNode failed = mapper.createObjectNode();
        failed.put("eventId", eventId);
        failed.put("correlationId", correlationId);
        failed.put("rawText", value);
        failed.put("failureReason", reason == null ? "unknown" : reason);
        failed.put("failedAt", Instant.now().toString());

        log.error("Giving up on correlationId={} eventId={} after retries: {}", correlationId, eventId, reason);
        kafka.send("raw-sms-failed", eventId, failed.toString());
    }
}
