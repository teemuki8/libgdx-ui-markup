package dev.gdx.markup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The optional flat-JSON palette override is read byte-bounded (cap + 1) before any UTF-8
 * string is allocated, so an oversized palette is rejected without a full-file read, and a
 * valid small palette overrides only the recognized color names.
 */
final class DefaultSkinPaletteTest {
    @TempDir
    Path tempDir;

    @Test
    void readsRecognizedColorsFromASmallPalette() throws IOException {
        Path palette = tempDir.resolve("palette.json");
        Files.writeString(palette, """
                {"background": "#120d18ff", "accent": "#c05a2aff", "bogus": "#12345678"}
                """);
        Map<String, String> overrides = DefaultSkin.readPaletteFile(palette);
        assertEquals("#120d18ff", overrides.get("background"));
        assertEquals("#c05a2aff", overrides.get("accent"));
        assertTrue(overrides.entrySet().stream().noneMatch(e -> e.getKey().equals("bogus")),
                "unknown names must be ignored");
    }

    @Test
    void acceptsPaletteExactlyAtTheByteCap() throws IOException {
        Path palette = tempDir.resolve("exact.json");
        String body = "{\"background\": \"#120d18ff\", \"panel\": \"#26324aff\"}";
        Files.writeString(palette, body + " ".repeat(DefaultSkin.MAX_PALETTE_BYTES
                - body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        assertEquals(DefaultSkin.MAX_PALETTE_BYTES,
                Files.size(palette), "fixture must sit exactly at the byte cap");
        Map<String, String> overrides = DefaultSkin.readPaletteFile(palette);
        assertEquals("#120d18ff", overrides.get("background"));
        assertEquals("#26324aff", overrides.get("panel"));
    }

    @Test
    void rejectsPaletteBeyondTheByteCapWithoutReadingItAll() throws IOException {
        Path palette = tempDir.resolve("huge.json");
        Files.write(palette, new byte[DefaultSkin.MAX_PALETTE_BYTES + 1]);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> DefaultSkin.readPaletteFile(palette));
        assertTrue(failure.getMessage().contains("too large"),
                "oversized palette must fail with the size diagnostic: " + failure.getMessage());
    }

    @Test
    void missingPaletteFileFailsLoudly() {
        Path missing = tempDir.resolve("absent.json");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> DefaultSkin.readPaletteFile(missing));
        assertTrue(failure.getMessage().contains("cannot read"),
                "unreadable palette must fail loudly: " + failure.getMessage());
    }
}
