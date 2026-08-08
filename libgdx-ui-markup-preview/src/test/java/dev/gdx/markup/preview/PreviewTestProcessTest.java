package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic regression tests for the preview/GL child JVM command construction
 * ({@link PreviewTestProcess#command}), covering the hosted-CI failure class where every GL
 * scenario child died before starting because LWJGL reported {@code GLFW may only be used on
 * the main thread ... -XstartOnFirstThread}.
 *
 * <p>Every preview/GL child ({@link PreviewTestChild} including the {@code gl-probe}) creates
 * a real GLFW window, so on macOS the JVM must run with {@code -XstartOnFirstThread} (the
 * main thread must be the process's first thread) and the flag must appear before the
 * classpath and the main class. These tests pin the OS-conditional flag selection and the
 * full command order as pure functions, so the fix is verified on every platform, not only on
 * macOS. The same contract is pinned by the production launcher tests
 * ({@code PreviewProcessLauncherTest}).
 */
final class PreviewTestProcessTest {
    @Test
    void macOsChildJvmRunsWithXstartOnFirstThreadBeforeClasspathAndMain() {
        assertEquals(List.of("-XstartOnFirstThread"),
                PreviewTestProcess.childJvmFlags("Mac OS X"),
                "macOS selects exactly the first-thread flag");
        assertEquals(List.of("-XstartOnFirstThread"),
                PreviewTestProcess.childJvmFlags("macOS 15.1"),
                "any macOS os.name spelling selects the flag");

        List<String> command = PreviewTestProcess.command(
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
            assertTrue(PreviewTestProcess.childJvmFlags(osName).isEmpty(),
                    "non-macOS platform adds no child JVM flags: " + osName);
            assertEquals(List.of(
                            "java",
                            "--enable-native-access=ALL-UNNAMED",
                            "-cp", "cp",
                            "Main",
                            "arg"),
                    PreviewTestProcess.command(
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
