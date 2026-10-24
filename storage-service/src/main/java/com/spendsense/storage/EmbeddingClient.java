package com.spendsense.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    /** Use when embedding a transaction for storage. */
    public static final String TASK_DOCUMENT = "RETRIEVAL_DOCUMENT";

    private final RestClient client = RestClient.create("https://generativelanguage.googleapis.com");
    private final ObjectMapper mapper;

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.embedding-model}")
    private String model;

    @Value("${gemini.embedding-dimensions}")
    private int dimensions;

    public EmbeddingClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Returns the embedding as a pgvector literal, ready to bind to an {@code ?::vector} parameter.
     * outputDimensionality is always sent: the model's native size is 3072, but the embedding
     * column is vector(768), so an unconstrained response would not fit.
     */
    public String embed(String text, String taskType) throws Exception {
        Map<String, Object> body = Map.of(
                "content", Map.of("parts", List.of(Map.of("text", text))),
                "taskType", taskType,
                "outputDimensionality", dimensions
        );

        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String response = client.post()
                        .uri("/v1beta/models/{model}:embedContent", model)
                        .header("x-goog-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);

                return toVectorLiteral(mapper.readTree(response).path("embedding").path("values"), dimensions);
            } catch (Exception e) {
                last = e;
                log.warn("Embedding call failed (attempt {}/3): {}", attempt, e.getMessage());
                Thread.sleep(2000L * attempt);
            }
        }
        throw last;
    }

    static String toVectorLiteral(JsonNode values, int expected) {
        if (!values.isArray() || values.size() != expected) {
            throw new IllegalStateException(
                    "Expected " + expected + " embedding values but got " + values.size());
        }
        StringBuilder sb = new StringBuilder(expected * 12).append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(values.get(i).asDouble());
        }
        return sb.append(']').toString();
    }
}
