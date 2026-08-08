package dev.gdx.markup.qualification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/**
 * Pure multi-signal gate policy: calibration separation between positive and deliberate
 * negative observations, explicit overlap failure, staleness detection, per-component verdict
 * gating, and the recreation density band.
 */
final class QualificationPolicyTest {
    @Test
    void calibratePlacesThresholdAtTheMidpointOfTheObservedSeparation() {
        OptionalDouble threshold = QualificationPolicy.calibrate(
                List.of(0.8, 0.9), List.of(0.1, 0.2));
        assertTrue(threshold.isPresent());
        double value = threshold.orElseThrow();
        assertEquals((0.2 + 0.8) / 2, value, 0.0001,
                "midpoint of [maxNegative, minPositive]");
        assertTrue(value > 0.2, "threshold must sit above the maximum negative");
        assertTrue(value < 0.8, "threshold must sit below the minimum positive");
        assertEquals(0.505, QualificationPolicy.calibrate(
                List.of(0.51), List.of(0.5)).orElseThrow(), 0.0001,
                "thin separations still calibrate to their exact midpoint");
    }

    @Test
    void calibrateFailsExplicitlyWhenPositiveAndNegativeRangesOverlap() {
        assertTrue(QualificationPolicy.calibrate(List.of(0.1), List.of(0.2)).isEmpty(),
                "a positive below the negative leaves no safe interval");
        assertTrue(QualificationPolicy.calibrate(
                List.of(0.2), List.of(0.2)).isEmpty(),
                "touching ranges leave no interval");
        assertTrue(QualificationPolicy.calibrate(
                List.of(0.1, 0.5), List.of(0.4, 0.9)).isEmpty(),
                "overlapping multi-observation distributions must fail calibration");
        assertTrue(QualificationPolicy.calibrate(
                List.of(0.05), List.of(0.999)).isEmpty(),
                "an almost-perfect negative and a floor positive cannot be separated");
    }

    @Test
    void calibrateRejectsEmptyOrInvalidObservations() {
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPolicy.calibrate(List.of(), List.of(0.1)));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPolicy.calibrate(List.of(0.1), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPolicy.calibrate(List.of(1.5), List.of(0.1)));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPolicy.calibrate(List.of(0.1), List.of(Double.NaN)));
    }

    @Test
    void thresholdIsClampedFractionOfMeasurement() {
        assertEquals(0.26, QualificationPolicy.threshold(0.4), 0.001);
        assertEquals(0.05, QualificationPolicy.threshold(0.01), 0.001);
        assertEquals(0.6435, QualificationPolicy.threshold(0.99), 0.001);
        assertEquals(0.95, QualificationPolicy.threshold(1.5), 0.001,
                "impossible scores clamp to the ceiling");
    }

    @Test
    void stalenessFlagsMeasurementsBelowTheCommittedThreshold() {
        assertFalse(QualificationPolicy.stale(0.26, 0.4),
                "a measurement above the committed threshold is not stale");
        assertFalse(QualificationPolicy.stale(0.26, 0.30),
                "a measurement still clearing the threshold is not stale");
        assertTrue(QualificationPolicy.stale(0.26, 0.25),
                "a measurement that no longer clears the threshold is stale");
        assertTrue(QualificationPolicy.stale(0.26, 0.0),
                "a vanished measurement is stale");
        assertFalse(QualificationPolicy.stale(0.0, 0.0), "an empty baseline is not stale");
    }

    @Test
    void failedComponentsGatesEachRequiredComponentIndependently() {
        FidelityThresholds thresholds = new FidelityThresholds(0.3, 0.3, 0.3, 0.3);
        assertEquals(List.of(FidelityComponent.GEOMETRY),
                QualificationPolicy.failedComponents(
                        new FidelityScore(0.9, 0.29, 0.9, 0.9, 10, 10), thresholds),
                "a geometry miss fails even with high color/detail");
        assertEquals(List.of(FidelityComponent.COLOR),
                QualificationPolicy.failedComponents(
                        new FidelityScore(0.9, 0.9, 0.29, 0.9, 10, 10), thresholds));
        assertEquals(List.of(FidelityComponent.DETAIL),
                QualificationPolicy.failedComponents(
                        new FidelityScore(0.9, 0.9, 0.9, 0.29, 10, 10), thresholds));
        assertEquals(List.of(),
                QualificationPolicy.failedComponents(
                        new FidelityScore(0.0, 0.3, 0.3, 0.3, 10, 10), thresholds),
                "the coarse diagnostic baseline never gates");
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
