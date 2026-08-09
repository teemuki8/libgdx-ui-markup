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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
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
    void rasterScaleUsesTheLargerAxisAndStaysWithinTheSupportedRange() {
        assertEquals(1f, PreviewApp.rasterScale(1280, 720, 1280, 720));
        assertEquals(2f, PreviewApp.rasterScale(1280, 720, 2560, 1440));
        assertEquals(2f, PreviewApp.rasterScale(1000, 500, 1500, 1000),
                "the larger physical-to-logical axis wins");
        assertEquals(1f, PreviewApp.rasterScale(1000, 500, 500, 250),
                "downscaled backing buffers clamp to one");
        assertEquals(4f, PreviewApp.rasterScale(100, 100, 800, 800),
                "extreme backing buffers clamp to four");
        assertEquals(1f, PreviewApp.rasterScale(0, 0, 0, 0),
                "uninitialized dimensions use the safe default");
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

    /** Skips GL scenarios on hosts where a child JVM cannot create an OpenGL window (e.g.
     * Windows CI runners have no WGL/OpenGL driver). The real GL behavior is still covered on
     * hosts that can (Linux under Xvfb, macOS). */
    private static void requireGl() {
        Assumptions.assumeTrue(PreviewTestProcess.glAvailable(),
                "no OpenGL driver on this host; GL scenario skipped");
    }

    /**
     * Two independent preview runs of the unchanged asymmetric fixture produce byte-identical
     * PNGs, the PNG is top-left normalized (accent bar on top, panel bar on bottom, fixed
     * clear color in the transparent middle).
     */
    @Test
    @Timeout(120)
    void screenshotIsTopLeftNormalizedAndByteIdenticalAcrossRepeatedRuns() throws Exception {
        requireGl();
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
        requireGl();
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
        requireGl();
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
        requireGl();
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
     * A good build, then a failing edit, then a recovery, then a second failing edit and a
     * second recovery, scripted in one child JVM: each failure must keep the last-good skin
     * instance and actors live and staged (the overlay renders on top), each recovery must
     * commit a fresh skin and swap the actors, and the child must exit cleanly.
     */
    @Test
    @Timeout(120)
    void badEditAfterGoodBuildKeepsLastGoodAndRecovers() throws Exception {
        requireGl();
        Path ui = tempDir.resolve("bad-after-good.xml");
        Path css = tempDir.resolve("bad-after-good.css");
        Files.writeString(css, "/* parent placeholder */", StandardCharsets.UTF_8);
        try (PreviewTestProcess child = PreviewTestProcess.launch(
                "bad-after-good", ui, css, null, Duration.ofSeconds(60))) {
            int exit = child.await();
            assertEquals(0, exit, "child exit code; stdout: " + child.stdout()
                    + " stderr: " + child.stderr());
            assertTrue(child.stdout().contains("preview-child: bad-after-good ok"),
                    "child ok line; stdout: " + child.stdout() + " stderr: " + child.stderr());
        }
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    /**
     * The first build fails: the typed error overlay must be visible on screen (staged), no
     * skin or actors may be committed, the overlay must draw without any scene resources, and
     * a later good edit must recover to a committed scene.
     */
    @Test
    @Timeout(120)
    void initialBadBuildShowsErrorOverlayAndRecovers() throws Exception {
        requireGl();
        Path ui = tempDir.resolve("initial-bad.xml");
        Path css = tempDir.resolve("initial-bad.css");
        Files.writeString(css, "/* parent placeholder */", StandardCharsets.UTF_8);
        try (PreviewTestProcess child = PreviewTestProcess.launch(
                "initial-bad", ui, css, null, Duration.ofSeconds(60))) {
            int exit = child.await();
            assertEquals(0, exit, "child exit code; stderr: " + child.stderr());
            assertTrue(child.stdout().contains("preview-child: initial-bad ok"),
                    "child ok line; stdout: " + child.stdout() + " stderr: " + child.stderr());
        }
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    /**
     * A JSON skin keeps its bitmap {@code default-font}, gains exact-size FreeType rendering,
     * and participates in the same density-change transaction as the preview overlay: a failed
     * candidate retains every last-good texture, recovery retires them, and close releases the
     * recovered custom, exact-size, and overlay fonts.
     */
    @Test
    @Timeout(120)
    void customSkinFontSizingAndDensityRefreshAreTransactional() throws Exception {
        requireGl();
        Path ui = tempDir.resolve("custom-skin.xml");
        Path css = tempDir.resolve("custom-skin.css");
        Path skin = writeCustomSkin();
        Files.writeString(css, "/* parent placeholder */", StandardCharsets.UTF_8);
        try (PreviewTestProcess child = PreviewTestProcess.launch(
                "custom-skin-fonts", ui, css, null, skin, Duration.ofSeconds(60))) {
            int exit = child.await();
            assertEquals(0, exit, "child exit code; stdout: " + child.stdout()
                    + " stderr: " + child.stderr());
            assertTrue(child.stdout().contains("preview-child: custom-skin-fonts ok"),
                    "child ok line; stdout: " + child.stdout() + " stderr: " + child.stderr());
        }
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    /**
     * A stage-swap failure injected into the rebuild: the candidate must be rolled back, the
     * exact old stage restored (last-good actors live, old skin undisposed, overlay on top),
     * and a later recovery must commit a fresh scene — all in one child JVM.
     */
    @Test
    @Timeout(120)
    void stageSwapFailureRestoresLastGoodSceneAndRecovers() throws Exception {
        requireGl();
        Path ui = tempDir.resolve("swap-failure.xml");
        Path css = tempDir.resolve("swap-failure.css");
        Files.writeString(css, "/* parent placeholder */", StandardCharsets.UTF_8);
        try (PreviewTestProcess child = PreviewTestProcess.launch(
                "swap-failure", ui, css, null, Duration.ofSeconds(60))) {
            int exit = child.await();
            assertEquals(0, exit, "child exit code; stderr: " + child.stderr());
            assertTrue(child.stdout().contains("preview-child: swap-failure ok"),
                    "child ok line; stdout: " + child.stdout() + " stderr: " + child.stderr());
        }
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    /**
     * Interrupting the parent while it waits on a stuck child — repeatedly, including while
     * cleanup is running — must not skip or abort cleanup: every bounded wait in the
     * termination ladder (terminate → bounded wait → force-kill → final wait) and the pump
     * joins retries on interrupts with its monotonic remaining deadline, the interrupt status
     * is preserved afterwards, and the child process and both pumps are dead.
     */
    @Test
    @Timeout(120)
    void interruptedParentStillTerminatesStuckChildAndPreservesInterrupt() throws Exception {
        requireGl();
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
        Thread reinterrupter = null;
        try {
            assertTrue(
                    child.awaitStdoutContaining("preview-child: stuck started",
                            Duration.ofSeconds(30)),
                    "the stuck child reports it started (observable start)");
            // Hammer the awaiting thread with interrupts while it runs the termination ladder,
            // so every bounded wait in cleanup must retry on interrupts instead of aborting
            // its phase. Paced by parkNanos, not a condition wait; daemon so a failed test
            // can never hang the JVM.
            reinterrupter = new Thread(() -> {
                while (awaiting.isAlive() && !Thread.currentThread().isInterrupted()) {
                    awaiting.interrupt();
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                }
            }, "preview-test-reinterrupter");
            reinterrupter.setDaemon(true);
            reinterrupter.start();
            awaiting.interrupt();
            awaiting.join(60_000);
            assertFalse(awaiting.isAlive(), "the awaiting thread finished cleanup and returned");
            assertTrue(awaitFailure.get() instanceof InterruptedException,
                    "an interrupted wait reports InterruptedException, got: " + awaitFailure.get());
            assertTrue(awaiting.isInterrupted(), "interrupt status preserved after cleanup");
            assertFalse(child.isAlive(), "the stuck child was terminated by the ladder");
            assertNoChildPumps();
        } finally {
            if (reinterrupter != null) {
                reinterrupter.interrupt();
                reinterrupter.join(10_000);
            }
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

    private Path writeCustomSkin() throws Exception {
        Path png = tempDir.resolve("custom-font.png");
        BufferedImage pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        pixel.setRGB(0, 0, 0xffffffff);
        ImageIO.write(pixel, "png", png.toFile());
        Files.writeString(tempDir.resolve("custom-font.fnt"), """
                info face="custom" size=16 bold=0 italic=0 charset="" unicode=1 stretchH=100 smooth=1 aa=1 padding=0,0,0,0 spacing=0,0
                common lineHeight=16 base=13 scaleW=1 scaleH=1 pages=1 packed=0
                page id=0 file="custom-font.png"
                chars count=1
                char id=32 x=0 y=0 width=1 height=1 xoffset=0 yoffset=0 xadvance=4 page=0 chnl=15
                kernings count=0
                """, StandardCharsets.UTF_8);
        Path skin = tempDir.resolve("custom-skin.json");
        Files.writeString(skin, """
                {
                  "com.badlogic.gdx.graphics.Color": {
                    "white": { "r": 1, "g": 1, "b": 1, "a": 1 }
                  },
                  "com.badlogic.gdx.graphics.g2d.BitmapFont": {
                    "default-font": { "file": "custom-font.fnt" }
                  },
                  "com.badlogic.gdx.scenes.scene2d.ui.Label$LabelStyle": {
                    "default": { "font": "default-font", "fontColor": "white" }
                  }
                }
                """, StandardCharsets.UTF_8);
        return skin;
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
