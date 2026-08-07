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
 * Marks up recreations of well-made game UIs and measures their structural region overlap
 * against the real reference screenshots, fetched at test time from their published sources
 * (never redistributed). Entries whose reference cannot be fetched are reported skipped rather
 * than failing; at least one entry must be measured so a silent no-op cannot pass.
 */
final class QualificationTest {
    @Test
    @Timeout(300)
    void recreationRegionsOverlapTheReferenceGameUis() throws Exception {
        try (QualificationRunner runner = new QualificationRunner(
                property("markup.qualification.corpus"),
                property("markup.qualification.cache"),
                property("markup.preview.distribution"),
                property("markup.qualification.output"))) {
            QualificationReport report = runner.run();
            assertTrue(report.scored() >= 1,
                    "at least one corpus entry must be fetched and scored (network or cache)");
            for (EntryResult result : report.results()) {
                if (result.verdict() == Verdict.FAIL) {
                    fail("entry " + result.id() + " scored "
                            + String.format(java.util.Locale.ROOT, "%.3f", result.dice())
                            + " below threshold "
                            + String.format(java.util.Locale.ROOT, "%.3f", result.threshold()));
                }
                if (result.verdict() == Verdict.PASS || result.verdict() == Verdict.FAIL) {
                    assertTrue(QualificationPolicy.densityInBand(
                                    result.referenceCells(), result.recreationCells()),
                            "entry " + result.id() + " recreation density "
                                    + result.recreationCells() + "/" + result.referenceCells()
                                    + " cells is outside the policy band");
                    assertFalse(QualificationPolicy.stale(result.threshold(), result.dice()),
                            "entry " + result.id()
                                    + " baselines are stale; run :libgdx-ui-markup-qualification"
                                    + ":calibrateQualification and commit the refreshed manifest");
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
}
