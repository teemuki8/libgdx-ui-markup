package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import dev.gdx.markup.runtime.MarkupRuntimeSource;
import dev.gdx.uiharness.mcp.HarnessMcpServer;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** Empty stylesheet shared by the transactional-rebuild fixtures. */
    private static final String EMPTY_CSS = "/* transactional rebuild fixtures */";

    /** A valid scene with a {@code title} actor and an accent bar. */
    private static final String GOOD_UI_A = """
            <table>
              <row/>
              <label id="title" text="Good A"/>
              <row/>
              <image id="accent-bar" drawable="accent" height="48" fill="true" expand="x"/>
            </table>
            """;

    /** A second valid scene with a {@code title} and a {@code subtitle} actor, no accent bar. */
    private static final String GOOD_UI_B = """
            <table>
              <row/>
              <label id="title" text="Good B"/>
              <row/>
              <label id="subtitle" text="Second"/>
            </table>
            """;

    /** Fails during the candidate build (unknown drawable), after the candidate skin exists. */
    private static final String BAD_UI = """
            <table>
              <row/>
              <image id="boom" drawable="no-such-drawable" height="48" fill="true" expand="x"/>
            </table>
            """;

    /** Declares runtime entity {@code user} backed by a real actor. */
    private static final String ENTITY_UI_A = """
            <table>
              <row/>
              <textfield id="user" data-runtime-entity="user" text="A"/>
            </table>
            """;

    /** Valid scene whose runtime entities collide with the live {@code user} registration:
     * the same entity id is declared twice (on distinct actor ids), so a transactional retry
     * after removing the old ids fails with the runtime's own DUPLICATE_ENTITY. */
    private static final String COLLIDING_BAD_UI = """
            <table>
              <row/>
              <textfield id="user" data-runtime-entity="user" text="A"/>
              <row/>
              <textfield id="mirror" data-runtime-entity="user" text="B"/>
            </table>
            """;

    /** A valid scene whose runtime entity is disjoint from the live {@code user} registration. */
    private static final String DISJOINT_GOOD_UI = """
            <table>
              <row/>
              <textfield id="other" data-runtime-entity="other" text="B"/>
            </table>
            """;

    /** Valid scene whose runtime entities are disjoint from the live {@code user} registration
     * and whose registration fails during preflight (an entity without an id). */
    private static final String DISJOINT_BAD_UI = """
            <table>
              <row/>
              <textfield id="other" data-runtime-entity="other" text="B"/>
              <row/>
              <textfield data-runtime-entity="ghost"/>
            </table>
            """;

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
                case "bad-after-good" -> runBadAfterGood(ui, css);
                case "initial-bad" -> runInitialBad(ui, css);
                case "mcp-attach" -> runMcpAttach(ui, css);
                case "swap-failure" -> runSwapFailure(ui, css);
                case "mcp-swap-failure" -> runMcpSwapFailure(ui, css);
                case "retire-failure" -> runRetireFailure(ui, css);
                case "restore-failure" -> runRestoreFailure(ui, css);
                case "mcp-cleanup-failure" -> runMcpCleanupFailure(ui, css);
                case "mcp-close-failure" -> runMcpCloseFailure(ui, css);
                case "mcp-close-all" -> runMcpCloseAll(ui, css);
                case "mcp-cause-chain" -> runMcpCauseChain(ui, css);
                case "mcp-init-failure" -> runMcpInitFailure(ui, css);
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

    /**
     * Good build, then a failing edit, then a recovery, then a second failing edit and a second
     * recovery, all scripted on the GL thread (no sleeps). Every failure must keep the last-good
     * skin instance and actors live and staged with the error overlay on top — rendering after a
     * failure would throw if the retained skin had been disposed — and every recovery must
     * commit a fresh skin and swap the staged actors.
     */
    private static void runBadAfterGood(Path ui, Path css) {
        writeFixture(ui, css, GOOD_UI_A);
        PreviewApp app = new PreviewApp(CliOptions.parse(new String[]{
                "--ui", ui.toString(), "--css", css.toString()}));
        launch(new ApplicationAdapter() {
            private int frame;
            private Skin goodA;
            private Skin goodB;

            @Override public void create() {
                app.create();
            }

            @Override public void render() {
                try {
                    frame++;
                    switch (frame) {
                        case 1 -> {
                            app.render();
                            goodA = app.skin();
                            assertNotNull(goodA, "the first build commits a skin");
                            assertTrue(app.stageContains("title"),
                                    "the first build stages its actors");
                            assertFalse(app.errorOverlayVisible(), "no overlay after a good build");
                        }
                        case 2 -> {
                            writeUi(ui, BAD_UI);
                            app.rebuild();
                            assertTrue(app.errorOverlayVisible(),
                                    "the error overlay is visible after a bad edit");
                            assertSame(goodA, app.skin(),
                                    "the old skin is retained (same instance) after a bad edit");
                            assertTrue(app.stageContains("title"),
                                    "last-good actors remain staged after a bad edit");
                        }
                        case 3 -> {
                            // Rendering the last-good scene must not throw: if the retained skin
                            // had been disposed, drawing its textures would fail the frame.
                            app.render();
                            assertTrue(app.errorOverlayVisible(),
                                    "the overlay stays visible while last-good actors render");
                        }
                        case 4 -> {
                            writeUi(ui, GOOD_UI_B);
                            app.rebuild();
                            assertFalse(app.errorOverlayVisible(), "overlay hidden after recovery");
                            assertNotSame(goodA, app.skin(), "recovery commits a fresh skin");
                            assertTrue(app.stageContains("subtitle"), "recovered actors staged");
                            assertFalse(app.stageContains("accent-bar"),
                                    "the previous scene's actors were swapped out");
                        }
                        case 5 -> {
                            app.render();
                            goodB = app.skin();
                        }
                        case 6 -> {
                            // Repeated recovery: a second bad edit, then a second recovery.
                            writeUi(ui, BAD_UI);
                            app.rebuild();
                            assertTrue(app.errorOverlayVisible(),
                                    "a second bad edit shows the overlay again");
                            assertSame(goodB, app.skin(),
                                    "the second last-good skin is retained after the second bad edit");
                            writeUi(ui, GOOD_UI_A);
                            app.rebuild();
                            assertFalse(app.errorOverlayVisible(),
                                    "a second recovery hides the overlay");
                            assertNotSame(goodB, app.skin(),
                                    "the second recovery commits a fresh skin");
                            assertTrue(app.stageContains("title"), "the recovered scene staged");
                            app.render();
                            Gdx.app.exit();
                        }
                        default -> {
                        }
                    }
                } catch (Throwable thrown) {
                    fail(messageOf(thrown));
                }
            }

            @Override public void dispose() {
                app.dispose();
            }
        });
    }

    /**
     * The first build fails: the typed error overlay must be visible and staged, no skin or
     * actors may be committed, the overlay must draw without any scene resources, and a later
     * good edit must recover to a committed scene.
     */
    private static void runInitialBad(Path ui, Path css) {
        writeFixture(ui, css, BAD_UI);
        PreviewApp app = new PreviewApp(CliOptions.parse(new String[]{
                "--ui", ui.toString(), "--css", css.toString()}));
        launch(new ApplicationAdapter() {
            private int frame;

            @Override public void create() {
                app.create();
            }

            @Override public void render() {
                try {
                    frame++;
                    if (frame == 1) {
                        assertTrue(app.errorOverlayVisible(),
                                "the initial error overlay is visible and staged");
                        assertNull(app.skin(), "no skin is committed by a failed first build");
                        assertFalse(app.stageContains("title"),
                                "no actors are staged by a failed first build");
                        // The overlay draws without any skin or actors (its font is independent).
                        app.render();
                    } else if (frame == 2) {
                        writeUi(ui, GOOD_UI_A);
                        app.rebuild();
                        assertFalse(app.errorOverlayVisible(), "overlay hidden after recovery");
                        assertNotNull(app.skin(), "recovery commits a skin");
                        assertTrue(app.stageContains("title"), "recovered actors staged");
                        app.render();
                        Gdx.app.exit();
                    }
                } catch (Throwable thrown) {
                    fail(messageOf(thrown));
                }
            }

            @Override public void dispose() {
                app.dispose();
            }
        });
    }

    /**
     * Transactional runtime attachment in {@code --mcp} mode: a good build commits a live
     * owner, a colliding candidate failure must remove the old ids and reinstall the last-good
     * registration, a disjoint candidate failure must preserve the live owner untouched, and a
     * recovery must commit a fresh owner. Every frame after a failure must carry exactly the
     * last-good entity and none of the candidate's (no leaked runtime handles).
     */
    private static void runMcpAttach(Path ui, Path css) {
        writeFixture(ui, css, ENTITY_UI_A);
        PreviewApp app = new PreviewApp(CliOptions.parse(new String[]{
                "--ui", ui.toString(), "--css", css.toString(), "--mcp"}));
        launch(new ApplicationAdapter() {
            private int frame;
            private MarkupRuntimeSource committed;
            private MarkupRuntimeSource reinstalled;

            @Override public void create() {
                app.create();
            }

            @Override public void render() {
                try {
                    frame++;
                    switch (frame) {
                        case 1 -> {
                            app.render();
                            committed = app.mcp().runtimeSource();
                            assertNotNull(committed, "attachRuntime returns a live owner");
                            assertEquals(List.of("user"), committed.registeredEntities(),
                                    "the first build registers its declared entity");
                            assertFrameHasOnly("user", app);
                        }
                        case 2 -> {
                            // The candidate shares the entity id with the live registration and
                            // then fails during commit (duplicate within the candidate): the old
                            // ids must be removed, the candidate retried, and the last-good
                            // registration reinstalled before the failure is reported.
                            writeUi(ui, COLLIDING_BAD_UI);
                            app.rebuild();
                            assertTrue(app.errorOverlayVisible(),
                                    "overlay visible after a runtime attach failure");
                            reinstalled = app.mcp().runtimeSource();
                            assertNotSame(committed, reinstalled,
                                    "a colliding candidate removes the old ids, so the last-good "
                                            + "registration is reinstalled as a fresh owner");
                            assertEquals(List.of("user"), reinstalled.registeredEntities(),
                                    "the last-good registration is reinstalled after the failure");
                            app.render();
                            assertFrameHasOnly("user", app);
                        }
                        case 3 -> {
                            // The candidate's entities are disjoint from the live ones and the
                            // candidate then fails during preflight: the live owner must be
                            // preserved untouched.
                            writeUi(ui, DISJOINT_BAD_UI);
                            app.rebuild();
                            assertTrue(app.errorOverlayVisible(),
                                    "overlay visible after a disjoint attach failure");
                            assertSame(reinstalled, app.mcp().runtimeSource(),
                                    "a disjoint candidate failure preserves the live owner");
                            assertEquals(List.of("user"), reinstalled.registeredEntities(),
                                    "the preserved registration is unchanged");
                            app.render();
                            assertFrameHasOnly("user", app);
                        }
                        case 4 -> {
                            // Recovery: a good entity build commits a fresh owner.
                            writeUi(ui, ENTITY_UI_A);
                            app.rebuild();
                            assertFalse(app.errorOverlayVisible(), "overlay hidden after recovery");
                            MarkupRuntimeSource recovered = app.mcp().runtimeSource();
                            assertNotSame(reinstalled, recovered,
                                    "recovery commits a fresh runtime owner");
                            assertEquals(List.of("user"), recovered.registeredEntities(),
                                    "the recovered scene registers its declared entity");
                            app.render();
                            assertFrameHasOnly("user", app);
                            Gdx.app.exit();
                        }
                        default -> {
                        }
                    }
                } catch (Throwable thrown) {
                    fail(messageOf(thrown));
                }
            }

            @Override public void dispose() {
                app.dispose();
            }
        });
    }

    /** Asserts the latest runtime frame carries exactly the last-good entity and none of the
     * candidate's or previously rolled-back entities. */
    private static void assertFrameHasOnly(String expected, PreviewApp app) {
        var frame = app.mcp().runtime().latestFrame().orElseThrow();
        assertTrue(frame.entity(EntityId.of(expected)).isPresent(),
                "the runtime frame still carries \"" + expected + "\"");
        assertTrue(frame.entity(EntityId.of("ghost")).isEmpty(),
                "no candidate entity leaked into the runtime frame");
        assertTrue(frame.entity(EntityId.of("other")).isEmpty(),
                "no rolled-back candidate entity leaked into the runtime frame");
    }

    /**
     * A stage-swap failure injected into the rebuild: the candidate (skin + actors) must be
     * rolled back, the exact old stage restored (last-good actors live, old skin undisposed,
     * overlay on top), and a later recovery must commit a fresh scene.
     */
    private static void runSwapFailure(Path ui, Path css) {
        writeFixture(ui, css, GOOD_UI_A);
        PreviewApp app = new PreviewApp(CliOptions.parse(new String[]{
                "--ui", ui.toString(), "--css", css.toString()}));
        launch(new ApplicationAdapter() {
            private int frame;
            private Skin good;

            @Override public void create() {
                app.create();
            }

            @Override public void render() {
                try {
                    frame++;
                    switch (frame) {
                        case 1 -> {
                            app.render();
                            good = app.skin();
                            assertNotNull(good, "the first build commits a skin");
                            assertTrue(app.stageContains("title"),
                                    "the first build stages its actors");
                        }
                        case 2 -> {
                            // Inject a stage-swap failure; the rebuild must roll the stage back
                            // to the exact old scene and keep the old skin and actors live.
                            app.stageSwap = root -> {
                                throw new IllegalStateException("injected-stage-failure");
                            };
                            writeUi(ui, GOOD_UI_B);
                            app.rebuild();
                            assertTrue(app.errorOverlayVisible(),
                                    "overlay visible after a stage-swap failure");
                            assertSame(good, app.skin(),
                                    "the old skin is retained after a stage-swap failure");
                            assertTrue(app.stageContains("title"), "old actors restored");
                            assertFalse(app.stageContains("subtitle"),
                                    "candidate actors are not staged");
                        }
                        case 3 -> {
                            app.render(); // the restored last-good scene still renders
                        }
                        case 4 -> {
                            // Recovery: restore the default swap and rebuild.
                            app.stageSwap = app::defaultStageSwap;
                            writeUi(ui, GOOD_UI_B);
                            app.rebuild();
                            assertFalse(app.errorOverlayVisible(), "overlay hidden after recovery");
                            assertNotSame(good, app.skin(), "recovery commits a fresh skin");
                            assertTrue(app.stageContains("subtitle"), "recovered actors staged");
                            app.render();
                            Gdx.app.exit();
                        }
                        default -> {
                        }
                    }
                } catch (Throwable thrown) {
                    fail(messageOf(thrown));
                }
            }

            @Override public void dispose() {
                app.dispose();
            }
        });
    }

    /**
     * A stage-swap failure after a colliding runtime acquire: the candidate registration must
     * be closed and the last-good registration reinstated (fields never advanced), the old
     * stage and skin retained, and a later recovery must commit a fresh scene and owner.
     */
    private static void runMcpSwapFailure(Path ui, Path css) {
        writeFixture(ui, css, ENTITY_UI_A);
        PreviewApp app = new PreviewApp(CliOptions.parse(new String[]{
                "--ui", ui.toString(), "--css", css.toString(), "--mcp"}));
        launch(new ApplicationAdapter() {
            private int frame;
            private MarkupRuntimeSource committed;
            private MarkupRuntimeSource reinstated;
            private Skin good;

            @Override public void create() {
                app.create();
            }

            @Override public void render() {
                try {
                    frame++;
                    switch (frame) {
                        case 1 -> {
                            app.render();
                            committed = app.mcp().runtimeSource();
                            good = app.skin();
                            assertNotNull(committed, "attachRuntime returns a live owner");
                            assertEquals(List.of("user"), committed.registeredEntities());
                            assertFrameHasOnly("user", app);
                        }
                        case 2 -> {
                            // A colliding acquire succeeds (old ids removed, candidate
                            // registered), then the stage swap fails: the candidate registration
                            // must be closed and the last-good registration reinstated; the
                            // committed owner, retained document, skin, and stage must not
                            // advance.
                            app.stageSwap = root -> {
                                throw new IllegalStateException("injected-stage-failure");
                            };
                            writeUi(ui, ENTITY_UI_A);
                            app.rebuild();
                            assertTrue(app.errorOverlayVisible(),
                                    "overlay visible after a stage-swap failure");
                            reinstated = app.mcp().runtimeSource();
                            assertNotNull(reinstated,
                                    "the last-good registration is reinstated");
                            assertNotSame(committed, reinstated,
                                    "the colliding acquire removed the old ids, so the last-good "
                                            + "registration is reinstated as a fresh owner");
                            assertEquals(List.of("user"), reinstated.registeredEntities());
                            assertFalse(app.mcp().runtimeLost(),
                                    "restore succeeded; the preview is not terminal");
                            assertSame(good, app.skin(), "the old skin is retained");
                            assertTrue(app.stageContains("user"), "old actors restored");
                            app.render();
                            assertFrameHasOnly("user", app);
                        }
                        case 3 -> {
                            // Recovery: restore the default swap and rebuild.
                            app.stageSwap = app::defaultStageSwap;
                            writeUi(ui, ENTITY_UI_A);
                            app.rebuild();
                            assertFalse(app.errorOverlayVisible(), "overlay hidden after recovery");
                            MarkupRuntimeSource recovered = app.mcp().runtimeSource();
                            assertNotSame(reinstated, recovered,
                                    "recovery commits a fresh runtime owner");
                            assertEquals(List.of("user"), recovered.registeredEntities());
                            assertNotSame(good, app.skin(), "recovery commits a fresh skin");
                            assertTrue(app.stageContains("user"), "recovered actors staged");
                            app.render();
                            assertFrameHasOnly("user", app);
                            Gdx.app.exit();
                        }
                        default -> {
                        }
                    }
                } catch (Throwable thrown) {
                    fail(messageOf(thrown));
                }
            }

            @Override public void dispose() {
                app.dispose();
            }
        });
    }

    /**
     * A retirement failure during runtime commit: closing the old registration throws after a
     * non-colliding candidate acquired successfully. The old owner's close is multi-handle, so
     * after it throws integrity is unknown: the preview must enter the terminal state (MCP
     * closed, rebuilds stop, typed TERMINAL status carrying the retirement failure and the
     * reinstatement cause) rather than claim the old registration intact. The reinstatement
     * attempt collides with the still-live old registration, whose duplicate is preserved in
     * the terminal message.
     */
    private static void runRetireFailure(Path ui, Path css) {
        writeFixture(ui, css, ENTITY_UI_A);
        PreviewApp app = new PreviewApp(CliOptions.parse(new String[]{
                "--ui", ui.toString(), "--css", css.toString(), "--mcp"}));
        launch(new ApplicationAdapter() {
            private int frame;
            private Skin good;

            @Override public void create() {
                app.create();
            }

            @Override public void render() {
                try {
                    frame++;
                    if (frame == 1) {
                        app.render();
                        good = app.skin();
                        assertNotNull(good, "the first build commits a skin");
                        assertNotNull(app.mcp().runtimeSource(), "attachRuntime returns a live owner");
                        assertEquals(List.of("user"),
                                app.mcp().runtimeSource().registeredEntities());
                        assertFrameHasOnly("user", app);
                    } else if (frame == 2) {
                        // The disjoint candidate acquires cleanly; the retirement of the old
                        // registration throws during commit. The candidate is closed (cleanup)
                        // and reinstatement attempted (it collides with the still-live old, a
                        // partial-close-unsafe duplicate) — both suppressed onto the primary
                        // retirement failure — and the preview enters the terminal state.
                        app.mcp().retirementCloser = retired -> {
                            throw new IllegalStateException("injected-retire-failure");
                        };
                        writeUi(ui, DISJOINT_GOOD_UI);
                        app.rebuild();
                        assertTrue(app.errorOverlayVisible(), "the terminal overlay is visible");
                        assertNull(app.mcp(), "the MCP session is closed in the terminal state");
                        assertSame(good, app.skin(), "the last-good skin stays on screen");
                        assertTrue(app.stageContains("user"),
                                "the last-good scene stays on screen");
                        // Rebuilds stop: a further rebuild is a no-op and changes nothing.
                        writeUi(ui, ENTITY_UI_A);
                        app.rebuild();
                        assertSame(good, app.skin(), "rebuilds stop in the terminal state");
                        assertTrue(app.errorOverlayVisible(), "the terminal overlay persists");
                    }
                } catch (Throwable thrown) {
                    fail(messageOf(thrown));
                }
            }

            @Override public void dispose() {
                app.dispose();
            }
        });
    }

    /**
     * Exceptional cleanup aggregation: the stage swap throws, then the candidate close throws,
     * and the last-good reinstatement also throws. Every cleanup failure must be suppressed
     * onto the primary stage failure and the preview must enter the terminal state with the
     * restore cause visible in the typed TERMINAL status; the last-good scene stays on screen
     * and rebuilds stop.
     */
    private static void runMcpCleanupFailure(Path ui, Path css) {
        writeFixture(ui, css, ENTITY_UI_A);
        PreviewApp app = new PreviewApp(CliOptions.parse(new String[]{
                "--ui", ui.toString(), "--css", css.toString(), "--mcp"}));
        launch(new ApplicationAdapter() {
            private int frame;
            private Skin good;

            @Override public void create() {
                app.create();
            }

            @Override public void render() {
                try {
                    frame++;
                    if (frame == 1) {
                        app.render();
                        good = app.skin();
                        assertNotNull(good, "the first build commits a skin");
                        assertNotNull(app.mcp().runtimeSource(), "attachRuntime returns a live owner");
                        assertEquals(List.of("user"),
                                app.mcp().runtimeSource().registeredEntities());
                        assertFrameHasOnly("user", app);
                    } else if (frame == 2) {
                        // A colliding acquire closes the old ids and registers the candidate,
                        // then the stage swap throws; rollback's candidate close throws and the
                        // reinstatement of the last-good registration also throws. Neither
                        // cleanup failure may skip the other; both are suppressed onto the
                        // primary stage failure and the preview enters the terminal state.
                        app.stageSwap = root -> {
                            throw new IllegalStateException("injected-stage-failure");
                        };
                        app.mcp().candidateCloser = candidate -> {
                            throw new IllegalStateException("injected-candidate-close-failure");
                        };
                        app.mcp().lastGoodRegistrar = (runtime, document, built, session) -> {
                            throw new IllegalStateException("injected-restore-failure");
                        };
                        writeUi(ui, ENTITY_UI_A);
                        app.rebuild();
                        assertTrue(app.errorOverlayVisible(), "the terminal overlay is visible");
                        assertNull(app.mcp(), "the MCP session is closed in the terminal state");
                        assertSame(good, app.skin(), "the last-good skin stays on screen");
                        assertTrue(app.stageContains("user"),
                                "the last-good scene stays on screen");
                        // Rebuilds stop: a further rebuild is a no-op and changes nothing.
                        writeUi(ui, ENTITY_UI_A);
                        app.rebuild();
                        assertSame(good, app.skin(), "rebuilds stop in the terminal state");
                        assertTrue(app.errorOverlayVisible(), "the terminal overlay persists");
                    }
                } catch (Throwable thrown) {
                    fail(messageOf(thrown));
                }
            }

            @Override public void dispose() {
                app.dispose();
            }
        });
    }

    /**
     * A would-be restore failure: after a colliding candidate fails on retry, reinstating the
     * last-good registration also fails. The preview must enter the terminal state — the
     * runtime is lost, the MCP session is closed, rebuilds stop, and a typed {@code TERMINAL}
     * status is published — rather than continuing as a recoverable overlay claiming last-good.
     */
    private static void runRestoreFailure(Path ui, Path css) {
        writeFixture(ui, css, ENTITY_UI_A);
        PreviewApp app = new PreviewApp(CliOptions.parse(new String[]{
                "--ui", ui.toString(), "--css", css.toString(), "--mcp"}));
        launch(new ApplicationAdapter() {
            private int frame;
            private Skin good;

            @Override public void create() {
                app.create();
            }

            @Override public void render() {
                try {
                    frame++;
                    if (frame == 1) {
                        app.render();
                        good = app.skin();
                        assertNotNull(good, "the first build commits a skin");
                        assertNotNull(app.mcp().runtimeSource(), "attachRuntime returns a live owner");
                        assertEquals(List.of("user"),
                                app.mcp().runtimeSource().registeredEntities());
                        assertFrameHasOnly("user", app);
                    } else if (frame == 2) {
                        // The colliding candidate fails on retry AND reinstating the last-good
                        // registration fails: the preview must stop, not claim last-good.
                        app.mcp().lastGoodRegistrar = (runtime, document, built, session) -> {
                            throw new IllegalStateException("injected-restore-failure");
                        };
                        writeUi(ui, COLLIDING_BAD_UI);
                        app.rebuild();
                        assertTrue(app.errorOverlayVisible(), "the terminal overlay is visible");
                        assertNull(app.mcp(), "the MCP session is closed in the terminal state");
                        assertSame(good, app.skin(), "the last-good skin stays on screen");
                        assertTrue(app.stageContains("user"),
                                "the last-good scene stays on screen");
                        // Rebuilds stop: a further rebuild is a no-op and changes nothing.
                        writeUi(ui, ENTITY_UI_A);
                        app.rebuild();
                        assertSame(good, app.skin(), "rebuilds stop in the terminal state");
                        assertTrue(app.errorOverlayVisible(), "the terminal overlay persists");
                    }
                } catch (Throwable thrown) {
                    fail(messageOf(thrown));
                }
            }

            @Override public void dispose() {
                app.dispose();
            }
        });
    }

    /**
     * Terminal cleanup with injected component-close failures: entering the terminal state
     * closes the MCP session, whose best-effort close must attempt EVERY owned component
     * (aggregating the failures), detach the ownership fields, and never re-close on a second
     * call. The close failures (including a partially-closed runtime owner) are appended to the
     * already-published bounded TERMINAL status instead of throwing out of the render loop; the
     * watcher/rebuilds stop and the last-good scene stays on screen.
     */
    private static void runMcpCloseFailure(Path ui, Path css) {
        writeFixture(ui, css, ENTITY_UI_A);
        PreviewApp app = new PreviewApp(CliOptions.parse(new String[]{
                "--ui", ui.toString(), "--css", css.toString(), "--mcp"}));
        launch(new ApplicationAdapter() {
            private int frame;
            private Skin good;
            private PreviewMcp mcpRef;
            private final AtomicInteger closeAttempts = new AtomicInteger();

            @Override public void create() {
                app.create();
            }

            @Override public void render() {
                try {
                    frame++;
                    if (frame == 1) {
                        app.render();
                        good = app.skin();
                        mcpRef = app.mcp();
                        assertNotNull(mcpRef, "the preview runs with --mcp");
                        assertNotNull(mcpRef.runtimeSource(), "attachRuntime returns a live owner");
                        assertEquals(List.of("user"), mcpRef.runtimeSource().registeredEntities());
                        assertFrameHasOnly("user", app);
                    } else if (frame == 2) {
                        // Make the reinstatement fail (terminal) and make the terminal cleanup
                        // close of the runtime owner (partially closed by the failed acquire) and
                        // the MCP server throw. Every component close must still be attempted,
                        // the failures aggregated, the ownership detached, a second close must be
                        // a no-op, and the cleanup causes must be appended to the TERMINAL status
                        // instead of escaping the render loop.
                        mcpRef.lastGoodRegistrar = (runtime, document, built, session) -> {
                            throw new IllegalStateException("injected-restore-failure");
                        };
                        mcpRef.componentCloser = component -> {
                            closeAttempts.incrementAndGet();
                            if (component == mcpRef.runtimeSource()) {
                                throw new IllegalStateException("injected-runtime-owner-close-failure");
                            }
                            if (component instanceof HarnessMcpServer) {
                                throw new IllegalStateException("injected-server-close-failure");
                            }
                            component.close(); // real close for the remaining owned components
                        };
                        writeUi(ui, COLLIDING_BAD_UI);
                        app.rebuild(); // terminal; enterTerminal closes mcp with injected failures
                        assertTrue(app.errorOverlayVisible(), "the terminal overlay is visible");
                        assertNull(app.mcp(), "the MCP session is detached in the terminal state");
                        // A second close is idempotent: it must not re-close (or re-throw).
                        mcpRef.close();
                        assertEquals(12, closeAttempts.get(),
                                "every owned component close was attempted exactly once");
                        assertSame(good, app.skin(), "the last-good skin stays on screen");
                        assertTrue(app.stageContains("user"), "the last-good scene stays on screen");
                        // Rebuilds stop: a further rebuild is a no-op and changes nothing.
                        writeUi(ui, ENTITY_UI_A);
                        app.rebuild();
                        assertSame(good, app.skin(), "rebuilds stop in the terminal state");
                        assertTrue(app.errorOverlayVisible(), "the terminal overlay persists");
                        app.render();
                        Gdx.app.exit();
                    }
                } catch (Throwable thrown) {
                    fail(messageOf(thrown));
                }
            }

            @Override public void dispose() {
                app.dispose();
            }
        });
    }

    /**
     * A nested and cyclic cause chain in the terminal cleanup: the injected runtime-owner close
     * failure is wrapped by {@code closeOwned} as the cause of a generic "failed to close …"
     * exception, and the injected failure itself chains root → mid → deepest → root (a cycle).
     * The typed TERMINAL status must carry the deepest injected cause text through every
     * wrapper level (not only the generic wrappers), must terminate (identity-based cycle
     * detection) instead of overflowing the stack, and must stay bounded; the terminal state
     * still renders and the last-good scene stays on screen.
     */
    private static void runMcpCauseChain(Path ui, Path css) {
        writeFixture(ui, css, ENTITY_UI_A);
        PreviewApp app = new PreviewApp(CliOptions.parse(new String[]{
                "--ui", ui.toString(), "--css", css.toString(), "--mcp"}));
        launch(new ApplicationAdapter() {
            private int frame;
            private Skin good;
            private PreviewMcp mcpRef;

            @Override public void create() {
                app.create();
            }

            @Override public void render() {
                try {
                    frame++;
                    if (frame == 1) {
                        app.render();
                        good = app.skin();
                        mcpRef = app.mcp();
                        assertNotNull(mcpRef, "the preview runs with --mcp");
                        assertNotNull(mcpRef.runtimeSource(), "attachRuntime returns a live owner");
                        assertEquals(List.of("user"), mcpRef.runtimeSource().registeredEntities());
                        assertFrameHasOnly("user", app);
                    } else if (frame == 2) {
                        // A nested cause chain that cycles back on itself: root → mid → deepest
                        // → root. closeOwned wraps the injected root as the cause of "failed to
                        // close runtime registration"; only a getCause()-traversing, cycle-safe,
                        // bounded terminal message can surface "injected-deepest-cause".
                        IllegalStateException deepest =
                                new IllegalStateException("injected-deepest-cause");
                        IllegalStateException mid =
                                new IllegalStateException("injected-mid-cause", deepest);
                        IllegalStateException root =
                                new IllegalStateException("injected-root-cause", mid);
                        deepest.initCause(root); // cycle: root -> mid -> deepest -> root
                        mcpRef.lastGoodRegistrar = (runtime, document, built, session) -> {
                            throw new IllegalStateException("injected-restore-failure");
                        };
                        mcpRef.componentCloser = component -> {
                            if (component == mcpRef.runtimeSource()) {
                                throw root;
                            }
                            component.close(); // real close for the remaining owned components
                        };
                        writeUi(ui, COLLIDING_BAD_UI);
                        app.rebuild(); // terminal; enterTerminal closes mcp with the cyclic root
                        assertTrue(app.errorOverlayVisible(), "the terminal overlay is visible");
                        assertNull(app.mcp(), "the MCP session is detached in the terminal state");
                        assertSame(good, app.skin(), "the last-good skin stays on screen");
                        assertTrue(app.stageContains("user"),
                                "the last-good scene stays on screen");
                        // The terminal state still renders: a cyclic cause traversal must not
                        // overflow the stack or emit unbounded output.
                        app.render();
                        Gdx.app.exit();
                    }
                } catch (Throwable thrown) {
                    fail(messageOf(thrown));
                }
            }

            @Override public void dispose() {
                app.dispose();
            }
        });
    }

    /**
     * Staged constructor ownership: when the MCP server (or the artifact publisher) fails to
     * initialize, every component already acquired — publisher, server, session, scheduler,
     * clock, runtime, harness, fence, capture, waits — must be closed best-effort and any
     * cleanup failure aggregated (suppressed) onto the primary initialization failure. The
     * injected closer records every close, so the child can prove no acquired resource leaks
     * and that a successful close after a failed server open still removes the publisher's
     * session directory.
     */
    private static void runMcpInitFailure(Path ui, Path css) {
        writeFixture(ui, css, ENTITY_UI_A);
        launch(new ApplicationAdapter() {
            private int frame;
            private Stage probe;

            @Override public void create() {
                probe = new Stage();
            }

            @Override public void render() {
                try {
                    frame++;
                    if (frame == 1) {
                        java.util.concurrent.atomic.AtomicReference<TmpDirArtifactPublisher>
                                created = new java.util.concurrent.atomic.AtomicReference<>();
                        java.util.concurrent.atomic.AtomicInteger closes =
                                new java.util.concurrent.atomic.AtomicInteger();
                        // Server-open failure after the publisher was acquired: the constructor
                        // must close every acquired component (including the publisher, whose
                        // session directory is deleted) and rethrow the primary failure with
                        // the cleanup aggregated. Acquired before the server: clock, scheduler,
                        // session, fence, capture, waits, runtime, harness, protocol executor,
                        // artifacts = 10 components.
                        RuntimeException serverFailure = assertThrows(RuntimeException.class,
                                () -> new PreviewMcp(probe,
                                        () -> {
                                            TmpDirArtifactPublisher publisher =
                                                    new TmpDirArtifactPublisher();
                                            created.set(publisher);
                                            return publisher;
                                        },
                                        (protocol, artifacts) -> {
                                            throw new IllegalStateException(
                                                    "injected-server-open-failure");
                                        },
                                        AgentRuntime::start,
                                        component -> {
                                            closes.incrementAndGet();
                                            component.close();
                                        }));
                        assertTrue(serverFailure.getMessage() != null
                                        && serverFailure.getMessage()
                                                .contains("injected-server-open-failure"),
                                "the primary server-open failure propagates: "
                                        + serverFailure.getMessage());
                        assertNotNull(created.get(), "the publisher was acquired");
                        assertFalse(Files.exists(created.get().sessionDir()),
                                "the acquired publisher's session directory is removed "
                                        + "during staged constructor cleanup");
                        assertTrue(closes.get() >= 10,
                                "every acquired component close was attempted: " + closes.get());
                        // Artifact-publisher failure: nothing is acquired past the publisher,
                        // and the failure propagates (the publisher constructor itself removes
                        // its directory — proven in the unit tests).
                        RuntimeException publisherFailure = assertThrows(RuntimeException.class,
                                () -> new PreviewMcp(probe,
                                        () -> {
                                            throw new IllegalStateException(
                                                    "injected-artifact-init-failure");
                                        },
                                        (protocol, artifacts) -> {
                                            throw new AssertionError("server must not open");
                                        },
                                        AgentRuntime::start,
                                        component -> {
                                            closes.incrementAndGet();
                                            component.close();
                                        }));
                        assertTrue(publisherFailure.getMessage() != null
                                        && publisherFailure.getMessage()
                                                .contains("injected-artifact-init-failure"),
                                "the artifact-init failure propagates: "
                                        + publisherFailure.getMessage());
                        // Partial runtime.start failure: the runtime was acquired BEFORE
                        // start, so the rollback must close it (strict reverse order).
                        java.util.concurrent.atomic.AtomicReference<AgentRuntime> started =
                                new java.util.concurrent.atomic.AtomicReference<>();
                        RuntimeException startFailure = assertThrows(RuntimeException.class,
                                () -> new PreviewMcp(probe,
                                        () -> {
                                            throw new AssertionError("publisher must not open");
                                        },
                                        (protocol, artifacts) -> {
                                            throw new AssertionError("server must not open");
                                        },
                                        runtime -> {
                                            started.set(runtime);
                                            throw new IllegalStateException(
                                                    "injected-runtime-start-failure");
                                        },
                                        component -> {
                                            closes.incrementAndGet();
                                            component.close();
                                        }));
                        assertTrue(startFailure.getMessage() != null
                                        && startFailure.getMessage()
                                                .contains("injected-runtime-start-failure"),
                                "the partial runtime.start failure propagates: "
                                        + startFailure.getMessage());
                        assertNotNull(started.get(), "the runtime was created before start");
                        assertEquals(io.github.teemuki8.libgdx.agent.runtime.core
                                        .RuntimeStatus.CLOSED, started.get().status(),
                                "the partially started runtime is closed by the rollback");
                        Gdx.app.exit();
                    }
                } catch (Throwable thrown) {
                    fail(messageOf(thrown));
                }
            }

            @Override public void dispose() {
                if (probe != null) {
                    probe.dispose();
                }
            }
        });
    }

    /**
     * Success-close regression: the normal {@code PreviewMcp.close()} must close EVERY
     * acquired component (runtime registration, MCP server, artifact publisher, protocol
     * executor, scene2d harness, agent runtime, wait engine, screen capture, frame fence,
     * session, scheduler, clock) exactly once, continue aggregating after a close failure,
     * detach the ownership fields, and make a second close a no-op.
     */
    private static void runMcpCloseAll(Path ui, Path css) {
        writeFixture(ui, css, ENTITY_UI_A);
        PreviewApp app = new PreviewApp(CliOptions.parse(new String[]{
                "--ui", ui.toString(), "--css", css.toString(), "--mcp"}));
        launch(new ApplicationAdapter() {
            private int frame;
            private PreviewMcp mcpRef;
            private final AtomicInteger closeAttempts = new AtomicInteger();

            @Override public void create() {
                app.create();
            }

            @Override public void render() {
                try {
                    frame++;
                    if (frame == 1) {
                        app.render();
                        mcpRef = app.mcp();
                        assertNotNull(mcpRef, "the preview runs with --mcp");
                        assertNotNull(mcpRef.runtimeSource(), "attachRuntime returns a live owner");
                        assertEquals(List.of("user"), mcpRef.runtimeSource().registeredEntities());
                        assertFrameHasOnly("user", app);
                    } else if (frame == 2) {
                        // Count every component close; make the agent runtime close fail to
                        // prove aggregation continues after the failure (the later components —
                        // wait engine, screen capture, frame fence, session, scheduler, clock —
                        // are still attempted and closed).
                        mcpRef.componentCloser = component -> {
                            closeAttempts.incrementAndGet();
                            if (component == mcpRef.runtime()) {
                                throw new IllegalStateException("injected-runtime-close-failure");
                            }
                            component.close(); // real close for every other owned component
                        };
                        RuntimeException closeFailure = assertThrows(RuntimeException.class,
                                mcpRef::close);
                        assertTrue(closeFailure.getMessage().contains("failed to close agent runtime"),
                                "the aggregated close failure names the failing component; got: "
                                        + closeFailure.getMessage());
                        assertEquals(12, closeAttempts.get(),
                                "every acquired component close was attempted exactly once");
                        // Second close is idempotent: no re-close, no rethrow.
                        mcpRef.close();
                        assertEquals(12, closeAttempts.get(), "a second close re-closes nothing");
                        assertNull(mcpRef.runtimeSource(),
                                "ownership fields are detached after close");
                        Gdx.app.exit();
                    }
                } catch (Throwable thrown) {
                    fail(messageOf(thrown));
                }
            }

            @Override public void dispose() {
                app.dispose();
            }
        });
    }

    private static void writeFixture(Path ui, Path css, String uiContent) {
        writeUi(ui, uiContent);
        try {
            Files.writeString(css, EMPTY_CSS, StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            fail(messageOf(failure));
        }
    }

    private static void writeUi(Path ui, String content) {
        try {
            Files.writeString(ui, content, StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            fail(messageOf(failure));
        }
    }

    private static String messageOf(Throwable thrown) {
        return thrown.getMessage() == null ? thrown.getClass().getSimpleName()
                : thrown.getMessage();
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
