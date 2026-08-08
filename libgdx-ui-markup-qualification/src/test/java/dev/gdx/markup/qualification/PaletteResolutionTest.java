package dev.gdx.markup.qualification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runner resolves the per-entry palette ({@code <id>-palette.json}) through the corpus
 * containment helper and enforces the palette byte cap before handing the path to the preview.
 * A palette that escapes the corpus (traversal or symlink) or that exceeds the cap fails as a
 * typed {@link ManifestException}; a missing palette is the normal absent outcome.
 */
final class PaletteResolutionTest {
    @TempDir
    Path tempDir;

    @Test
    void containedPaletteResolvesInsideTheCorpus() throws IOException {
        Path corpus = Files.createDirectories(tempDir.resolve("corpus"));
        Path palette = Files.writeString(corpus.resolve("palisade-palette.json"),
                "{\"background\": \"#040d10ff\"}");
        Optional<Path> resolved = QualificationRunner.containedPalette(corpus, "palisade");
        assertTrue(resolved.isPresent());
        assertEquals(palette.toAbsolutePath().normalize(), resolved.orElseThrow());
    }

    @Test
    void absentPaletteIsTheNormalEmptyOutcome() throws IOException {
        Path corpus = Files.createDirectories(tempDir.resolve("corpus"));
        assertTrue(QualificationRunner.containedPalette(corpus, "no-palette-entry").isEmpty());
    }

    @Test
    void oversizedPaletteIsATypedFailure() throws IOException {
        Path corpus = Files.createDirectories(tempDir.resolve("corpus"));
        Files.write(corpus.resolve("huge-palette.json"),
                new byte[QualificationRunner.MAX_PALETTE_BYTES + 1]);
        ManifestException failure = assertThrows(ManifestException.class,
                () -> QualificationRunner.containedPalette(corpus, "huge"));
        assertEquals(ManifestException.Kind.PALETTE_TOO_LARGE, failure.kind());
    }

    @Test
    void symlinkEscapingPaletteIsATypedFailure() throws IOException {
        Path corpus = Files.createDirectories(tempDir.resolve("corpus"));
        Path outside = Files.writeString(tempDir.resolve("outside.json"), "{}");
        createSymlinkOrAbort(corpus.resolve("escape-palette.json"), outside);
        ManifestException failure = assertThrows(ManifestException.class,
                () -> QualificationRunner.containedPalette(corpus, "escape"));
        assertEquals(ManifestException.Kind.SYMLINK_ESCAPE, failure.kind());
    }

    // ---------------------------------------------------------------- helpers

    private static void createSymlinkOrAbort(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            throw new AssertionError("cannot create symlink " + link + " -> " + target,
                    unavailable);
        }
    }
}
