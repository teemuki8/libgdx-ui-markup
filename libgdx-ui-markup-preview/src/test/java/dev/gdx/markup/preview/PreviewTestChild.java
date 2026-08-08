package dev.gdx.markup.preview;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import java.nio.file.Path;

/**
 * Test-child main for the process-isolated preview tests: each scenario runs in its own JVM so
 * no GL/LWJGL thread (and no {@code Gdx} global state) ever lives in the test (parent) JVM.
 * The parent launches this class with the current test classpath, drains bounded stdout/stderr
 * on daemon pumps, waits on a monotonic/bounded deadline, and owns the termination ladder
 * (terminate → bounded wait → force-kill → final wait) for the deliberately stuck scenario.
 *
 * <p>Exit contract: 0 after a successful scenario (with {@code preview-child: <scenario> ok}
 * on stdout); 1 with a bounded {@code preview-child: failure ...} line on stderr for scenario
 * failures; {@code stuck} never exits on its own (the parent terminates it).
 */
public final class PreviewTestChild {
    /** Window size the child renders into; the parent's pixel assertions mirror it. */
    public static final int WINDOW_WIDTH = 480;
    public static final int WINDOW_HEIGHT = 360;

    private static final int FRAMES = 5;
    private static final int MAX_FAILURE_MESSAGE = 2000;

    private PreviewTestChild() {
    }

    /** {@code <scenario> [--ui <path>] [--css <path>] [--png <path>]} */
    public static void main(String[] args) {
        String scenario = args.length > 0 ? args[0] : "";
        Path ui = null;
        Path css = null;
        Path png = null;
        for (int index = 1; index < args.length; index++) {
            switch (args[index]) {
                case "--ui" -> ui = Path.of(args[++index]);
                case "--css" -> css = Path.of(args[++index]);
                case "--png" -> png = Path.of(args[++index]);
                default -> fail("unknown argument " + args[index]);
            }
        }
        try {
            switch (scenario) {
                case "orientation" -> runOrientation(ui, css, png);
                case "ghost" -> runGhost(ui, css, png);
                case "stuck" -> runStuck();
                case "failing" -> runFailing();
                default -> fail("unknown scenario " + scenario);
            }
        } catch (Throwable failure) {
            fail(failure.getMessage() == null ? failure.getClass().getSimpleName()
                    : failure.getMessage());
        }
        System.out.println("preview-child: " + scenario + " ok");
        System.out.flush();
    }

    /** The preview's own CLI path: {@code --frames 5 --screenshot <png> --exit}. */
    private static void runOrientation(Path ui, Path css, Path png) {
        launch(new PreviewApp(CliOptions.parse(new String[] {
                "--ui", ui.toString(),
                "--css", css.toString(),
                "--frames", Integer.toString(FRAMES),
                "--screenshot", png.toString(),
                "--exit",
        })));
    }

    /**
     * Scripted render thread: the target UI renders, then a full-screen different color is
     * painted directly into the default framebuffer (a larger/different prior render), then the
     * target UI renders again until the preview's own {@code --frames} screenshot fires.
     * Frame-scripted and deterministic — no sleeps.
     */
    private static void runGhost(Path ui, Path css, Path png) {
        PreviewApp app = new PreviewApp(CliOptions.parse(new String[] {
                "--ui", ui.toString(),
                "--css", css.toString(),
                "--frames", Integer.toString(FRAMES),
                "--screenshot", png.toString(),
                "--exit",
        }));
        launch(new ApplicationAdapter() {
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
                    // Prior larger/different render: bright green, nothing the fixture uses.
                    Gdx.gl.glClearColor(0f, 1f, 0f, 1f);
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                } else {
                    app.render();
                }
            }

            @Override public void dispose() {
                app.dispose();
            }
        });
    }

    /** Deliberately stuck: reports it started, then renders forever and never exits. */
    private static void runStuck() {
        // Observable start for the parent's interruption test; printed before the endless loop.
        System.out.println("preview-child: stuck started");
        System.out.flush();
        launch(new ApplicationAdapter() {
        });
    }

    /** Fails before normal exit: the first render throws and LWJGL3 rethrows it. */
    private static void runFailing() {
        launch(new ApplicationAdapter() {
            @Override public void render() {
                throw new IllegalStateException("listener-failure-before-exit");
            }
        });
    }

    private static void launch(ApplicationAdapter listener) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("gdx-ui-markup-preview-test-child");
        config.setWindowedMode(WINDOW_WIDTH, WINDOW_HEIGHT);
        config.setInitialVisible(false);
        config.disableAudio(true);
        config.useVsync(false);
        // The constructor blocks on the GL loop and returns only after the app exits.
        new Lwjgl3Application(listener, config);
    }

    private static void fail(String message) {
        System.err.println("preview-child: failure " + bound(message));
        System.err.flush();
        System.exit(1);
    }

    private static String bound(String message) {
        return message.length() <= MAX_FAILURE_MESSAGE
                ? message : message.substring(0, MAX_FAILURE_MESSAGE);
    }
}
