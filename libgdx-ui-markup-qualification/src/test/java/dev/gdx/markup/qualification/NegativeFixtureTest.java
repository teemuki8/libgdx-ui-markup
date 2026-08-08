package dev.gdx.markup.qualification;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies the committed deterministic negative fixtures under
 * {@code src/test/resources/negative/}: each deliberately-broken palisade recreation is
 * measured against the palisade reference through the same production scorer used by the
 * qualification, and its intended component must score below the calibrated committed
 * threshold — i.e. the committed gate genuinely rejects every calibrated negative while the
 * faithful recreation (measured by the qualification test) clears it.
 */
final class NegativeFixtureTest {
    @Test
    @Timeout(120)
    void everyCommittedNegativeIsRejectedByItsIntendedComponent() throws Exception {
        Path corpus = Path.of(property("markup.qualification.corpus"));
        Path resources = Path.of("src", "test", "resources", "negative");
        BufferedImage reference = ImageIO.read(
                corpus.resolve("reference/palisade-initial.png").toFile());
        CorpusEntry palisade = CorpusManifest.load(corpus.resolve("manifest.json")).entries()
                .stream().filter(entry -> entry.id().equals("palisade-skirmish")).findFirst()
                .orElseThrow();
        double geometryThreshold = palisade.thresholds().geometry();
        double colorThreshold = palisade.thresholds().color();
        double detailThreshold = palisade.thresholds().detail();
        Map<String, FidelityComponent> intended = Map.of(
                "flip", FidelityComponent.GEOMETRY,
                "translate", FidelityComponent.GEOMETRY,
                "scale", FidelityComponent.GEOMETRY,
                "hue", FidelityComponent.COLOR,
                "blur", FidelityComponent.DETAIL);
        for (Map.Entry<String, FidelityComponent> fixture : intended.entrySet()) {
            Path png = resources.resolve("palisade-" + fixture.getKey() + ".png");
            if (!png.toFile().isFile()) {
                fail("committed fixture missing: " + png);
            }
            BufferedImage negative = ImageIO.read(png.toFile());
            FidelityScore score = VisualFidelity.measure(reference, negative);
            FidelityComponent component = fixture.getValue();
            double threshold = switch (component) {
                case GEOMETRY -> geometryThreshold;
                case COLOR -> colorThreshold;
                case DETAIL -> detailThreshold;
                case COARSE_LAYOUT -> throw new AssertionError();
            };
            assertTrue(score.component(component) < threshold,
                    "fixture palisade-" + fixture.getKey() + " must be rejected by "
                            + component + ": " + score.component(component)
                            + " is not below the committed threshold " + threshold);
        }
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing system property -D" + name);
        }
        return value;
    }
}
