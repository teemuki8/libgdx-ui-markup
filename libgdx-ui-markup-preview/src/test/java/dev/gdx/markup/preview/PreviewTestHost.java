package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a libGDX listener on a real hidden LWJGL3 GL thread (headless under
 * {@code xvfb-run}). Exceptions that escape the listener or the GL loop are captured and
 * rethrown on the calling thread.
 *
 * <p>Lifecycle contract: the host never returns while its GL thread is alive. On the normal
 * path the application exits on its own (for example via {@code Gdx.app.exit()}) and the
 * host joins it. If the application does not exit on its own within the natural-exit
 * deadline, the host requests a clean exit on the GL thread, joins with a hard deadline, and
 * fails the run with an actionable message. The GL thread is also a daemon so that even a
 * listener stuck inside {@code render()} can never prevent the test JVM from exiting.
 * Teardown always runs in a {@code finally} before any assertion; there is no
 * {@code Thread.stop} and no sleeping.
 */
final class PreviewTestHost {
    /** Thread-name prefix the host owns; tests assert no such thread remains after a run. */
    static final String HOST_THREAD_PREFIX = "gdx-ui-markup-preview-test-";

    private static final int START_TIMEOUT_SECONDS = 30;
    private static final Duration DEFAULT_NATURAL_EXIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration FORCED_EXIT_TIMEOUT = Duration.ofSeconds(30);

    private PreviewTestHost() {
    }

    /**
     * Launches {@code listener} in a hidden {@code width x height} window and blocks until the
     * application exits on its own (for example via {@code Gdx.app.exit()}).
     */
    static void run(ApplicationAdapter listener, int width, int height) throws Exception {
        run(listener, width, height, DEFAULT_NATURAL_EXIT_TIMEOUT);
    }

    /**
     * Package-visible seam for lifecycle regression tests: {@code naturalExitTimeout} bounds
     * how long the host waits for the application to exit on its own before it requests a
     * clean exit and fails the run.
     */
    static void run(ApplicationAdapter listener, int width, int height,
            Duration naturalExitTimeout) throws Exception {
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(naturalExitTimeout, "naturalExitTimeout");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("gdx-ui-markup-preview-test");
        config.setWindowedMode(width, height);
        config.setInitialVisible(false);
        config.disableAudio(true);
        config.useVsync(false);
        Thread main = new Thread(() -> {
            try {
                new Lwjgl3Application(new ApplicationAdapter() {
                    @Override public void create() {
                        listener.create();
                        started.countDown();
                    }

                    @Override public void resize(int width, int height) {
                        listener.resize(width, height);
                    }

                    @Override public void render() {
                        listener.render();
                    }

                    @Override public void dispose() {
                        listener.dispose();
                    }
                }, config);
            } catch (Throwable thrown) {
                failure.set(thrown);
                started.countDown();
            }
        }, HOST_THREAD_PREFIX + "main");
        // The GL loop runs on this thread (the Lwjgl3Application constructor blocks on it), so
        // daemonizing it guarantees a stuck listener can never hang the test JVM.
        main.setDaemon(true);
        main.start();
        try {
            assertTrue(started.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "GL context did not start");
        } finally {
            // Teardown before any assertion: never leave the host thread alive.
            if (!awaitExit(main, naturalExitTimeout)) {
                requestExit();
                if (!awaitExit(main, FORCED_EXIT_TIMEOUT)) {
                    failure.compareAndSet(null, new AssertionError(
                            "application did not exit even after Gdx.app.exit(); "
                                    + "the host thread would hang the test JVM"));
                } else {
                    failure.compareAndSet(null, new AssertionError(
                            "application did not exit on its own within "
                                    + naturalExitTimeout));
                }
            }
        }
        assertTrue(!main.isAlive(), "host thread must be terminated before returning");
        if (failure.get() != null) {
            Throwable cause = failure.get();
            if (cause instanceof AssertionError error) {
                throw error;
            }
            throw new AssertionError("GL test listener failed", cause);
        }
    }

    private static void requestExit() {
        try {
            Gdx.app.exit();
        } catch (Throwable ignored) {
            // Gdx.app may be null when the context never started.
        }
    }

    /** Bounded join; {@code false} when the thread was still alive at the deadline. */
    private static boolean awaitExit(Thread main, Duration timeout) {
        try {
            main.join(Math.max(1, timeout.toMillis()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !main.isAlive();
    }
}
