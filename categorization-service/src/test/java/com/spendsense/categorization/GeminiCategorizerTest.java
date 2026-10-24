package com.spendsense.categorization;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiCategorizerTest {

    @Test
    void noExamplesAddsNothingToPrompt() {
        assertEquals("", GeminiCategorizer.fewShot(List.of()));
    }

    @Test
    void correctionsBecomeFewShotLines() {
        String block = GeminiCategorizer.fewShot(List.of(
                Map.of("merchant", "Priya Sharma", "corrected_category", "Transfers")));
        assertTrue(block.contains("Priya Sharma"));
        assertTrue(block.contains("Transfers"));
    }

    @Test
    void manualCategoryIsInTheAllowedSet() {
        assertTrue(CategorizationDecider.CATEGORIES.contains(CategorizationDecider.MANUAL));
    }
}
