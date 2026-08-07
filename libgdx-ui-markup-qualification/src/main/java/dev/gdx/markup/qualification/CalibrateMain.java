package dev.gdx.markup.qualification;

import java.nio.file.Path;

/**
 * Standalone entry point for the {@code calibrateQualification} task: measures every corpus
 * recreation and rewrites the manifest thresholds with no human intervention.
 */
public final class CalibrateMain {
    private CalibrateMain() {
    }

    /** Runs calibration; requires the four qualification system properties. */
    public static void main(String[] args) {
        try (QualificationRunner runner = new QualificationRunner(
                property("markup.qualification.corpus"),
                property("markup.qualification.cache"),
                property("markup.preview.distribution"),
                property("markup.qualification.output"))) {
            runner.calibrate();
        }
    }

    private static Path property(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing system property -D" + name);
        }
        return Path.of(value);
    }
}
