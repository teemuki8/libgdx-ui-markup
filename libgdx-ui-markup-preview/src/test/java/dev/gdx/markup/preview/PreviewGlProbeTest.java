package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_API_UNAVAILABLE;
import static org.lwjgl.glfw.GLFW.GLFW_OUT_OF_MEMORY;

import org.junit.jupiter.api.Test;

/**
 * Red/green classification tests for the GL probe gate. The probe may ONLY skip gated GL
 * scenarios for the exact hosted-Windows no-OpenGL-driver signature (GLFW_API_UNAVAILABLE
 * with the WGL "driver does not appear to support OpenGL" error on a Windows host); every
 * other outcome — a non-zero exit, a missing or lookalike unavailable marker, the
 * unavailable marker on a non-Windows host, or a non-matching GLFW error — must FAIL the
 * gated tests, never skip them.
 */
final class PreviewGlProbeTest {
    @Test
    void okMarkerIsAvailableOnAnyHost() {
        assertTrue(PreviewTestProcess.classifyGlProbe(0, PreviewTestChild.GL_PROBE_OK, "", true),
                "an ok marker with exit 0 means the host can create a GL window");
        assertTrue(PreviewTestProcess.classifyGlProbe(0, PreviewTestChild.GL_PROBE_OK, "", false),
                "an ok marker is available regardless of the host OS");
    }

    @Test
    void exactWindowsUnavailableMarkerSkipsOnlyOnWindows() {
        assertFalse(PreviewTestProcess.classifyGlProbe(
                0, PreviewTestChild.GL_PROBE_WINDOWS_UNAVAILABLE, "", true),
                "the exact Windows no-OpenGL-driver marker on Windows skips the GL scenarios");
        assertThrows(AssertionError.class, () -> PreviewTestProcess.classifyGlProbe(
                        0, PreviewTestChild.GL_PROBE_WINDOWS_UNAVAILABLE, "", false),
                "the unavailable marker on a non-Windows host fails, never skips");
    }

    @Test
    void nonZeroExitFails() {
        assertThrows(AssertionError.class, () -> PreviewTestProcess.classifyGlProbe(
                        1, PreviewTestChild.GL_PROBE_OK, "preview-child: failure boom", true),
                "a non-zero probe exit fails the gated tests");
    }

    @Test
    void nonZeroExitSurfacesTheChildFailureLineFromTheStderrTail() {
        // The child prints JDK/LWJGL warnings before its own 'preview-child: failure' line;
        // the parent must surface the stderr tail (where the diagnosis lives), not only the
        // head — a head-only view hid the exact macOS probe exit cause in hosted CI.
        String warnings = "WARNING: sun.misc.Unsafe::objectFieldOffset\n".repeat(60);
        String diagnosis = "preview-child: failure gl-probe window creation failed on "
                + "Mac OS X/aarch64 (Java 25): 65544:Cocoa: Failed to create window";
        AssertionError failure = assertThrows(AssertionError.class,
                () -> PreviewTestProcess.classifyGlProbe(1, "", warnings + diagnosis, true));
        assertTrue(failure.getMessage().contains("preview-child: failure"),
                "the exit-1 message must surface the child failure line: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("65544:Cocoa"),
                "the exit-1 message must surface the GLFW error: " + failure.getMessage());
        assertFalse(failure.getMessage().startsWith("WARNING:"),
                "the exit-1 message must lead with the failure summary, not JDK warnings: "
                        + failure.getMessage());
    }

    @Test
    void missingMarkerFails() {
        assertThrows(AssertionError.class, () -> PreviewTestProcess.classifyGlProbe(
                        0, "", "", true),
                "a probe child that prints no recognized marker fails, never skips");
    }

    @Test
    void lookalikeUnavailableMarkerFails() {
        // A marker that merely SAYS unavailable without the exact structured Windows
        // signature must fail: the previous broad catch converted any child failure into a
        // skip and hid regressions.
        assertThrows(AssertionError.class, () -> PreviewTestProcess.classifyGlProbe(
                        0, "preview-child: gl-probe unavailable: Couldn't create window",
                        "", true),
                "a lookalike unavailable marker (not the exact Windows signature) fails");
        assertThrows(AssertionError.class, () -> PreviewTestProcess.classifyGlProbe(
                        0, "preview-child: gl-probe unavailable", "", true),
                "a bare unavailable marker without the signature fails");
    }

    @Test
    void windowsSignatureMatchesOnlyTheExactCondition() {
        String signature = GLFW_API_UNAVAILABLE
                + ":WGL: The driver does not appear to support OpenGL";
        assertTrue(PreviewTestChild.isWindowsNoOpenGlDriver(signature, true),
                "the exact GLFW_API_UNAVAILABLE/WGL driver message on Windows matches");
        assertFalse(PreviewTestChild.isWindowsNoOpenGlDriver(signature, false),
                "the same error on a non-Windows host is not the unavailable condition");
        assertFalse(PreviewTestChild.isWindowsNoOpenGlDriver(
                GLFW_OUT_OF_MEMORY + ":WGL: The driver does not appear to support OpenGL", true),
                "a different GLFW error code is not the unavailable condition");
        assertFalse(PreviewTestChild.isWindowsNoOpenGlDriver(
                GLFW_API_UNAVAILABLE + ":X11: Failed to open display", true),
                "a different backend message is not the unavailable condition");
        assertFalse(PreviewTestChild.isWindowsNoOpenGlDriver(
                GLFW_API_UNAVAILABLE + ":WGL: something else entirely", true),
                "a missing exact driver message is not the unavailable condition");
        assertFalse(PreviewTestChild.isWindowsNoOpenGlDriver(null, true),
                "a missing GLFW error capture is not the unavailable condition");
        assertFalse(PreviewTestChild.isWindowsNoOpenGlDriver("garbage", true),
                "an unparsable capture is not the unavailable condition");
    }
}
