package com.spendsense.extraction;

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
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    // Extraction only. Categorization is a separate service reading from Postgres.
    private static final String SYSTEM_PROMPT = """
            You extract transaction details from Indian bank SMS messages.
            Respond with ONLY a JSON object:
            - isTransaction (boolean): false for OTPs, promos, balance alerts
            - amount (number or null)
            - currency (string, default "INR")
            - merchant (string or null): cleaned up (swiggy@ybl -> Swiggy)
            - transactionType ("DEBIT" or "CREDIT" or null)
            - transactionDate (string YYYY-MM-DD or null)
            - accountLast4 (string or null)
            - confidence (number 0-1)
            """;

    private final RestClient client = RestClient.create("https://generativelanguage.googleapis.com");
    private final ObjectMapper mapper;

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model}")
    private String model;

    public GeminiClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode analyze(String smsText) throws Exception {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", SYSTEM_PROMPT))),
                "contents", List.of(Map.of("role", "user",
                        "parts", List.of(Map.of("text", maskBalance(smsText))))),
                "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0)
        );

        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String response = client.post()
                        .uri("/v1beta/models/{model}:generateContent", model)
                        .header("x-goog-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);

                String text = mapper.readTree(response)
                        .path("candidates").get(0)
                        .path("content").path("parts").get(0)
                        .path("text").asText();
                return mapper.readTree(text);
            } catch (Exception e) {
                last = e;
                log.warn("Gemini call failed (attempt {}/3): {}", attempt, e.getMessage());
                Thread.sleep(2000L * attempt);
            }
        }
        throw last;
    }

    // Privacy only, not parsing: hides the account balance before the SMS leaves your machine.
    static String maskBalance(String sms) {
        return sms.replaceAll(
                "(?i)(avl\\.?\\s*bal(ance)?|bal(ance)?)[:\\s]*(rs\\.?|inr)?\\s*[\\d,]+(\\.\\d+)?",
                "balance hidden");
    }
}