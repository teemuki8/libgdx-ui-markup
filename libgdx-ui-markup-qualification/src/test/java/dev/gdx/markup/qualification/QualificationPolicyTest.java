package dev.gdx.markup.qualification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure gate policy: calibrated thresholds, staleness detection, and density bands. */
final class QualificationPolicyTest {
    @Test
    void thresholdIsClampedFractionOfMeasurement() {
        assertEquals(0.26, QualificationPolicy.threshold(0.4), 0.001);
        assertEquals(0.05, QualificationPolicy.threshold(0.01), 0.001);
        assertEquals(0.6435, QualificationPolicy.threshold(0.99), 0.001);
        assertEquals(0.95, QualificationPolicy.threshold(1.5), 0.001,
                "impossible Dice values clamp to the ceiling");
    }

    @Test
    void stalenessIsRelativeToTheCommittedThreshold() {
        assertFalse(QualificationPolicy.stale(0.26, 0.4),
                "a 1% drift from the calibrated threshold is not stale");
        assertTrue(QualificationPolicy.stale(0.26, 0.55),
                "a measurement far above the threshold requires re-calibration");
        assertTrue(QualificationPolicy.stale(0.26, 0.30),
                "a measurement far below the threshold requires re-calibration");
    }

    @Test
    void densityBandRejectsBothEmptyAndScreenFillingRecreations() {
        assertTrue(QualificationPolicy.densityInBand(500, 250));
        assertTrue(QualificationPolicy.densityInBand(500, 1400));
        assertFalse(QualificationPolicy.densityInBand(500, 50),
                "a recreation with almost no structure is not a faithful layout");
        assertFalse(QualificationPolicy.densityInBand(500, 2000),
                "a recreation that floods the screen games region overlap");
    }
}
