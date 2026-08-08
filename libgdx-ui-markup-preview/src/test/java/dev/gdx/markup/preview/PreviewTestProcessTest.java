package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic regression tests for the shared preview/GL child JVM command construction
 * ({@link PreviewJvmCommand}), covering the hosted-CI failure class where every GL
 * scenario child died before starting because LWJGL reported {@code GLFW may only be used on
 * the main thread ... -XstartOnFirstThread}.
 *
 * <p>Every preview/GL child ({@link PreviewTestChild} including the {@code gl-probe}, the
 * preview app launched by the IDEA plugin, and the Gradle-launched preview) creates a real
 * GLFW window, so on macOS the JVM must run with {@code -XstartOnFirstThread} (the main
 * thread must be the process's first thread) and the flag must appear before the classpath
 * and the main class. These tests pin the OS-conditional flag selection and the full command
 * order as pure functions, so the fix is verified on every platform, not only on macOS.
 */
final class PreviewTestProcessTest {
    @Test
    void macOsChildJvmRunsWithXstartOnFirstThreadBeforeClasspathAndMain() {
        assertTrue(PreviewJvmCommand.isMac("Mac OS X"),
                "the canonical macOS os.name is recognized");
        assertTrue(PreviewJvmCommand.isMac("macOS 15.1"),
                "any macOS os.name spelling is recognized");
        assertEquals(List.of("-XstartOnFirstThread"),
                PreviewJvmCommand.platformJvmFlags("Mac OS X"),
                "macOS selects exactly the first-thread flag");

        List<String> command = PreviewJvmCommand.build(
                "java",
                List.of("--enable-native-access=ALL-UNNAMED"),
                "cp",
                "dev.gdx.markup.preview.PreviewTestChild",
                List.of("gl-probe"),
                "Mac OS X");
        assertEquals(List.of(
                        "java",
                        "-XstartOnFirstThread",
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp", "cp",
                        "dev.gdx.markup.preview.PreviewTestChild",
                        "gl-probe"),
                command,
                "macOS children get -XstartOnFirstThread before -cp and the main class");
        assertEquals("-XstartOnFirstThread", command.get(1),
                "the platform flag is the first JVM option");
        assertEquals("-cp", command.get(3),
                "the classpath option follows the JVM flags, not the platform flag slot");
    }

    @Test
    void nonMacOsChildJvmGetsNoExtraFlagsAndKeepsTheCommandOrder() {
        for (String osName : new String[] {"Linux", "Windows 11", "FreeBSD", null}) {
            assertTrue(PreviewJvmCommand.platformJvmFlags(osName).isEmpty(),
                    "non-macOS platform adds no child JVM flags: " + osName);
            assertFalse(PreviewJvmCommand.isMac(osName),
                    "non-macOS os.name is not treated as macOS: " + osName);
            assertEquals(List.of(
                            "java",
                            "--enable-native-access=ALL-UNNAMED",
                            "-cp", "cp",
                            "Main",
                            "arg"),
                    PreviewJvmCommand.build(
                            "java",
                            List.of("--enable-native-access=ALL-UNNAMED"),
                            "cp",
                            "Main",
                            List.of("arg"),
                            osName),
                    "non-macOS commands carry no extra flag and keep the deterministic order");
        }
    }
}
