package dev.gdx.markup.qualification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.markup.qualification.QualificationReport.EntryResult;
import dev.gdx.markup.qualification.QualificationReport.Verdict;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Multi-signal visual fidelity model: immutable bounded scores, per-component thresholds, the
 * verdict that fails on any single required component, and schema-version-2 report evidence.
 */
final class VisualFidelityTest {
    // ------------------------------------------------------------------ model bounds

    @Test
    void scoreRejectsNonFiniteAndOutOfRangeComponents() {
        assertThrows(IllegalArgumentException.class,
                () -> new FidelityScore(1.0, 0.5, 0.5, Double.NaN, 10, 10),
                "NaN detail must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new FidelityScore(1.1, 0.5, 0.5, 0.5, 10, 10),
                "coarseLayout above 1 must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new FidelityScore(0.5, -0.01, 0.5, 0.5, 10, 10),
                "negative geometry must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new FidelityScore(0.5, 0.5, 0.5, 0.5, -1, 10),
                "negative referenceCells must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new FidelityScore(0.5, 0.5, 0.5, 0.5, 10, -2),
                "negative recreationCells must be rejected");
        new FidelityScore(1.0, 1.0, 1.0, 1.0, 0, 0);
        new FidelityScore(0.0, 0.0, 0.0, 0.0, 3600, 3600);
    }

    @Test
    void thresholdsRejectOutOfRangeAndAcceptOptionalCoarseBaseline() {
        assertThrows(IllegalArgumentException.class,
                () -> new FidelityThresholds(1.01, 0.5, 0.5, 0.5),
                "geometry threshold above 1 must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new FidelityThresholds(0.5, -0.5, 0.5, null),
                "negative color threshold must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new FidelityThresholds(0.5, 0.5, 0.5, Double.POSITIVE_INFINITY),
                "infinite coarse baseline must be rejected");
        FidelityThresholds withoutCoarse = new FidelityThresholds(0.5, 0.5, 0.5, null);
        assertEquals(0.5, withoutCoarse.geometry());
        assertFalse(withoutCoarse.coarseBaseline().isPresent());
    }

    @Test
    void verdictFailsWhenAnySingleRequiredComponentIsBelowThresholdDespiteHighAverage() {
        FidelityScore highAverage = new FidelityScore(0.9, 0.9, 0.01, 0.9, 100, 100);
        FidelityThresholds thresholds = new FidelityThresholds(0.3, 0.3, 0.3, 0.3);
        assertEquals(List.of(FidelityComponent.COLOR),
                QualificationPolicy.failedComponents(highAverage, thresholds),
                "a single low color must fail the verdict even though every other component "
                        + "and the average are high");
        assertEquals(List.of(FidelityComponent.GEOMETRY),
                QualificationPolicy.failedComponents(
                        new FidelityScore(0.9, 0.1, 0.9, 0.9, 100, 100), thresholds));
        assertEquals(List.of(FidelityComponent.DETAIL),
                QualificationPolicy.failedComponents(
                        new FidelityScore(0.9, 0.9, 0.9, 0.1, 100, 100), thresholds));
        assertTrue(QualificationPolicy.failedComponents(
                new FidelityScore(0.9, 0.9, 0.9, 0.9, 100, 100), thresholds).isEmpty(),
                "all components at or above their thresholds must pass");
        assertEquals(2, QualificationPolicy.failedComponents(
                new FidelityScore(0.9, 0.1, 0.9, 0.1, 100, 100), thresholds).size(),
                "two components below threshold must both be reported");
    }

    // ------------------------------------------------------------- deterministic report

    @Test
    void reportSchemaVersionTwoSerializesEveryScoreAndThreshold() throws IOException {
        Path reportFile = Files.createTempFile("qualification-report", ".json");
        try {
            FidelityScore score = new FidelityScore(0.315, 0.51, 0.42, 0.71, 264, 461);
            FidelityThresholds thresholds = new FidelityThresholds(0.2, 0.25, 0.4, 0.205);
            EntryResult passing = new EntryResult("palisade-skirmish", "Apache-2.0",
                    score, thresholds, Verdict.PASS, List.of(), false);
            EntryResult failing = new EntryResult("broken-entry", "MIT",
                    new FidelityScore(0.3, 0.1, 0.9, 0.9, 10, 10),
                    new FidelityThresholds(0.3, 0.3, 0.3, 0.3), Verdict.FAIL,
                    List.of(FidelityComponent.GEOMETRY), false);
            QualificationReport report = new QualificationReport(List.of(passing, failing));
            report.writeJson(reportFile);
            String json = Files.readString(reportFile);
            assertTrue(json.contains("\"schemaVersion\" : 2"), "report must be schema v2");
            assertTrue(json.contains("\"geometry\" : 0.51"));
            assertTrue(json.contains("\"color\" : 0.42"));
            assertTrue(json.contains("\"detail\" : 0.71"));
            assertTrue(json.contains("\"coarseLayout\" : 0.315"));
            assertTrue(json.contains("\"thresholds\""));
            assertTrue(json.contains("\"geometry\" : 0.2"));
            assertTrue(json.contains("\"color\" : 0.25"));
            assertTrue(json.contains("\"detail\" : 0.4"));
            assertTrue(json.contains("\"failedDimensions\""));
            assertTrue(json.contains("\"GEOMETRY\""),
                    "the failed dimension must be named in the report");
            String summary = report.summary();
            assertTrue(summary.contains("geometry=0.510") && summary.contains("color=0.420")
                            && summary.contains("detail=0.710"),
                    "summary must carry every component: " + summary);
            assertTrue(summary.contains("failed=[GEOMETRY]"),
                    "strict summary must identify the failed dimension: " + summary);
            String secondWrite = Files.readString(reportFile);
            report.writeJson(reportFile);
            assertEquals(secondWrite, Files.readString(reportFile),
                    "repeated writes must be byte-identical (deterministic rounding/ordering)");
        } finally {
            Files.deleteIfExists(reportFile);
        }
    }

    @Test
    void reportPreservesCorpusOrderAndCountsOnlyScoredEntries() {
        EntryResult pass = new EntryResult("a", "MIT",
                new FidelityScore(0.5, 0.5, 0.5, 0.5, 5, 5),
                new FidelityThresholds(0.3, 0.3, 0.3, 0.3), Verdict.PASS, List.of(), false);
        EntryResult skip = new EntryResult("b", "MIT",
                FidelityScore.ZERO, new FidelityThresholds(0.3, 0.3, 0.3, 0.3),
                Verdict.SKIPPED_REFERENCE, List.of(), false);
        QualificationReport report = new QualificationReport(List.of(pass, skip));
        assertEquals(List.of("a", "b"),
                report.results().stream().map(EntryResult::id).toList(),
                "results must stay in corpus declaration order");
        assertEquals(1, report.scored(), "skips are not scored");
    }

    // ------------------------------------------------------- identity and determinism

    @Test
    @Timeout(60)
    void identityImageScoresPerfectlyOnEveryComponent() throws IOException {
        BufferedImage image = solidWithText(320, 180, 0xff20282e, 0xffc8b090);
        FidelityScore identity = VisualFidelity.measure(image, image);
        assertEquals(1.0, identity.geometry(), 0.0001);
        assertEquals(1.0, identity.color(), 0.0001);
        assertEquals(1.0, identity.detail(), 0.0001);
    }

    @Test
    @Timeout(60)
    void measureIsDeterministicAcrossDecodePaths() throws IOException {
        BufferedImage image = solidWithText(320, 180, 0xff20282e, 0xffc8b090);
        Path copy = Files.createTempFile("recreation", ".png");
        try {
            ImageIO.write(image, "png", copy.toFile());
            FidelityScore viaReference = VisualFidelity.measure(image, image);
            ReferenceImageStore.ReferenceImage wrapped =
                    new ReferenceImageStore.ReferenceImage(image);
            FidelityScore viaPath = VisualFidelity.measure(wrapped, copy);
            assertEquals(viaReference.geometry(), viaPath.geometry(), 0.0);
            assertEquals(viaReference.color(), viaPath.color(), 0.0);
            assertEquals(viaReference.detail(), viaPath.detail(), 0.0);
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    // ------------------------------------------- geometry and color metric separation

    @Test
    @Timeout(60)
    void translatedAndFlippedImagesLowerGeometryBelowTheCalibratedNegativeCeiling() {
        BufferedImage base = syntheticUi();
        BufferedImage translated = translate(base, 80, 45);
        BufferedImage flipped = NegativeTransforms.flip(base);
        FidelityScore identity = VisualFidelity.measure(base, base);
        FidelityScore translatedScore = VisualFidelity.measure(base, translated);
        FidelityScore flippedScore = VisualFidelity.measure(base, flipped);
        assertEquals(1.0, identity.geometry(), 0.0001, "identity geometry must be 1");
        double ceiling = QualificationPolicy.calibrate(List.of(identity.geometry()),
                List.of(translatedScore.geometry(), flippedScore.geometry())).orElseThrow();
        assertTrue(translatedScore.geometry() < ceiling,
                "translation must lower geometry below the calibrated negative ceiling: "
                        + translatedScore.geometry() + " >= " + ceiling);
        assertTrue(flippedScore.geometry() < ceiling,
                "a vertical flip must lower geometry below the calibrated negative ceiling: "
                        + flippedScore.geometry() + " >= " + ceiling);
        assertTrue(identity.geometry() > ceiling,
                "identity must clear the calibrated ceiling");
    }

    @Test
    @Timeout(60)
    void hueRotationLowersColorWithoutRelyingOnGeometry() {
        BufferedImage base = syntheticUi();
        BufferedImage hueShifted = NegativeTransforms.hueRotate(base);
        FidelityScore identity = VisualFidelity.measure(base, base);
        FidelityScore hueScore = VisualFidelity.measure(base, hueShifted);
        assertEquals(1.0, identity.color(), 0.0001, "identity color must be 1");
        assertTrue(hueScore.color() < identity.color() - 0.05,
                "hue rotation must lower color: " + hueScore.color()
                        + " not below " + (identity.color()
                        - 0.05));
        assertTrue(hueScore.geometry() > 0.9,
                "hue rotation must not rely on geometry: geometry " + hueScore.geometry()
                        + " collapsed although the layout is unchanged");
    }

    @Test
    @Timeout(60)
    void colorAndGeometryAreMeasuredIndependently() {
        BufferedImage base = syntheticUi();
        BufferedImage translated = translate(base, 80, 45);
        BufferedImage hueShifted = NegativeTransforms.hueRotate(base);
        FidelityScore translatedScore = VisualFidelity.measure(base, translated);
        FidelityScore hueScore = VisualFidelity.measure(base, hueShifted);
        assertTrue(translatedScore.color() > 0.9,
                "a pure translation keeps the palette: color " + translatedScore.color()
                        + " collapsed with the geometry");
        assertTrue(translatedScore.geometry() < 0.5,
                "a pure translation must disturb geometry");
        assertTrue(hueScore.geometry() > 0.9,
                "a pure hue rotation keeps the layout: geometry " + hueScore.geometry()
                        + " collapsed with the color");
        assertTrue(hueScore.color() < 0.9,
                "a pure hue rotation must disturb color");
    }

    // ---------------------------------------------------- typography/detail separation

    @Test
    @Timeout(60)
    void blurredTextLowersDetailWhileColorStaysHigh() {
        BufferedImage base = syntheticUi();
        BufferedImage blurred = NegativeTransforms.blur(base);
        FidelityScore identity = VisualFidelity.measure(base, base);
        FidelityScore blurredScore = VisualFidelity.measure(base, blurred);
        assertEquals(1.0, identity.detail(), 0.0001, "identity detail must be 1");
        double ceiling = QualificationPolicy.calibrate(List.of(identity.detail()),
                List.of(blurredScore.detail())).orElseThrow();
        assertTrue(blurredScore.detail() < ceiling,
                "blurred typography must fall below the calibrated negative ceiling: "
                        + blurredScore.detail() + " >= " + ceiling);
        assertTrue(blurredScore.color() > 0.8,
                "blur preserves the palette, so color must stay high while detail falls: "
                        + blurredScore.color());
    }

    @Test
    @Timeout(60)
    void scaledAndSpacingShiftedTextLowerDetailBelowTheCalibratedCeiling() {
        BufferedImage base = syntheticUi();
        BufferedImage scaled = NegativeTransforms.scale(base);
        BufferedImage spacingShifted = spacingShifted(base);
        FidelityScore identity = VisualFidelity.measure(base, base);
        FidelityScore scaledScore = VisualFidelity.measure(base, scaled);
        FidelityScore spacingScore = VisualFidelity.measure(base, spacingShifted);
        double ceiling = QualificationPolicy.calibrate(List.of(identity.detail()),
                List.of(scaledScore.detail(), spacingScore.detail())).orElseThrow();
        assertTrue(scaledScore.detail() < ceiling,
                "uniform scale must lower detail below the calibrated negative ceiling: "
                        + scaledScore.detail() + " >= " + ceiling);
        assertTrue(spacingScore.detail() < ceiling,
                "compressed line spacing must lower detail below the calibrated ceiling: "
                        + spacingScore.detail() + " >= " + ceiling);
        assertTrue(identity.detail() > ceiling, "identity detail must clear the ceiling");
    }

    // --------------------------------------- detail scope and allocation boundedness

    @Test
    @Timeout(60)
    void detailScoresOnlyReferenceDetailCellsSparseLocalized() {
        BufferedImage reference = sparseDetailReference();
        BufferedImage matching = detailPatchAt(reference, 24, 24);
        BufferedImage misplaced = detailPatchAt(reference, 460, 250);
        FidelityScore matchingScore = VisualFidelity.measure(reference, matching);
        FidelityScore misplacedScore = VisualFidelity.measure(reference, misplaced);
        assertTrue(matchingScore.detail() > misplacedScore.detail() + 0.2,
                "a recreation whose detail sits in the reference's detail cells must score "
                        + "well above one whose detail sits in reference-blank cells: "
                        + matchingScore.detail() + " vs " + misplacedScore.detail());
        assertTrue(matchingScore.detail() > 0.5,
                "the localized matching detail must score high: " + matchingScore.detail());
    }

    @Test
    @Timeout(60)
    void metricsReadEachPixelABoundedNumberOfTimes() {
        BufferedImage image = syntheticUi();
        CountingPixels reference = new CountingPixels(image);
        CountingPixels recreation = new CountingPixels(syntheticUi());
        VisualFidelity.measure(reference, recreation);
        long pixels = (long) image.getWidth() * image.getHeight();
        assertTrue(reference.reads() <= 10 * pixels,
                "reference pixels must be read a bounded number of times (gray once per "
                        + "metric, never re-decoded per cell): " + reference.reads()
                        + " reads for " + pixels + " pixels");
        assertTrue(recreation.reads() <= 10 * pixels,
                "recreation pixels must be read a bounded number of times: "
                        + recreation.reads() + " reads for " + pixels + " pixels");
    }

    /**
     * A mostly-blank reference with a single localized text-like detail patch; the patch
     * occupies a small number of 8x8 grid cells near the top-left.
     */
    private static BufferedImage sparseDetailReference() {
        BufferedImage image = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        fill(image, 0xff10141a);
        for (int row = 0; row < 6; row++) {
            fillRect(image, 24, 24 + row * 14, 160, 6, 0xffe8e0c8);
        }
        return image;
    }

    /** Draws a text-like detail patch at the given top-left corner of a dark canvas. */
    private static BufferedImage detailPatchAt(BufferedImage template, int x0, int y0) {
        BufferedImage image = new BufferedImage(template.getWidth(), template.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        fill(image, 0xff10141a);
        for (int row = 0; row < 6; row++) {
            fillRect(image, x0, y0 + row * 14, 160, 6, 0xffe8e0c8);
        }
        return image;
    }

    /** Counts pixel reads through the package-private Pixels seam. */
    private static final class CountingPixels implements VisualFidelity.Pixels {
        private final BufferedImage image;
        private long reads;

        CountingPixels(BufferedImage image) {
            this.image = image;
        }

        long reads() {
            return reads;
        }

        @Override public int width() {
            return image.getWidth();
        }

        @Override public int height() {
            return image.getHeight();
        }

        @Override public int rgb(int x, int y) {
            reads++;
            return image.getRGB(x, y);
        }
    }

    // -------------------------------------------------------------------- fixtures

    /** A deterministic synthetic canvas: dark background plus light horizontal stripes. */
    private static BufferedImage solidWithText(int width, int height, int background, int ink) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, background);
            }
        }
        for (int row = 0; row < 8; row++) {
            int y = 12 + row * 20;
            for (int x = 10; x < width - 10; x++) {
                for (int dy = 0; dy < 8; dy++) {
                    image.setRGB(x, y + dy, ink);
                }
            }
        }
        return image;
    }

    /**
     * Deterministic synthetic UI-like canvas: dark panels with high-contrast borders and
     * text-like horizontal stripe rows, sized so one 80x45 grid cell covers 8x8 pixels.
     */
    private static BufferedImage syntheticUi() {
        BufferedImage image = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        fill(image, 0xff10141a);
        for (int panel = 0; panel < 4; panel++) {
            int px = 40 + panel * 140;
            int py = 30 + (panel % 2) * 150;
            fillRect(image, px, py, 110, 70, 0xff223040);
            fillRect(image, px, py, 110, 3, 0xffd8a040);
            for (int row = 0; row < 3; row++) {
                int ty = py + 18 + row * 16;
                for (int tx = px + 10; tx < px + 100; tx += 12) {
                    fillRect(image, tx, ty, 7, 5, 0xffe8e0c8);
                }
            }
        }
        return image;
    }

    /** Fixed translation by a whole number of 8x8 cells. */
    private static BufferedImage translate(BufferedImage source, int dx, int dy) {
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int sx = x - dx;
                int sy = y - dy;
                out.setRGB(x, y, sx >= 0 && sx < source.getWidth()
                        && sy >= 0 && sy < source.getHeight()
                        ? source.getRGB(sx, sy)
                        : 0xff10141a);
            }
        }
        return out;
    }

    /** Same content with compressed text-line spacing (a typography-only change). */
    private static BufferedImage spacingShifted(BufferedImage source) {
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int sy = y < 300 ? (y * 5) / 6 : y;
                out.setRGB(x, y, source.getRGB(x, sy));
            }
        }
        return out;
    }

    private static void fill(BufferedImage image, int color) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color);
            }
        }
    }

    private static void fillRect(BufferedImage image, int x0, int y0, int width, int height,
            int color) {
        for (int y = y0; y < y0 + height; y++) {
            for (int x = x0; x < x0 + width; x++) {
                image.setRGB(x, y, color);
            }
        }
    }
}
