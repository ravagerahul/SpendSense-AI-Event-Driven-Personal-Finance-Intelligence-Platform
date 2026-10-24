package com.spendsense.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SpendQueryService {

    private static final Logger log = LoggerFactory.getLogger(SpendQueryService.class);

    // Shown to the LLM so it writes queries that match the real columns. The closed category
    // list matters: without it the model invents values like category ILIKE '%flight%', which
    // matches nothing because a flight is stored as category 'Travel'.
    private static final String SCHEMA = """
            Table: transactions
            Columns: amount numeric, currency text, merchant text, transaction_type text ('DEBIT' or 'CREDIT'),
                     transaction_date date, category text, raw_text text, extraction_confidence double precision,
                     categorization_confidence double precision, needs_review boolean,
                     review_corrected boolean, corrected_category text, created_at timestamptz

            category is one of exactly these values and nothing else:
              Food & Dining, Groceries, Transport, Shopping, Bills & Utilities, Subscriptions,
              Entertainment, Health, Travel, Transfers, Income, Needs Manual Categorization, Other
            Never invent a category outside that list.

            Categories are broad. 'Travel' covers flights, trains, buses and hotels alike, so a
            question about one specific kind of thing cannot be answered by category alone. When
            the question names something narrower than a category (a flight, a coffee, a cab ride,
            a named shop), match merchant and raw_text with ILIKE as well, for example:
              WHERE category ILIKE 'Travel' AND (merchant ILIKE '%makemytrip%' OR raw_text ILIKE '%flight%')
            Note: category is NULL until categorization-service has processed the row.
            """;

    private static final Pattern BANNED = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|truncate|create|grant|revoke|copy|call|do|execute)\\b|\\bpg_\\w+|--|/\\*");

    private static final int MAX_ROWS = 500;
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnlyTx;
    private final GeminiQueryClient gemini;
    private final EmbeddingClient embeddings;
    private final ObjectMapper mapper;

    // Transactions retrieved for a semantic answer. Only ever a "best matches" sample, which is
    // why no aggregation is allowed to depend on it.
    @Value("${rag.top-k:5}")
    private int topK;

    // Candidate merchant names shown to the SQL prompt. Unrelated to how many rows get summed.
    @Value("${merchant.top-k:10}")
    private int merchantTopK;

    public SpendQueryService(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                             GeminiQueryClient gemini, EmbeddingClient embeddings, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.jdbc.setMaxRows(MAX_ROWS);
        this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
        this.gemini = gemini;
        this.embeddings = embeddings;
        this.mapper = mapper;
    }

    /**
     * Routes the question to one of two paths: similarity search over embeddings for questions
     * about meaning, text-to-SQL for questions that need arithmetic.
     */
    public Map<String, Object> answer(String question) {
        try {
            String classification = gemini.classifyQuestion(question);
            boolean semantic = isSemantic(classification);
            log.info("Routed question to {} path (classifier said '{}')",
                    semantic ? "rag" : "sql", classification.trim());

            if (semantic) return answerBySimilarity(question);

            Map<String, Object> sqlAnswer = answerBySql(question);

            // The router sends anything phrased as arithmetic ("how much did I pay for my flight
            // booking?") down the SQL path, where the model can only match literal strings. When
            // that finds nothing, the question was probably about meaning after all, so retry it
            // over the embeddings rather than telling the user the transaction does not exist.
            if (((List<?>) sqlAnswer.get("rows")).isEmpty()) {
                log.info("SQL path returned no rows; falling back to similarity search");
                Map<String, Object> ragAnswer = new java.util.LinkedHashMap<>(answerBySimilarity(question));
                ragAnswer.put("method", "rag");
                ragAnswer.put("fallbackFrom", "sql");
                ragAnswer.put("attemptedSql", sqlAnswer.get("sql"));
                return ragAnswer;
            }

            return sqlAnswer;
        } catch (Exception e) {
            log.error("Query failed for question '{}'", question, e);
            return Map.of("question", question, "error", "Could not answer that. Try rephrasing.");
        }
    }

    private Map<String, Object> answerBySimilarity(String question) throws Exception {
        String questionVector = embeddings.embed(question, EmbeddingClient.TASK_QUERY);

        // <=> is cosine distance, so smaller is closer; similarity is reported for readability.
        List<Map<String, Object>> retrieved = readOnlyTx.execute(status -> jdbc.queryForList("""
                SELECT merchant, amount, currency, transaction_type, transaction_date, category,
                       raw_text, 1 - (embedding <=> ?::vector) AS similarity
                  FROM transactions
                 WHERE embedding IS NOT NULL
                 ORDER BY embedding <=> ?::vector
                 LIMIT ?
                """, questionVector, questionVector, topK));

        if (retrieved.isEmpty()) {
            log.warn("No embedded transactions to search; rows ingested before phase 3 have no embedding");
        }

        String answer = gemini.summarizeRetrieved(question, mapper.writeValueAsString(retrieved));

        return Map.of(
                "question", question,
                "method", "rag",
                "answer", answer,
                "retrievedTransactions", retrieved);
    }

    private Map<String, Object> answerBySql(String question) throws Exception {
        List<Map<String, Object>> resolved = resolveMerchants(question);
        List<String> names = resolved.stream().map(m -> (String) m.get("merchant_name")).toList();

        String sql = gemini.generateSql(SCHEMA, names, question);
        validateReadOnly(sql);

        // Read-only transaction: Postgres rejects writes even if the validator is bypassed.
        List<Map<String, Object>> rows = readOnlyTx.execute(status -> jdbc.queryForList(sql));
        String answer = gemini.summarize(question, mapper.writeValueAsString(rows));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", question);
        out.put("method", "sql");
        out.put("resolvedMerchants", resolved);
        out.put("sql", sql);
        out.put("rows", rows);
        out.put("answer", answer);
        return out;
    }

    /**
     * Turns the fuzzy part of an aggregation question into exact merchant names.
     *
     * <p>This is the half that similarity search is actually good at. The LIMIT here caps how
     * many candidate <em>names</em> the SQL prompt sees, not how many transactions get summed -
     * the SQL that follows aggregates over every row those names match. That distinction is the
     * whole point: searching the transactions table instead would cap the arithmetic itself.
     */
    private List<Map<String, Object>> resolveMerchants(String question) throws Exception {
        String questionVector = embeddings.embed(question, EmbeddingClient.TASK_QUERY);

        return readOnlyTx.execute(status -> jdbc.queryForList("""
                SELECT merchant_name, 1 - (embedding <=> ?::vector) AS similarity
                  FROM merchants
                 WHERE embedding IS NOT NULL
                 ORDER BY embedding <=> ?::vector
                 LIMIT ?
                """, questionVector, questionVector, merchantTopK));
    }

    // Anything other than a clear "semantic" falls through to SQL, which is the safer default:
    // it shows its work (the generated SQL and rows) instead of silently retrieving nothing.
    static boolean isSemantic(String classification) {
        return classification != null && classification.trim().toLowerCase().startsWith("semantic");
    }

    // Keywords are matched as whole words so column names like created_at are not rejected.
    static void validateReadOnly(String sql) {
        String s = sql.trim().toLowerCase();
        if (!s.startsWith("select")) throw new IllegalArgumentException("Generated SQL was not a SELECT");
        if (s.contains(";") && !s.endsWith(";")) throw new IllegalArgumentException("Multiple statements rejected");
        Matcher m = BANNED.matcher(s);
        if (m.find()) throw new IllegalArgumentException("Disallowed keyword: " + m.group());
        if (!s.contains("transactions")) throw new IllegalArgumentException("Query must reference transactions table");
    }
}
