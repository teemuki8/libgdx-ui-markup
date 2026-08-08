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
 * positives. When the positive and negative ranges overlap (no safe interval exists) the
 * calibration fails explicitly instead of inventing a threshold. Committed thresholds that
 * drift from the measurement-implied value are reported stale so the maintainer re-calibrates.
 * The recreation density band (from the coarse structured-cell counts) rejects recreations
 * that flood or empty the screen, which could otherwise game a region-overlap score.
 */
public final class QualificationPolicy {
    /** Minimum separation a calibrated threshold keeps from the negative ceiling. */
    public static final double SEPARATION_MARGIN = 0.05;
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

    private QualificationPolicy() {
    }

    /**
     * Calibrates one component threshold from positive and deliberate-negative observations.
     *
     * <p>The threshold is the midpoint of the safe interval
     * {@code [maxNegative + SEPARATION_MARGIN, minPositive - SEPARATION_MARGIN]} and is empty
     * when that interval is invalid, i.e. when the ranges overlap or touch. An empty result
     * means no threshold can separate the observed positives from the negatives, so the
     * calibration must fail loudly rather than commit a meaningless gate.
     *
     * @param positives  measured component scores of faithful recreations (non-empty)
     * @param negatives  measured component scores of deliberate negatives (non-empty)
     * @return the calibrated threshold, or empty when no safe interval exists
     */
    public static OptionalDouble calibrate(List<Double> positives, List<Double> negatives) {
        requireObservations("positives", positives);
        requireObservations("negatives", negatives);
        double minPositive = positives.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double maxNegative = negatives.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double low = maxNegative + SEPARATION_MARGIN;
        double high = minPositive - SEPARATION_MARGIN;
        if (low > high) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of((low + high) / 2.0);
    }

    /** Returns the diagnostic threshold for one measured score, clamped to the policy band. */
    public static double threshold(double measured) {
        double candidate = THRESHOLD_FRACTION * measured;
        return Math.max(THRESHOLD_FLOOR, Math.min(THRESHOLD_CEILING, candidate));
    }

    /**
     * Returns whether the committed threshold no longer matches the current measurement within
     * the staleness tolerance, meaning the corpus baselines should be re-calibrated.
     */
    public static boolean stale(double committedThreshold, double measured) {
        if (committedThreshold <= 0) {
            return measured > 0;
        }
        double implied = threshold(measured);
        return Math.abs(implied - committedThreshold) / committedThreshold > STALENESS_TOLERANCE;
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
