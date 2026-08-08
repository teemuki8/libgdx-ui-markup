package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a libGDX listener on a real hidden LWJGL3 GL thread (headless under
 * {@code xvfb-run}). Exceptions that escape the listener or the GL loop are captured and
 * rethrown on the calling thread; the call returns only after the application has fully
 * exited and disposed its window.
 */
final class PreviewTestHost {
    private PreviewTestHost() {
    }

    /**
     * Launches {@code listener} in a hidden {@code width x height} window and blocks until the
     * application exits on its own (for example via {@code Gdx.app.exit()}).
     */
    static void run(ApplicationAdapter listener, int width, int height) throws Exception {
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
        }, "gdx-ui-markup-preview-test-main");
        main.start();
        assertTrue(started.await(30, TimeUnit.SECONDS), "GL context did not start");
        main.join(60_000);
        assertTrue(!main.isAlive(), "application did not exit within 60s");
        if (failure.get() != null) {
            throw new AssertionError("GL test listener failed", failure.get());
        }
    }
}
