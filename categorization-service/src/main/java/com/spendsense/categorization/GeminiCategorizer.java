package com.spendsense.categorization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
public class GeminiCategorizer {

    private static final Logger log = LoggerFactory.getLogger(GeminiCategorizer.class);

    private static final String SYSTEM_PROMPT = """
            You categorize financial transactions. Given the merchant name,
            amount, and transaction type, assign exactly one category.

            Categories: Food & Dining, Groceries, Transport, Shopping,
            Bills & Utilities, Subscriptions, Entertainment, Health, Travel,
            Transfers, Income, Needs Manual Categorization, Other

            When the merchant is a bare personal name rather than a business,
            the direction of the money decides the category:
            - DEBIT to a personal name: "Needs Manual Categorization". Paying a
              person could be rent, a shared bill, a loan repayment or a gift,
              and the name alone does not say which. Ask rather than guess.
            - CREDIT from a personal name: "Transfers". Money arriving from an
              individual is not earnings, so it must not be "Income".

            Use "Transfers" either direction when the two sides are the same
            person's own accounts (a self-transfer, an own-account label, or a
            name matching the account holder).

            Use "Income" only for money from an employer or other organisation:
            salary, a refund, interest, a payout.
            """;

    private static final String RESPONSE_FORMAT = """

            Respond with ONLY JSON:
            - category (string, exactly one from the list)
            - confidence (number 0-1)
            - reasoning (string, one sentence)
            """;

    private final RestClient client = RestClient.create("https://generativelanguage.googleapis.com");
    private final ObjectMapper mapper;

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model}")
    private String model;

    public GeminiCategorizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param examples past corrections, newest first, injected as few-shot guidance so the model
     *                 learns from categories the user has already fixed by hand.
     */
    public JsonNode categorize(String merchant, BigDecimal amount, String transactionType,
                               List<Map<String, Object>> examples) throws Exception {
        String system = SYSTEM_PROMPT + fewShot(examples) + RESPONSE_FORMAT;
        String user = "Merchant: " + merchant
                + "\nAmount: " + amount
                + "\nType: " + transactionType;

        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))),
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
                log.warn("Gemini categorization failed (attempt {}/3): {}", attempt, e.getMessage());
                Thread.sleep(2000L * attempt);
            }
        }
        throw last;
    }

    static String fewShot(List<Map<String, Object>> examples) {
        if (examples.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\nCategories the user has corrected by hand before:\n");
        for (Map<String, Object> e : examples) {
            sb.append("- Merchant \"").append(e.get("merchant"))
                    .append("\" is \"").append(e.get("corrected_category")).append("\"\n");
        }
        return sb.toString();
    }
}
