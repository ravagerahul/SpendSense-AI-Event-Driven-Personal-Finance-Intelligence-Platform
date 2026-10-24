package com.spendsense.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class GeminiQueryClient {

    private final RestClient client = RestClient.create("https://generativelanguage.googleapis.com");
    private final ObjectMapper mapper;

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model}")
    private String model;

    public GeminiQueryClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param merchants merchant names already resolved against the question by vector search.
     *                  Supplying them closes the set of legal filter values, which is what stops
     *                  the model inventing one (it previously wrote category ILIKE '%flight%').
     */
    public String generateSql(String schema, List<String> merchants, String question) throws Exception {
        String merchantList = merchants.isEmpty()
                ? "(none known yet - do not filter by merchant)"
                : merchants.stream().map(m -> "'" + m.replace("'", "''") + "'")
                        .collect(java.util.stream.Collectors.joining(", "));

        String system = """
        You write a single PostgreSQL SELECT query to answer the user's question,
        using only the table and columns described. Respond with ONLY the SQL, no
        explanation, no markdown fences. Never write INSERT, UPDATE, DELETE or DDL.
        Use ILIKE instead of = when comparing the category or merchant columns, so
        matching is case-insensitive.

        Merchants in the database most related to this question, most similar first:
          %s

        Rules for filter values:
        - Use ONLY merchant names from that list and categories from the closed list
          in the schema below. NEVER invent a merchant or a category.
        - One question can match several merchants: "flights" may mean MakeMyTrip,
          Goibibo and IndiGo alike. Match them all, never just the first.
        - Always compare merchant case-insensitively, because the stored spelling
          varies (MAKEMYTRIP and MakeMyTrip are the same merchant). For several
          merchants write merchant ILIKE ANY (ARRAY['A','B','C']); for one write
          merchant ILIKE 'A'. NEVER use merchant IN (...) or merchant = '...', both
          of which are case-sensitive and will silently miss rows.
        - The list is ranked by similarity, not filtered. Include only the merchants
          that genuinely fit the question and ignore the rest.
        - If the question singles out no merchant at all (for example "total spend
          this month"), do not filter by merchant.

        """.formatted(merchantList) + schema;

        return call(system, question).trim().replace("```sql", "").replace("```", "").trim();
    }

    public String summarize(String question, String rowsJson) throws Exception {
        String system = """
                You answer a question about personal spending using the given query result rows
                (JSON). Answer in one or two plain sentences, with concrete numbers in INR,
                written with the rupee sign (for example ₹450.00). Never use $ or the word
                dollars. If the rows are empty, say no matching transactions were found.
                """;
        return call(system, "Question: " + question + "\nRows: " + rowsJson);
    }

    /** Decides which path answers the question: pgvector similarity search, or text-to-SQL. */
    public String classifyQuestion(String question) throws Exception {
        String system = """
                Classify this question as "semantic" or "aggregation":
                - aggregation: the answer is a number computed over transactions - sum, total,
                  count, average, "how much", "how many", or a comparison between periods.
                - semantic: the answer is one or more particular transactions, recalled by
                  meaning rather than computed ("that payment last Tuesday", "what was that
                  charge from the airport").

                Choose "aggregation" whenever a number is wanted, even if the question names a
                specific thing rather than a category ("how much did I pay for my flight
                booking?"). Such names are resolved to real merchants before the query runs, so
                the arithmetic still covers every matching transaction.

                Respond with ONLY one word: semantic or aggregation
                """;
        return call(system, question);
    }

    public String summarizeRetrieved(String question, String transactionsJson) throws Exception {
        String system = """
                Answer the user's question about their personal spending using
                ONLY the retrieved transactions below. Answer in one or two
                sentences with concrete numbers in ₹ (INR). If no relevant
                transactions were found, say so.
                """;
        return call(system, "Question: " + question + "\nRetrieved transactions: " + transactionsJson);
    }

    private String call(String system, String userText) throws Exception {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userText)))),
                "generationConfig", Map.of("temperature", 0)
        );
        String response = client.post()
                .uri("/v1beta/models/{model}:generateContent", model)
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        return mapper.readTree(response).path("candidates").get(0)
                .path("content").path("parts").get(0).path("text").asText();
    }
}