package com.spendsense.anomaly;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnomalyDetectorTest {

    @Test
    void gradesSeverityByRatio() {
        assertEquals(Anomaly.HIGH, AnomalyDetector.severityForRatio(5.1));
        assertEquals(Anomaly.HIGH, AnomalyDetector.severityForRatio(12.0));
        assertEquals(Anomaly.MEDIUM, AnomalyDetector.severityForRatio(3.5));
    }

    @Test
    void doesNotFireAtOrBelowThreeTimesAverage() {
        // Exactly 3x is not "> 3x", so a transaction at the boundary is not an anomaly.
        assertNull(AnomalyDetector.severityForRatio(3.0));
        assertNull(AnomalyDetector.severityForRatio(1.0));
        assertNull(AnomalyDetector.severityForRatio(0.2));
    }

    @Test
    void fiveTimesAverageIsStillMedium() {
        // The table reads HIGH if >5x, so 5.0 itself stays MEDIUM.
        assertEquals(Anomaly.MEDIUM, AnomalyDetector.severityForRatio(5.0));
    }
}
