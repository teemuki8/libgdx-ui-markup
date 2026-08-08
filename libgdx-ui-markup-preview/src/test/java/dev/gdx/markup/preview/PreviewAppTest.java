package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import dev.gdx.markup.core.MarkupDocument;
import dev.gdx.markup.core.MarkupException;
import dev.gdx.markup.core.MarkupParser;
import dev.gdx.markup.core.style.CssDocument;
import dev.gdx.markup.core.style.CssParser;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * GL-free tests for the exact parser calls the preview rebuild path makes: default-limit
 * parsers reading bounded files from disk. Oversized files must fail with a typed
 * {@code TOO_LARGE} diagnostic before any content is materialized, and exact-limit UTF-8 files
 * must parse, so the preview never allocates unbounded Strings from disk.
 */
final class PreviewAppTest {
    @TempDir
    Path tempDir;

    @Test
    void oversizedUiFileFailsTooLargeThroughPreviewParserCall() throws Exception {
        // The final byte starts a two-byte UTF-8 sequence: a decode-first implementation (like
        // the previous Files.readString) would surface an IOException here, not typed TOO_LARGE.
        byte[] over = new byte[MarkupParser.MAX_INPUT_BYTES + 1];
        Arrays.fill(over, (byte) 'x');
        over[over.length - 1] = (byte) 0xC3;
        Path ui = tempDir.resolve("ui.xml");
        Files.write(ui, over);
        MarkupException failure = assertThrows(MarkupException.class,
                () -> new MarkupParser().parse(ui));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("limit"));
    }

    @Test
    void oversizedCssFileFailsTooLargeThroughPreviewParserCall() throws Exception {
        byte[] over = new byte[CssParser.MAX_INPUT_BYTES + 1];
        Arrays.fill(over, (byte) 'x');
        over[over.length - 1] = (byte) 0xC3;
        Path css = tempDir.resolve("ui.css");
        Files.write(css, over);
        MarkupException failure = assertThrows(MarkupException.class,
                () -> new CssParser().parse(css));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("limit"));
    }

    @Test
    void exactLimitUtf8UiAndCssFilesParseThroughPreviewParserCalls() throws Exception {
        String xml = "<ui><!--" + "x".repeat(MarkupParser.MAX_INPUT_BYTES - 16) + "--></ui>";
        Path ui = tempDir.resolve("ui.xml");
        Files.write(ui, xml.getBytes(StandardCharsets.UTF_8));
        MarkupDocument document = new MarkupParser().parse(ui);
        assertEquals("ui", document.root().tag());

        String css = "/*" + "x".repeat(CssParser.MAX_INPUT_BYTES - 4) + "*/";
        Path cssFile = tempDir.resolve("ui.css");
        Files.write(cssFile, css.getBytes(StandardCharsets.UTF_8));
        CssDocument styles = new CssParser().parse(cssFile);
        assertTrue(styles.rules().isEmpty());
    }

    @Test
    void truncatedMultibyteUiFileFailsWithTypedDiagnosticThroughPreviewParserCall()
            throws Exception {
        byte[] truncated = new byte[] {'<', 'u', 'i', '/', '>', (byte) 0xC3};
        Path ui = tempDir.resolve("ui.xml");
        Files.write(ui, truncated);
        MarkupException failure = assertThrows(MarkupException.class,
                () -> new MarkupParser().parse(ui));
        assertEquals(MarkupException.Kind.MALFORMED_XML, failure.kind());
    }

    // ---------------------------------------------------------------------------------------
    // Deterministic frames and screenshots (real LWJGL3; run under `xvfb-run`). The asymmetric
    // top/bottom fixture proves PNG vertical orientation from the bar colors and makes
    // stale-pixel ghosting provable through the transparent middle. Every scenario runs in a
    // dedicated child JVM ({@link PreviewTestChild}) launched on the current test classpath, so
    // no GL/LWJGL thread or Gdx global ever lives in this (parent) test JVM; the parent owns
    // child termination (terminate → bounded wait → force-kill → final wait).
    // ---------------------------------------------------------------------------------------

    private static final int BAR_HEIGHT = 48;

    /**
     * Two independent preview runs of the unchanged asymmetric fixture produce byte-identical
     * PNGs, the PNG is top-left normalized (accent bar on top, panel bar on bottom, fixed
     * clear color in the transparent middle).
     */
    @Test
    @Timeout(120)
    void screenshotIsTopLeftNormalizedAndByteIdenticalAcrossRepeatedRuns() throws Exception {
        Path ui = fixture("asymmetric-top-bottom.xml");
        Path css = fixture("asymmetric-top-bottom.css");

        Path first = tempDir.resolve("first.png");
        Path second = tempDir.resolve("second.png");
        runChild("orientation", ui, css, first);
        runChild("orientation", ui, css, second);

        byte[] firstBytes = Files.readAllBytes(first);
        assertTrue(firstBytes.length > 0, "first screenshot is non-empty");
        assertArrayEquals(firstBytes, Files.readAllBytes(second),
                "an unchanged render produces byte-identical PNGs");
        assertOrientation(first);
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    /**
     * After a prior larger/different render (a full-screen color that nothing in the fixture
     * uses) the target render's PNG is still byte-identical to a run with no history: the
     * fixed clear before every draw must wipe the stale back-buffer pixels.
     */
    @Test
    @Timeout(120)
    void screenshotIsByteIdenticalAfterLargerDifferentPriorRender() throws Exception {
        Path ui = fixture("asymmetric-top-bottom.xml");
        Path css = fixture("asymmetric-top-bottom.css");

        Path clean = tempDir.resolve("clean.png");
        Path afterGhost = tempDir.resolve("after-ghost.png");
        runChild("orientation", ui, css, clean);
        runChild("ghost", ui, css, afterGhost);

        assertArrayEquals(Files.readAllBytes(clean), Files.readAllBytes(afterGhost),
                "the target render is byte-identical after a larger/different prior render");
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    /**
     * A child that renders forever and never exits must be cleaned up by the parent's
     * termination ladder (terminate → bounded wait → force-kill → final wait) with an
     * actionable timeout message; no child process may remain.
     */
    @Test
    @Timeout(120)
    void childThatNeverExitsIsTerminatedByParentLadder() throws Exception {
        try (PreviewTestProcess child = PreviewTestProcess.launch(
                "stuck", null, null, null, Duration.ofSeconds(3))) {
            AssertionError failure = assertThrows(AssertionError.class, child::await);
            assertTrue(failure.getMessage().contains("did not exit"),
                    "actionable timeout message, got: " + failure.getMessage());
            assertFalse(child.isAlive(), "the child process was terminated by the ladder");
        }
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    /**
     * A child that fails before its normal exit exits non-zero with a bounded failure line the
     * parent drains and asserts.
     */
    @Test
    @Timeout(120)
    void childFailureIsReportedWithBoundedOutput() throws Exception {
        try (PreviewTestProcess child = PreviewTestProcess.launch(
                "failing", null, null, null, Duration.ofSeconds(60))) {
            int exit = child.await();
            assertNotEquals(0, exit, "a failing child exits non-zero");
            assertTrue(child.stderr().contains("preview-child: failure listener-failure-before-exit"),
                    "bounded failure line captured, got stderr: " + child.stderr());
        }
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    /**
     * Interrupting the parent while it waits on a stuck child must not skip cleanup: the
     * termination ladder (terminate → bounded wait → force-kill → final wait) and the pump
     * joins complete with real bounded waits, the interrupt status is preserved afterwards,
     * and the child process is dead.
     */
    @Test
    @Timeout(120)
    void interruptedParentStillTerminatesStuckChildAndPreservesInterrupt() throws Exception {
        PreviewTestProcess child = PreviewTestProcess.launch(
                "stuck", null, null, null, Duration.ofSeconds(60));
        AtomicReference<Throwable> awaitFailure = new AtomicReference<>();
        Thread awaiting = new Thread(() -> {
            try {
                child.await();
                awaitFailure.set(null);
            } catch (Throwable thrown) {
                awaitFailure.set(thrown);
            }
        }, "preview-test-awaiting");
        awaiting.start();
        try {
            assertTrue(
                    child.awaitStdoutContaining("preview-child: stuck started",
                            Duration.ofSeconds(30)),
                    "the stuck child reports it started (observable start)");
            awaiting.interrupt();
            awaiting.join(30_000);
            assertFalse(awaiting.isAlive(), "the awaiting thread finished cleanup and returned");
            assertTrue(awaitFailure.get() instanceof InterruptedException,
                    "an interrupted wait reports InterruptedException, got: " + awaitFailure.get());
            assertTrue(awaiting.isInterrupted(), "interrupt status preserved after cleanup");
            assertFalse(child.isAlive(), "the stuck child was terminated by the ladder");
            assertNoChildPumps();
        } finally {
            child.close();
        }
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    /** Proves every child pump thread was joined before the run returned. */
    private static void assertNoChildPumps() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            assertFalse("preview-child-pump".equals(thread.getName()),
                    "a child pump thread is still alive: " + thread.getName());
        }
    }

    private Path fixture(String name) throws Exception {
        try (InputStream in = PreviewAppTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "fixture on the test classpath: " + name);
            Path target = tempDir.resolve(name);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
    }

    /** Runs one child scenario and asserts a clean exit, its ok line, and the PNG artifact. */
    private void runChild(String scenario, Path ui, Path css, Path png) throws Exception {
        runChild(scenario, ui, css, png, Duration.ofSeconds(60));
    }

    private void runChild(String scenario, Path ui, Path css, Path png, Duration deadline)
            throws Exception {
        try (PreviewTestProcess child = PreviewTestProcess.launch(
                scenario, ui, css, png, deadline)) {
            int exit = child.await();
            assertEquals(0, exit, "child exit code; stderr: " + child.stderr());
            assertTrue(child.stdout().contains("preview-child: " + scenario + " ok"),
                    "child ok line; stdout: " + child.stdout() + " stderr: " + child.stderr());
        }
        if (png != null) {
            assertTrue(Files.isRegularFile(png), "screenshot written: " + png);
        }
    }

    /** Asserts the PNG is top-left normalized and the middle shows the fixed clear color. */
    private static void assertOrientation(Path png) throws Exception {
        BufferedImage image = ImageIO.read(png.toFile());
        assertNotNull(image, "PNG decodes: " + png);
        assertEquals(PreviewTestChild.WINDOW_WIDTH, image.getWidth(), "PNG width");
        assertEquals(PreviewTestChild.WINDOW_HEIGHT, image.getHeight(), "PNG height");
        int top = image.getRGB(PreviewTestChild.WINDOW_WIDTH / 2, BAR_HEIGHT / 2);
        int bottom = image.getRGB(PreviewTestChild.WINDOW_WIDTH / 2,
                PreviewTestChild.WINDOW_HEIGHT - 1 - BAR_HEIGHT / 2);
        int middle = image.getRGB(PreviewTestChild.WINDOW_WIDTH / 2,
                PreviewTestChild.WINDOW_HEIGHT / 2);
        assertEquals(rgba("69d2e7ff"), top,
                "the accent bar renders at the PNG top — the screenshot is not vertically flipped");
        assertEquals(rgba("26324aff"), bottom, "the panel bar renders at the PNG bottom");
        assertEquals(rgba("172033ff"), middle,
                "the transparent middle shows the fixed clear color — no stale back-buffer pixels");
    }

    /** Encodes a hex color as ARGB, the byte order {@link BufferedImage#getRGB} returns. */
    private static int rgba(String hex) {
        Color c = Color.valueOf(hex);
        int r = (int) (c.r * 255);
        int g = (int) (c.g * 255);
        int b = (int) (c.b * 255);
        int a = (int) (c.a * 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
