package dev.gdx.markup.qualification;

import java.util.Optional;

/**
 * Immutable per-entry pass thresholds for the required fidelity components.
 *
 * <p>{@code geometry}, {@code color}, and {@code detail} gate the verdict; the optional
 * {@code coarseLayout} baseline is a diagnostic reference only and never gates. Thresholds are
 * calibrated from measured positives and deliberate negatives and committed to the corpus
 * manifest; strict CI never recalibrates them.
 */
public record FidelityThresholds(
        double geometry,
        double color,
        double detail,
        Double coarseLayout) {

    /** Enforces the finite {@code [0,1]} bounds on every threshold. */
    public FidelityThresholds {
        requireThreshold("geometry", geometry);
        requireThreshold("color", color);
        requireThreshold("detail", detail);
        if (coarseLayout != null) {
            requireThreshold("coarseLayout", coarseLayout);
        }
    }

    /** Returns the optional diagnostic coarse-layout baseline. */
    public Optional<Double> coarseBaseline() {
        return Optional.ofNullable(coarseLayout);
    }

    /** Returns the threshold for the given required component. */
    public double component(FidelityComponent component) {
        return switch (component) {
            case GEOMETRY -> geometry;
            case COLOR -> color;
            case DETAIL -> detail;
            case COARSE_LAYOUT -> coarseLayout == null
                    ? Double.NaN
                    : coarseLayout;
            case STRUCTURE_DENSITY -> throw new IllegalArgumentException(
                    "STRUCTURE_DENSITY is not a thresholded component");
        };
    }

    private static void requireThreshold(String name, double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(
                    name + " threshold must be finite and in [0, 1]: " + value);
        }
    }
}
