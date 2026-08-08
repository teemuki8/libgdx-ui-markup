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
 * negative observations, explicit overlap failure, immutable per-component absolute floors
 * that calibration may never undercut, floor-aware bidirectional staleness, per-component
 * verdict gating, and the recreation density band.
 */
final class QualificationPolicyTest {
    @Test
    void calibratePlacesThresholdAtTheMidpointOfTheObservedSeparation() {
        OptionalDouble threshold = QualificationPolicy.calibrate(FidelityComponent.GEOMETRY,
                List.of(0.8, 0.9), List.of(0.1, 0.2));
        assertTrue(threshold.isPresent());
        double value = threshold.orElseThrow();
        assertEquals((0.2 + 0.8) / 2, value, 0.0001,
                "midpoint of [maxNegative, minPositive]");
        assertTrue(value > 0.2, "threshold must sit above the maximum negative");
        assertTrue(value < 0.8, "threshold must sit below the minimum positive");
        assertEquals(0.505, QualificationPolicy.calibrate(FidelityComponent.GEOMETRY,
                List.of(0.51), List.of(0.5)).orElseThrow(), 0.0001,
                "thin separations still calibrate to their exact midpoint");
    }

    @Test
    void calibrateFailsExplicitlyWhenPositiveAndNegativeRangesOverlap() {
        assertTrue(QualificationPolicy.calibrate(FidelityComponent.GEOMETRY,
                        List.of(0.3), List.of(0.4)).isEmpty(),
                "a positive below the negative leaves no safe interval");
        assertTrue(QualificationPolicy.calibrate(FidelityComponent.GEOMETRY,
                        List.of(0.2), List.of(0.2)).isEmpty(),
                "touching ranges leave no interval");
        assertTrue(QualificationPolicy.calibrate(FidelityComponent.COLOR,
                        List.of(0.3, 0.5), List.of(0.4, 0.9)).isEmpty(),
                "overlapping multi-observation distributions must fail calibration");
        assertTrue(QualificationPolicy.calibrate(FidelityComponent.DETAIL,
                        List.of(0.5), List.of(0.999)).isEmpty(),
                "an almost-perfect negative and a modest positive cannot be separated");
    }

    @Test
    void calibrateRejectsEmptyOrInvalidObservations() {
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPolicy.calibrate(FidelityComponent.GEOMETRY,
                        List.of(), List.of(0.1)));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPolicy.calibrate(FidelityComponent.GEOMETRY,
                        List.of(0.1), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPolicy.calibrate(FidelityComponent.GEOMETRY,
                        List.of(1.5), List.of(0.1)));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPolicy.calibrate(FidelityComponent.GEOMETRY,
                        List.of(0.1), List.of(Double.NaN)));
    }

    @Test
    void componentFloorsAreExplicitImmutablePerComponentConstants() {
        assertEquals(0.12, QualificationPolicy.GEOMETRY_FLOOR, 0.0);
        assertEquals(0.20, QualificationPolicy.COLOR_FLOOR, 0.0);
        assertEquals(0.12, QualificationPolicy.DETAIL_FLOOR, 0.0);
        assertEquals(QualificationPolicy.GEOMETRY_FLOOR,
                QualificationPolicy.floor(FidelityComponent.GEOMETRY), 0.0);
        assertEquals(QualificationPolicy.COLOR_FLOOR,
                QualificationPolicy.floor(FidelityComponent.COLOR), 0.0);
        assertEquals(QualificationPolicy.DETAIL_FLOOR,
                QualificationPolicy.floor(FidelityComponent.DETAIL), 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPolicy.floor(FidelityComponent.COARSE_LAYOUT),
                "the diagnostic coarse baseline never gates and has no floor");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationPolicy.floor(FidelityComponent.STRUCTURE_DENSITY),
                "the density invariant never gates and has no floor");
    }

    @Test
    void calibrateLiftsTheMidpointToTheComponentsAbsoluteFloor() {
        // geometry floor 0.12: raw midpoint 0.08 would undercut the floor, so it is lifted
        assertEquals(0.12, QualificationPolicy.calibrate(FidelityComponent.GEOMETRY,
                List.of(0.16), List.of(0.0)).orElseThrow(), 0.0001,
                "a geometry midpoint below 0.12 must lift to the floor");
        // color floor 0.20: raw midpoint 0.15 lifts to the floor
        assertEquals(0.20, QualificationPolicy.calibrate(FidelityComponent.COLOR,
                List.of(0.30), List.of(0.0)).orElseThrow(), 0.0001,
                "a color midpoint below 0.20 must lift to the floor");
        // detail floor 0.12: raw midpoint 0.075 lifts to the floor
        assertEquals(0.12, QualificationPolicy.calibrate(FidelityComponent.DETAIL,
                List.of(0.15), List.of(0.0)).orElseThrow(), 0.0001,
                "a detail midpoint below 0.12 must lift to the floor");
        // the lifted threshold still sits strictly below the measured positive
        assertTrue(QualificationPolicy.calibrate(FidelityComponent.COLOR,
                        List.of(0.30), List.of(0.0)).orElseThrow() < 0.30,
                "the floor must never mint a threshold the measured positive cannot clear");
    }

    @Test
    void calibrateRefusesAPositiveBelowItsComponentFloor() {
        assertTrue(QualificationPolicy.calibrate(FidelityComponent.GEOMETRY,
                        List.of(0.119), List.of(0.0)).isEmpty(),
                "a geometry positive below 0.12 cannot be calibrated, even against a zero "
                        + "negative");
        assertTrue(QualificationPolicy.calibrate(FidelityComponent.GEOMETRY,
                        List.of(0.11), List.of(0.001)).isEmpty(),
                "a sub-floor positive cannot be calibrated even when cleanly separated");
        assertTrue(QualificationPolicy.calibrate(FidelityComponent.COLOR,
                        List.of(0.19), List.of(0.0)).isEmpty(),
                "a color positive below 0.20 cannot be calibrated");
        assertTrue(QualificationPolicy.calibrate(FidelityComponent.DETAIL,
                        List.of(0.11), List.of(0.0)).isEmpty(),
                "a detail positive below 0.12 cannot be calibrated");
        assertTrue(QualificationPolicy.calibrate(FidelityComponent.COLOR,
                        List.of(0.19, 0.9), List.of(0.0)).isEmpty(),
                "the weakest positive governs: one sub-floor observation refuses calibration");
    }

    @Test
    void calibrateAtTheFloorBoundaryCommitsTheFloorItself() {
        // positive exactly on the floor with separation from the negatives: threshold equals
        // the floor, the strongest gate the measured recreation can still clear
        assertEquals(0.12, QualificationPolicy.calibrate(FidelityComponent.GEOMETRY,
                List.of(0.12), List.of(0.0)).orElseThrow(), 0.0001,
                "a positive exactly on the geometry floor commits the floor itself");
        assertEquals(0.20, QualificationPolicy.calibrate(FidelityComponent.COLOR,
                List.of(0.20), List.of(0.10)).orElseThrow(), 0.0001,
                "a positive exactly on the color floor commits the floor itself");
        assertEquals(0.12, QualificationPolicy.calibrate(FidelityComponent.DETAIL,
                List.of(0.14), List.of(0.10)).orElseThrow(), 0.0001,
                "a detail midpoint exactly on the floor commits the floor itself");
        // the boundary is exact: one ulp below the floor refuses calibration
        assertTrue(QualificationPolicy.calibrate(FidelityComponent.GEOMETRY,
                        List.of(Math.nextDown(0.12)), List.of(0.0)).isEmpty(),
                "one ulp below the geometry floor must refuse calibration");
        // at the floor, overlap still refuses: no threshold can separate touching ranges
        assertTrue(QualificationPolicy.calibrate(FidelityComponent.DETAIL,
                        List.of(0.12), List.of(0.12)).isEmpty(),
                "touching ranges at the floor still leave no safe interval");
    }

    @Test
    void selfTransformCalibrationCannotPassALowQualityPositive() {
        // A recreation so poor that its own deliberate transforms cannot be told apart from
        // it (a blank screen, for example: flip/translate/hue/blur/scale are all identical)
        // must never calibrate any component into a passing gate.
        for (FidelityComponent component : FidelityComponent.REQUIRED) {
            assertTrue(QualificationPolicy.calibrate(component,
                            List.of(0.0), List.of(0.0)).isEmpty(),
                    component + ": a zero positive with zero self-transform negatives must "
                            + "never calibrate");
            assertTrue(QualificationPolicy.calibrate(component,
                            List.of(0.05), List.of(0.05)).isEmpty(),
                    component + ": an indistinguishable self-transform negative must never "
                            + "calibrate");
            assertTrue(QualificationPolicy.calibrate(component,
                            List.of(0.10), List.of(0.08, 0.09)).isEmpty(),
                    component + ": a low-quality positive whose self-transforms cluster "
                            + "below it must never calibrate into a passing gate");
            assertTrue(QualificationPolicy.calibrate(component,
                            List.of(0.01), List.of(0.0)).isEmpty(),
                    component + ": a sub-floor positive must never calibrate, even against "
                            + "perfect zero negatives");
        }
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
    void stalenessComparesCommittedAgainstCalibrationImpliedThreshold() {
        // committed = midpoint(0.2, 0.8) = 0.5; current measurement 0.8 implies 0.5 -> no drift
        assertFalse(QualificationPolicy.stale(FidelityComponent.GEOMETRY,
                0.5, 0.8, List.of(0.2)),
                "an unchanged implied threshold is not stale");
        // upward drift: measured 0.95 implies 0.575, a 15% rise above committed 0.5
        assertTrue(QualificationPolicy.stale(FidelityComponent.GEOMETRY,
                0.5, 0.95, List.of(0.2)),
                "upward scorer drift beyond 10% must be stale");
        // downward drift: measured 0.3 implies 0.25, a 50% fall below committed 0.5
        assertTrue(QualificationPolicy.stale(FidelityComponent.GEOMETRY,
                0.5, 0.3, List.of(0.2)),
                "downward scorer drift beyond 10% must be stale");
        // just below the boundary: measured 0.89 implies 0.545, a 9% rise -> not stale
        assertFalse(QualificationPolicy.stale(FidelityComponent.GEOMETRY,
                0.5, 0.89, List.of(0.2)),
                "9% drift is inside the 10% tolerance");
        // just past the boundary: measured 0.91 implies 0.555, an 11% rise
        assertTrue(QualificationPolicy.stale(FidelityComponent.GEOMETRY,
                0.5, 0.91, List.of(0.2)),
                "11% drift must be stale");
        // no safe interval: the current positive no longer clears the negatives
        assertTrue(QualificationPolicy.stale(FidelityComponent.GEOMETRY,
                0.5, 0.1, List.of(0.2)),
                "an overlapping positive/negative range is stale by definition");
        assertFalse(QualificationPolicy.stale(FidelityComponent.GEOMETRY,
                0.0, 0.0, List.of(0.1)),
                "an empty baseline is not stale");
    }

    @Test
    void stalenessIsFloorAwareAndBidirectional() {
        // A committed threshold exactly on the floor is NOT stale when the raw implied
        // midpoint would fall below the floor: the floor holds the implied threshold up at
        // the committed value (raw midpoint 0.105 would otherwise read as a 12.5% drift).
        assertFalse(QualificationPolicy.stale(FidelityComponent.GEOMETRY,
                        0.12, 0.16, List.of(0.05)),
                "geometry: the implied midpoint 0.105 is lifted to the floor 0.12, matching "
                        + "the committed floor threshold");
        // Upward drift beyond tolerance is stale even from a committed floor threshold:
        // measured 0.30 implies 0.175, a 46% rise above committed 0.12.
        assertTrue(QualificationPolicy.stale(FidelityComponent.GEOMETRY,
                        0.12, 0.30, List.of(0.05)),
                "geometry: upward drift beyond 10% must be stale from a floor baseline");
        // Downward drift: measured 0.16 implies a threshold that falls from committed 0.15
        // to the floor 0.12, a 20% drop.
        assertTrue(QualificationPolicy.stale(FidelityComponent.GEOMETRY,
                        0.15, 0.16, List.of(0.02)),
                "geometry: a fall toward the floor beyond 10% must be stale");
        // A measured positive below the floor cannot imply any threshold at all -> stale.
        assertTrue(QualificationPolicy.stale(FidelityComponent.GEOMETRY,
                        0.15, 0.11, List.of(0.05)),
                "geometry: a sub-floor measurement cannot imply the committed threshold");
        // Color floor 0.20: committed 0.22 vs floor-lifted implied 0.20 is a 9.1% fall.
        assertFalse(QualificationPolicy.stale(FidelityComponent.COLOR,
                        0.22, 0.24, List.of(0.0)),
                "color: the floor-lifted implied 0.20 is inside the 10% tolerance of 0.22");
        // committed 0.23 vs floor-lifted implied 0.20 is a 13.0% fall -> stale.
        assertTrue(QualificationPolicy.stale(FidelityComponent.COLOR,
                        0.23, 0.24, List.of(0.0)),
                "color: the floor-lifted implied 0.20 is a 13% fall from committed 0.23");
        // An unchanged implied threshold exactly on the floor is not stale.
        assertFalse(QualificationPolicy.stale(FidelityComponent.DETAIL,
                        0.12, 0.14, List.of(0.10)),
                "detail: the implied midpoint exactly on the floor matches the committed "
                        + "floor threshold");
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
