package dev.gdx.markup.qualification;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Pure gate and calibration policy for unattended multi-signal qualification runs.
 *
 * <p>Calibration treats each fidelity component independently: the committed threshold for a
 * component sits strictly below the minimum positive observation (a faithful recreation) by a
 * documented margin and strictly above the maximum deliberate-negative observation (a broken
 * recreation), so the gate rejects every calibrated negative while accepting the measured
 * positives. Every gated component also has an immutable absolute floor (geometry 0.12, color
 * 0.20, detail 0.12) that calibration may never undercut: the effective threshold is the
 * larger of the component floor and the calibrated midpoint, and a positive that scores below
 * its floor cannot be calibrated at all — a sub-floor recreation can never be committed as a
 * passing baseline. When the positive and negative ranges overlap (no safe interval exists)
 * the calibration fails explicitly instead of inventing a threshold. Committed thresholds that
 * drift from the measurement-implied (floor-aware) value are reported stale so the maintainer
 * re-calibrates. The recreation density band (from the coarse structured-cell counts) rejects
 * recreations that flood or empty the screen, which could otherwise game a region-overlap
 * score.
 */
public final class QualificationPolicy {
    /** Fraction of the measured score a diagnostic threshold keeps as regression headroom. */
    public static final double THRESHOLD_FRACTION = 0.65;
    /** Absolute floor and ceiling for diagnostic thresholds. */
    public static final double THRESHOLD_FLOOR = 0.05;
    /** Absolute floor and ceiling for diagnostic thresholds. */
    public static final double THRESHOLD_CEILING = 0.95;
    /** Relative drift from a committed threshold that flags staleness. */
    public static final double STALENESS_TOLERANCE = 0.10;
    /** Recreation structured cells must stay within this band of the reference's. */
    public static final double MIN_DENSITY_RATIO = 0.2;
    /** Recreation structured cells must stay within this band of the reference's. */
    public static final double MAX_DENSITY_RATIO = 3.0;
    /**
     * Absolute minimum committed threshold for {@link FidelityComponent#GEOMETRY}. Calibration
     * may never produce a geometry gate below this value, and a geometry positive below it
     * cannot be calibrated at all.
     */
    public static final double GEOMETRY_FLOOR = 0.12;
    /**
     * Absolute minimum committed threshold for {@link FidelityComponent#COLOR}. Calibration
     * may never produce a color gate below this value, and a color positive below it cannot
     * be calibrated at all.
     */
    public static final double COLOR_FLOOR = 0.20;
    /**
     * Absolute minimum committed threshold for {@link FidelityComponent#DETAIL}. Calibration
     * may never produce a detail gate below this value, and a detail positive below it cannot
     * be calibrated at all.
     */
    public static final double DETAIL_FLOOR = 0.12;

    private QualificationPolicy() {
    }

    /**
     * Returns the immutable absolute floor for one gated fidelity component: the lowest
     * committed threshold {@link #calibrate} may produce for it. Diagnostic components
     * ({@link FidelityComponent#COARSE_LAYOUT}, {@link FidelityComponent#STRUCTURE_DENSITY})
     * never gate and therefore have no floor.
     *
     * @param component  the gated fidelity component
     * @return the component's absolute floor
     * @throws IllegalArgumentException for components that never gate
     */
    public static double floor(FidelityComponent component) {
        return switch (component) {
            case GEOMETRY -> GEOMETRY_FLOOR;
            case COLOR -> COLOR_FLOOR;
            case DETAIL -> DETAIL_FLOOR;
            case COARSE_LAYOUT, STRUCTURE_DENSITY -> throw new IllegalArgumentException(
                    component + " is not a gated fidelity component and has no absolute floor");
        };
    }

    /**
     * Calibrates one component threshold from positive and deliberate-negative observations.
     *
     * <p>The threshold is the midpoint of the observed separation interval
     * {@code [maxNegative, minPositive]}: it sits strictly below the minimum positive score
     * and strictly above the maximum deliberate-negative score by exactly half the measured
     * separation, so the committed gate rejects every calibrated negative while accepting the
     * measured positives without any hand-picked margin. The result is lifted to the
     * component's absolute floor ({@link #floor(FidelityComponent)}) when the midpoint would
     * undercut it, so calibration can never lower required fidelity below the floor. The
     * result is empty when the positive ranges overlap or touch — no threshold can separate
     * them, so the calibration must fail loudly instead of committing a meaningless gate —
     * and when the weakest positive itself scores below the component floor, because a
     * threshold at or above the floor could never be cleared by a sub-floor recreation
     * (calibration cannot mint an impossible or empty passing gate).
     *
     * @param component  the fidelity component being calibrated
     * @param positives  measured component scores of faithful recreations (non-empty)
     * @param negatives  measured component scores of deliberate negatives (non-empty)
     * @return the calibrated threshold, or empty when no safe interval exists
     */
    public static OptionalDouble calibrate(FidelityComponent component,
            List<Double> positives, List<Double> negatives) {
        requireObservations("positives", positives);
        requireObservations("negatives", negatives);
        double floor = floor(component);
        double minPositive = positives.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        if (minPositive < floor) {
            return OptionalDouble.empty();
        }
        double maxNegative = negatives.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        if (maxNegative >= minPositive) {
            return OptionalDouble.empty();
        }
        double midpoint = (maxNegative + minPositive) / 2.0;
        return OptionalDouble.of(Math.max(floor, midpoint));
    }

    /** Returns the diagnostic threshold for one measured score, clamped to the policy band. */
    public static double threshold(double measured) {
        double candidate = THRESHOLD_FRACTION * measured;
        return Math.max(THRESHOLD_FLOOR, Math.min(THRESHOLD_CEILING, candidate));
    }

    /**
     * Returns whether the committed threshold is stale: the current calibration-implied
     * threshold (the floor-aware midpoint of the current positive and its component-relevant
     * deliberate negatives, i.e. exactly what {@link #calibrate} would commit today) has
     * drifted from the committed value by more than {@value #STALENESS_TOLERANCE} relative to
     * the committed threshold. This detects both upward scorer drift (the metric became more
     * lenient, so the implied threshold rose) and downward drift (the recreation or scorer
     * regressed, so the implied threshold fell), in either case signalling that the corpus
     * baselines must be re-calibrated. Because the implied threshold is floor-aware, a
     * committed threshold at the floor is not falsely flagged when the raw midpoint dips below
     * it, and a measured positive below the floor — which can imply no threshold at all — is
     * stale by definition. When the current positive no longer clears the negatives (no safe
     * interval exists) the baselines are stale by definition. A committed threshold of zero
     * (an uncalibrated placeholder) is stale whenever anything is measured.
     */
    public static boolean stale(FidelityComponent component, double committedThreshold,
            double measured, List<Double> negatives) {
        if (committedThreshold <= 0) {
            return measured > 0;
        }
        OptionalDouble implied = calibrate(component, List.of(measured), negatives);
        if (implied.isEmpty()) {
            return true;
        }
        double expected = implied.orElseThrow();
        return Math.abs(expected - committedThreshold) / committedThreshold
                > STALENESS_TOLERANCE;
    }

    /** Returns whether the recreation's structured-cell count is in the density band. */
    public static boolean densityInBand(int referenceCells, int recreationCells) {
        if (referenceCells <= 0) {
            return recreationCells == 0;
        }
        double ratio = (double) recreationCells / referenceCells;
        return ratio >= MIN_DENSITY_RATIO && ratio <= MAX_DENSITY_RATIO;
    }

    /**
     * Returns the required components whose measured score is below its committed threshold.
     * The verdict fails exactly when this list is non-empty; the coarse-layout baseline is
     * diagnostic and never appears here.
     */
    public static List<FidelityComponent> failedComponents(
            FidelityScore score, FidelityThresholds thresholds) {
        List<FidelityComponent> failed = new ArrayList<>(FidelityComponent.REQUIRED.size());
        for (FidelityComponent component : FidelityComponent.REQUIRED) {
            if (score.component(component) < thresholds.component(component)) {
                failed.add(component);
            }
        }
        return List.copyOf(failed);
    }

    private static void requireObservations(String name, List<Double> observations) {
        if (observations == null || observations.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        for (Double observation : observations) {
            if (observation == null || !Double.isFinite(observation)
                    || observation < 0 || observation > 1) {
                throw new IllegalArgumentException(
                        name + " values must be finite and in [0, 1]: " + observations);
            }
        }
    }
}
