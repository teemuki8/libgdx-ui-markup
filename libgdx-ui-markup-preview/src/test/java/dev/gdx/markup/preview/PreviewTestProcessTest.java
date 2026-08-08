package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic regression tests for the child-JVM launch configuration, covering the
 * hosted-CI failure class where every GL scenario child died before starting because LWJGL
 * reported {@code GLFW may only be used on the main thread ... -XstartOnFirstThread}.
 *
 * <p>The child JVM ({@link PreviewTestChild}) creates a real GLFW window, so on macOS the
 * JVM must run with {@code -XstartOnFirstThread} (the main thread must be the process's
 * first thread). These tests pin the OS-conditional flag selection as a pure function, so
 * the fix is verified on every platform, not only on macOS.
 */
final class PreviewTestProcessTest {
    @Test
    void macOsChildJvmRunsWithXstartOnFirstThread() {
        assertTrue(PreviewTestProcess.childJvmFlags("Mac OS X").contains("-XstartOnFirstThread"),
                "macOS children need -XstartOnFirstThread for GLFW");
        assertTrue(PreviewTestProcess.childJvmFlags("macOS 15.1").contains("-XstartOnFirstThread"),
                "any macOS os.name spelling selects the flag");
        assertEquals(List.of("-XstartOnFirstThread"),
                PreviewTestProcess.childJvmFlags("Mac OS X"),
                "macOS selects exactly the first-thread flag");
    }

    @Test
    void nonMacOsChildJvmGetsNoExtraFlags() {
        for (String osName : new String[] {"Linux", "Windows 11", "FreeBSD", null}) {
            assertTrue(PreviewTestProcess.childJvmFlags(osName).isEmpty(),
                    "non-macOS platform adds no child JVM flags: " + osName);
        }
    }
}
