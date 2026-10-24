package com.spendsense.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpendQueryServiceTest {

    @Test
    void allowsPlainSelect() {
        assertDoesNotThrow(() -> SpendQueryService.validateReadOnly(
                "SELECT category, SUM(amount) FROM transactions GROUP BY category"));
    }

    @Test
    void allowsCreatedAtColumn() {
        assertDoesNotThrow(() -> SpendQueryService.validateReadOnly(
                "SELECT * FROM transactions ORDER BY created_at DESC LIMIT 5;"));
    }

    @Test
    void rejectsNonSelect() {
        assertThrows(IllegalArgumentException.class,
                () -> SpendQueryService.validateReadOnly("DELETE FROM transactions"));
    }

    @Test
    void rejectsWriteKeywordInsideSelect() {
        assertThrows(IllegalArgumentException.class, () -> SpendQueryService.validateReadOnly(
                "SELECT * FROM transactions; DROP TABLE transactions"));
        assertThrows(IllegalArgumentException.class, () -> SpendQueryService.validateReadOnly(
                "SELECT * FROM transactions WHERE merchant = 'x' AND 1 = (SELECT 1) -- drop"));
    }

    @Test
    void rejectsSystemFunctions() {
        assertThrows(IllegalArgumentException.class, () -> SpendQueryService.validateReadOnly(
                "SELECT pg_sleep(30) FROM transactions"));
    }

    @Test
    void requiresTransactionsTable() {
        assertThrows(IllegalArgumentException.class,
                () -> SpendQueryService.validateReadOnly("SELECT 1"));
    }

    @Test
    void routesSemanticAnswersToRag() {
        assertTrue(SpendQueryService.isSemantic("semantic"));
        assertTrue(SpendQueryService.isSemantic("  Semantic\n"));
    }

    @Test
    void routesEverythingElseToSql() {
        assertFalse(SpendQueryService.isSemantic("aggregation"));
        assertFalse(SpendQueryService.isSemantic(""));
        assertFalse(SpendQueryService.isSemantic(null));
        assertFalse(SpendQueryService.isSemantic("I think this is a semantic question"));
    }
}
