package com.spendsense.insights;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InsightsGeneratorTest {

    @Test
    void reportsPercentChangeBothWays() {
        assertEquals(50.0, InsightsGenerator.percentChange(new BigDecimal("200"), new BigDecimal("300")));
        assertEquals(-25.0, InsightsGenerator.percentChange(new BigDecimal("400"), new BigDecimal("300")));
        assertEquals(0.0, InsightsGenerator.percentChange(new BigDecimal("300"), new BigDecimal("300")));
    }

    @Test
    void refusesToInventAChangeWithoutABaseline() {
        // A first-ever period has no previous spend. Reporting 0% or 100% would both be lies,
        // so the field stays null and the UI can say "no comparison available".
        assertNull(InsightsGenerator.percentChange(BigDecimal.ZERO, new BigDecimal("300")));
        assertNull(InsightsGenerator.percentChange(null, new BigDecimal("300")));
    }

    @Test
    void computesCategoryShare() {
        assertEquals(25.0, InsightsGenerator.percentOf(new BigDecimal("250"), new BigDecimal("1000")));
        assertEquals(33.3, InsightsGenerator.percentOf(new BigDecimal("100"), new BigDecimal("300")));
    }

    @Test
    void categoryShareIsNullWhenNothingWasSpent() {
        assertNull(InsightsGenerator.percentOf(new BigDecimal("100"), BigDecimal.ZERO));
    }
}
