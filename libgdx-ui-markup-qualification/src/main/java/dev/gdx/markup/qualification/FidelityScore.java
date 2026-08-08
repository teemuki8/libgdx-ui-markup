package dev.gdx.markup.qualification;

/**
 * Immutable multi-signal visual fidelity measurement of one recreation against its reference.
 *
 * <p>Every component is a finite double in {@code [0, 1]} (1.0 = identical); the cell counts
 * are non-negative and diagnostic. The record enforces these bounds in its compact
 * constructor, so a malformed measurement can never enter a report or gate.
 */
public record FidelityScore(
        double coarseLayout,
        double geometry,
        double color,
        double detail,
        int referenceCells,
        int recreationCells) {

    /** The all-zero score used for entries that were skipped (never measured). */
    public static final FidelityScore ZERO = new FidelityScore(0, 0, 0, 0, 0, 0);

    /** Enforces the finite {@code [0,1]} component bounds and non-negative cell counts. */
    public FidelityScore {
        requireComponent("coarseLayout", coarseLayout);
        requireComponent("geometry", geometry);
        requireComponent("color", color);
        requireComponent("detail", detail);
        if (referenceCells < 0) {
            throw new IllegalArgumentException("referenceCells must be >= 0: " + referenceCells);
        }
        if (recreationCells < 0) {
            throw new IllegalArgumentException(
                    "recreationCells must be >= 0: " + recreationCells);
        }
    }

    /** Returns the component with the given name, or throws for unmeasured components. */
    public double component(FidelityComponent component) {
        return switch (component) {
            case GEOMETRY -> geometry;
            case COLOR -> color;
            case DETAIL -> detail;
            case COARSE_LAYOUT -> coarseLayout;
            case STRUCTURE_DENSITY -> throw new IllegalArgumentException(
                    "STRUCTURE_DENSITY is not a measured score component");
        };
    }

    private static void requireComponent(String name, double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(
                    name + " must be finite and in [0, 1]: " + value);
        }
    }
}
