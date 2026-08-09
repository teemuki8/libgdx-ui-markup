package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CliOptionsTest {
    @Test
    void parsesAllFlags() {
        CliOptions options = CliOptions.parse(new String[] {
                "--ui", "samples/signin.xml",
                "--css", "samples/signin.gdxcss",
                "--skin", "skin.json",
                "--frames", "42",
                "--screenshot", "out.png",
                "--exit",
                "--mcp",
        });
        assertEquals(Path.of("samples/signin.xml"), options.ui());
        assertEquals(Path.of("samples/signin.gdxcss"), options.css());
        assertEquals(Path.of("skin.json"), options.skin());
        assertEquals(42, options.frames());
        assertEquals(Path.of("out.png"), options.screenshot());
        assertTrue(options.exit());
        assertTrue(options.mcp());
    }

    @Test
    void minimalInvocationDefaultsOff() {
        CliOptions options = CliOptions.parse(
                new String[] {"--ui", "a.xml", "--css", "b.css"});
        assertEquals(0, options.frames());
        assertNull(options.skin());
        assertNull(options.screenshot());
        assertFalse(options.exit());
        assertFalse(options.mcp());
    }

    @Test
    void requiresUiAndCss() {
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[] {"--ui", "a.xml"}));
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[] {}));
    }

    @Test
    void unknownFlagFails() {
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[] {"--ui", "a.xml", "--css", "b.css",
                        "--wat"}));
    }

    @Test
    void missingValueFails() {
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[] {"--ui", "a.xml", "--css"}));
    }

    @Test
    void malformedFramesFails() {
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[] {"--ui", "a.xml", "--css", "b.css",
                        "--frames", "many"}));
    }
}
