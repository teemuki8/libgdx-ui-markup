package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import dev.gdx.markup.core.MarkupDocument;
import dev.gdx.markup.core.MarkupException;
import dev.gdx.markup.core.MarkupParser;
import dev.gdx.markup.core.style.CssDocument;
import dev.gdx.markup.core.style.CssParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
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
    // stale-pixel ghosting provable through the transparent middle.
    // ---------------------------------------------------------------------------------------

    private static final int WINDOW_WIDTH = 480;
    private static final int WINDOW_HEIGHT = 360;
    private static final int BAR_HEIGHT = 48;
    private static final int FRAMES = 5;

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
        runCli(ui, css, first);
        runCli(ui, css, second);

        byte[] firstBytes = Files.readAllBytes(first);
        assertTrue(firstBytes.length > 0, "first screenshot is non-empty");
        assertArrayEquals(firstBytes, Files.readAllBytes(second),
                "an unchanged render produces byte-identical PNGs");
        assertOrientation(first);
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
        runCli(ui, css, clean);
        runGhostCli(ui, css, afterGhost);

        assertArrayEquals(Files.readAllBytes(clean), Files.readAllBytes(afterGhost),
                "the target render is byte-identical after a larger/different prior render");
    }

    /**
     * A preview run whose application never exits on its own must still terminate: the host
     * requests a clean exit on the GL thread and joins with a hard deadline instead of leaving
     * a live non-daemon thread behind to hang the test JVM.
     */
    @Test
    @Timeout(120)
    void hostForcesExitAndReportsWhenApplicationNeverExits() throws Exception {
        ApplicationAdapter endless = new ApplicationAdapter() {
            // Renders forever, never calls Gdx.app.exit().
        };
        AssertionError failure = assertThrows(AssertionError.class,
                () -> PreviewTestHost.run(endless, 64, 64, Duration.ofMillis(250)));
        assertTrue(failure.getMessage().contains("did not exit"),
                "failure names the never-exit condition, got: " + failure.getMessage());
        assertNoPreviewHostThreads();
    }

    /**
     * A listener that throws before its normal exit must surface the failure as the assertion
     * cause and leave no live host thread behind.
     */
    @Test
    @Timeout(120)
    void hostRethrowsListenerFailureAndLeavesNoLiveThread() throws Exception {
        IllegalStateException boom = new IllegalStateException("listener-failure-before-exit");
        ApplicationAdapter failing = new ApplicationAdapter() {
            @Override public void render() {
                throw boom;
            }
        };
        AssertionError failure = assertThrows(AssertionError.class,
                () -> PreviewTestHost.run(failing, 64, 64, Duration.ofSeconds(5)));
        assertSame(boom, failure.getCause(),
                "the listener's exception is the assertion cause");
        assertNoPreviewHostThreads();
    }

    /** Proves the host joined every thread it spawned before returning. */
    private static void assertNoPreviewHostThreads() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            assertFalse(thread.getName().startsWith(PreviewTestHost.HOST_THREAD_PREFIX),
                    "a preview host thread is still alive: " + thread.getName());
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

    /** Runs the real preview CLI path for {@link #FRAMES} frames in one hidden GL window. */
    private void runCli(Path ui, Path css, Path png) throws Exception {
        PreviewApp app = new PreviewApp(options(ui, css, png));
        String status = captureStdout(() -> {
            try {
                PreviewTestHost.run(app, WINDOW_WIDTH, WINDOW_HEIGHT);
            } catch (Exception failure) {
                throw new AssertionError("preview run failed", failure);
            }
        });
        assertTrue(status.contains("\"ok\":true"), "preview reported a successful build, got: "
                + status);
        assertTrue(Files.isRegularFile(png), "screenshot written: " + png);
    }

    /**
     * Runs the preview with a scripted render thread: the target UI renders, then a full-screen
     * different color is painted directly into the default framebuffer (a larger/different prior
     * render), then the target UI renders again until the preview's own {@code --frames}
     * screenshot fires. Frame-scripted and deterministic — no sleeps.
     */
    private void runGhostCli(Path ui, Path css, Path png) throws Exception {
        PreviewApp app = new PreviewApp(options(ui, css, png));
        ApplicationAdapter scripted = new ApplicationAdapter() {
            private int frame;

            @Override public void create() {
                app.create();
            }

            @Override public void resize(int width, int height) {
                app.resize(width, height);
            }

            @Override public void render() {
                frame++;
                if (frame == 1) {
                    app.render();
                } else if (frame == 2) {
                    paintWholeBackBufferDifferent();
                } else {
                    app.render();
                }
            }

            @Override public void dispose() {
                app.dispose();
            }
        };
        String status = captureStdout(() -> {
            try {
                PreviewTestHost.run(scripted, WINDOW_WIDTH, WINDOW_HEIGHT);
            } catch (Exception failure) {
                throw new AssertionError("preview run failed", failure);
            }
        });
        assertTrue(status.contains("\"ok\":true"), "preview reported a successful build, got: "
                + status);
        assertTrue(Files.isRegularFile(png), "screenshot written: " + png);
    }

    private static CliOptions options(Path ui, Path css, Path png) {
        return CliOptions.parse(new String[] {
                "--ui", ui.toString(),
                "--css", css.toString(),
                "--frames", Integer.toString(FRAMES),
                "--screenshot", png.toString(),
                "--exit",
        });
    }

    /** Paints every pixel of the default framebuffer a bright green the fixture never uses. */
    private static void paintWholeBackBufferDifferent() {
        Gdx.gl.glClearColor(0f, 1f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
    }

    /** Asserts the PNG is top-left normalized and the middle shows the fixed clear color. */
    private static void assertOrientation(Path png) throws Exception {
        BufferedImage image = ImageIO.read(png.toFile());
        assertNotNull(image, "PNG decodes: " + png);
        assertEquals(WINDOW_WIDTH, image.getWidth(), "PNG width");
        assertEquals(WINDOW_HEIGHT, image.getHeight(), "PNG height");
        int top = image.getRGB(WINDOW_WIDTH / 2, BAR_HEIGHT / 2);
        int bottom = image.getRGB(WINDOW_WIDTH / 2, WINDOW_HEIGHT - 1 - BAR_HEIGHT / 2);
        int middle = image.getRGB(WINDOW_WIDTH / 2, WINDOW_HEIGHT / 2);
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

    private static String captureStdout(Runnable runnable) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            runnable.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
