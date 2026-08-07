package dev.gdx.markup.qualification;

/**
 * Pure gate policy for unattended qualification runs: calibrated thresholds, staleness
 * detection, and the recreation density band.
 *
 * <p>Thresholds are derived from measurements (65% of the measured dilated Dice, clamped), so
 * no human tuning is needed; a committed threshold that drifted more than the tolerance from
 * what the current measurement implies is reported stale so the maintainer re-calibrates.
 * The density band rejects recreations with almost no structure or recreations that flood the
 * whole screen, either of which could game a region-overlap score.
 */
public final class QualificationPolicy {
    /** Fraction of the measured score a calibrated threshold keeps as regression headroom. */
    public static final double THRESHOLD_FRACTION = 0.65;
    /** Absolute floor and ceiling for calibrated thresholds. */
    public static final double THRESHOLD_FLOOR = 0.05;
    /** Absolute floor and ceiling for calibrated thresholds. */
    public static final double THRESHOLD_CEILING = 0.95;
    /** Relative drift from a committed threshold that flags staleness. */
    public static final double STALENESS_TOLERANCE = 0.10;
    /** Recreation structured cells must stay within this band of the reference's. */
    public static final double MIN_DENSITY_RATIO = 0.2;
    /** Recreation structured cells must stay within this band of the reference's. */
    public static final double MAX_DENSITY_RATIO = 3.0;

    private QualificationPolicy() {
    }

    /** Returns the calibrated threshold for one measured score, clamped to the policy band. */
    public static double threshold(double measuredDice) {
        double candidate = THRESHOLD_FRACTION * measuredDice;
        return Math.max(THRESHOLD_FLOOR, Math.min(THRESHOLD_CEILING, candidate));
    }

    /**
     * Returns whether the committed threshold no longer matches the current measurement within
     * the staleness tolerance, meaning the corpus baselines should be re-calibrated.
     */
    public static boolean stale(double committedThreshold, double measuredDice) {
        double implied = threshold(measuredDice);
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
}
