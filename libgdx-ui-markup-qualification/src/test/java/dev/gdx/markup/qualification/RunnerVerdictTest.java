package dev.gdx.markup.qualification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.markup.qualification.QualificationReport.EntryResult;
import dev.gdx.markup.qualification.QualificationReport.Verdict;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The authoritative verdict: a recreation whose structured-cell density is outside the
 * reference's band can never PASS, and the report identifies the density failure by name.
 */
final class RunnerVerdictTest {
    private static final FidelityThresholds THRESHOLDS =
            new FidelityThresholds(0.1, 0.1, 0.1, 0.1);

    @Test
    void emptyAndFloodingMasksNeverPassTheAuthoritativeVerdict() {
        FidelityScore flood = new FidelityScore(0.9, 0.9, 0.9, 0.9, 500, 2000);
        FidelityScore empty = new FidelityScore(0.9, 0.9, 0.9, 0.9, 500, 50);
        FidelityScore normal = new FidelityScore(0.9, 0.9, 0.9, 0.9, 500, 250);
        assertTrue(QualificationRunner.failedDimensions(flood, THRESHOLDS)
                        .contains(FidelityComponent.STRUCTURE_DENSITY),
                "a screen-flooding recreation must fail the density invariant");
        assertTrue(QualificationRunner.failedDimensions(empty, THRESHOLDS)
                        .contains(FidelityComponent.STRUCTURE_DENSITY),
                "an almost-empty recreation must fail the density invariant");
        assertFalse(QualificationRunner.failedDimensions(normal, THRESHOLDS)
                        .contains(FidelityComponent.STRUCTURE_DENSITY),
                "an in-band recreation passes the density invariant");
    }

    @Test
    void reportNamesTheDensityFailureAndCannotMarkItPassed() throws IOException {
        Path reportFile = Files.createTempFile("qualification-report", ".json");
        try {
            FidelityScore flood = new FidelityScore(0.9, 0.9, 0.9, 0.9, 500, 2000);
            EntryResult failing = new EntryResult("flood", "MIT", flood, THRESHOLDS,
                    Verdict.FAIL, List.of(FidelityComponent.STRUCTURE_DENSITY));
            QualificationReport report = new QualificationReport(List.of(failing));
            report.writeJson(reportFile);
            String json = Files.readString(reportFile);
            assertTrue(json.contains("\"STRUCTURE_DENSITY\""),
                    "the report must name the density failure: " + json);
            assertTrue(report.summary().contains("failed=[STRUCTURE_DENSITY]"),
                    "the summary must identify the density failure: " + report.summary());
            assertFalse(failing.passed(), "a density failure can never be reported as passed");
        } finally {
            Files.deleteIfExists(reportFile);
        }
    }
}
