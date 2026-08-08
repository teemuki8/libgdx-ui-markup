package dev.gdx.markup.qualification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.gdx.markup.qualification.QualificationReport.EntryResult;
import dev.gdx.markup.qualification.QualificationReport.Verdict;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Marks up recreations of well-made game UIs and measures their multi-signal visual fidelity
 * (geometry, color, detail) against the real reference screenshots, fetched at test time from
 * their published sources (never redistributed). Entries whose reference cannot be fetched are
 * reported skipped rather than failing; at least one entry must be measured so a silent no-op
 * cannot pass. Every required component gates independently: a single dimension below its
 * committed threshold fails the entry no matter how high the others score.
 */
final class QualificationTest {
    @Test
    @Timeout(300)
    void recreationsClearEveryFidelityComponentIndependently() throws Exception {
        try (QualificationRunner runner = new QualificationRunner(
                property("markup.qualification.corpus"),
                property("markup.preview.distribution"),
                property("markup.qualification.output"))) {
            QualificationReport report = runner.run();
            assertTrue(report.scored() >= 1,
                    "at least one corpus entry must be fetched and scored (network or cache)");
            if (strict()) {
                for (EntryResult result : report.results()) {
                    if (result.verdict() == Verdict.SKIPPED_REFERENCE
                            || result.verdict() == Verdict.SKIPPED_RENDER) {
                        fail("strict qualification: entry " + result.id()
                                + " was skipped (" + result.verdict() + "); every corpus "
                                + "entry must be measured (reference unavailable or render "
                                + "failed)");
                    }
                }
            }
            for (EntryResult result : report.results()) {
                if (result.verdict() == Verdict.FAIL) {
                    fail("entry " + result.id() + " failed dimensions "
                            + result.failedDimensions() + " with scores "
                            + "geometry="
                            + String.format(java.util.Locale.ROOT, "%.3f",
                                    result.score().geometry())
                            + " color="
                            + String.format(java.util.Locale.ROOT, "%.3f",
                                    result.score().color())
                            + " detail="
                            + String.format(java.util.Locale.ROOT, "%.3f",
                                    result.score().detail())
                            + " below thresholds geometry="
                            + String.format(java.util.Locale.ROOT, "%.3f",
                                    result.thresholds().geometry())
                            + " color="
                            + String.format(java.util.Locale.ROOT, "%.3f",
                                    result.thresholds().color())
                            + " detail="
                            + String.format(java.util.Locale.ROOT, "%.3f",
                                    result.thresholds().detail()));
                }
                if (result.verdict() == Verdict.PASS || result.verdict() == Verdict.FAIL) {
                    assertTrue(QualificationPolicy.densityInBand(
                                    result.score().referenceCells(),
                                    result.score().recreationCells()),
                            "entry " + result.id() + " recreation density "
                                    + result.score().recreationCells() + "/"
                                    + result.score().referenceCells()
                                    + " cells is outside the policy band");
                    for (FidelityComponent component : FidelityComponent.REQUIRED) {
                        assertFalse(QualificationPolicy.stale(
                                        result.thresholds().component(component),
                                        result.score().component(component)),
                                "entry " + result.id() + " " + component
                                        + " baselines are stale; run "
                                        + ":libgdx-ui-markup-qualification"
                                        + ":calibrateQualification and commit the refreshed "
                                        + "manifest");
                    }
                }
            }
        }
    }

    private static Path property(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing system property -D" + name);
        }
        return Path.of(value);
    }

    private static boolean strict() {
        return Boolean.parseBoolean(System.getProperty("markup.qualification.strict"));
    }
}
