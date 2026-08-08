package dev.gdx.markup.qualification;

import java.util.List;

/**
 * The independent visual fidelity signals measured for every corpus entry.
 *
 * <p>{@link #GEOMETRY}, {@link #COLOR}, and {@link #DETAIL} are required pass gates: the
 * qualification verdict fails when any one of them is below its committed threshold, so a high
 * average can never mask a single broken dimension. {@link #COARSE_LAYOUT} is the legacy
 * dilated-region overlap score, retained purely as a diagnostic baseline and never a gate.
 */
public enum FidelityComponent {
    /** Positional fidelity: thresholded Sobel-gradient cell mask Dice. */
    GEOMETRY,
    /** Palette fidelity: quantized RGB histogram intersection. */
    COLOR,
    /** Typography/detail fidelity: grid-local high-frequency magnitude and orientation. */
    DETAIL,
    /** Diagnostic-only dilated-region overlap (never gates). */
    COARSE_LAYOUT;

    /** The components that gate the qualification verdict. */
    public static final List<FidelityComponent> REQUIRED = List.of(GEOMETRY, COLOR, DETAIL);

    private static final java.util.List<FidelityComponent> ALL = List.of(values());
}
