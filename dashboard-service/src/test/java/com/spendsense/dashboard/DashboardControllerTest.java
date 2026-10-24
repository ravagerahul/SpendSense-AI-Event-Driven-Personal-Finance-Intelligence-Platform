package com.spendsense.dashboard;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DashboardControllerTest {

    @Test
    void computesCategoryShare() {
        assertEquals(25.0, DashboardController.percentOf(new BigDecimal("250"), new BigDecimal("1000")));
        assertEquals(33.3, DashboardController.percentOf(new BigDecimal("100"), new BigDecimal("300")));
    }

    @Test
    void shareOfNothingIsNullNotZero() {
        // A month with no spending has no category shares; 0% would imply a measured result.
        assertNull(DashboardController.percentOf(new BigDecimal("100"), BigDecimal.ZERO));
        assertNull(DashboardController.percentOf(new BigDecimal("100"), null));
    }
}
