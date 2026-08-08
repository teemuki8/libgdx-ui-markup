package dev.gdx.markup.harness;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs one test body on a real hidden LWJGL3 GL thread (headless under {@code xvfb-run}).
 * Assertions thrown inside the body are captured and rethrown on the test thread. Mirrors the
 * runtime module's render-thread host so the harness integration test can build a live actor
 * tree and register it through {@code MarkupRuntimeSource}.
 */
final class HarnessGdxTestHost {
    private HarnessGdxTestHost() {
    }

    interface TestBody {
        void run() throws Exception;
    }

    static void run(TestBody body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("gdx-ui-markup-harness-test");
        config.setWindowedMode(64, 64);
        config.setInitialVisible(false);
        config.disableAudio(true);
        config.useVsync(false);
        config.setIdleFPS(60);
        Thread main = new Thread(() -> {
            try {
                new Lwjgl3Application(new ApplicationAdapter() {
                    @Override public void create() {
                        started.countDown();
                    }

                    @Override public void render() {
                        try {
                            body.run();
                        } catch (Throwable thrown) {
                            failure.set(thrown);
                        }
                        Gdx.app.exit();
                    }
                }, config);
            } catch (Throwable thrown) {
                failure.set(thrown);
                started.countDown();
            }
        }, "gdx-ui-markup-harness-test-main");
        main.start();
        assertTrue(started.await(30, TimeUnit.SECONDS), "GL context did not start");
        main.join(30_000);
        if (failure.get() != null) {
            throw new AssertionError("GL test body failed", failure.get());
        }
    }
}
