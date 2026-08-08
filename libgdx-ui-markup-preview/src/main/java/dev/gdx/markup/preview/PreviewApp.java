package dev.gdx.markup.preview;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import dev.gdx.markup.core.BuiltUi;
import dev.gdx.markup.core.DefaultSkin;
import dev.gdx.markup.core.MarkupBuilder;
import dev.gdx.markup.core.MarkupDocument;
import dev.gdx.markup.core.MarkupException;
import dev.gdx.markup.core.MarkupParser;
import dev.gdx.markup.core.NoopSink;
import dev.gdx.markup.core.SemanticSink;
import dev.gdx.markup.core.style.CssDocument;
import dev.gdx.markup.core.style.CssParser;
import dev.gdx.markup.runtime.MarkupRuntimeSource;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * LWJGL3 preview application: builds a Scene2D UI from {@code --ui} markup plus {@code --css}
 * on the GL thread, hot-reloads on file changes (300 ms debounce), renders typed build errors
 * as a red overlay with a bounded {@code markup-status: {...}} stdout line, and supports CI
 * ({@code --frames}/{@code --screenshot}/{@code --exit}) and agent ({@code --mcp}) modes.
 */
public final class PreviewApp extends ApplicationAdapter implements AutoCloseable {
    /** Stable harness MCP session id exposed by {@code --mcp}. */
    public static final String SESSION_ID = "markup-preview";

    private static final long RELOAD_DEBOUNCE_NANOS = 300_000_000L;
    private static final Color ERROR_TEXT = Color.valueOf("ff6b6bff");
    /**
     * Fixed opaque clear color, applied before every draw. Matches the default skin's
     * background so screenshots keep the intended UI backdrop; being a constant makes every
     * frame (and therefore every screenshot) deterministic regardless of prior back-buffer
     * contents.
     */
    static final Color CLEAR_COLOR = Color.valueOf("172033ff");

    private final CliOptions options;
    private Stage stage;
    private Skin skin;
    private PreviewMcp mcp;
    private Label errorLabel;
    private Label.LabelStyle errorStyle;
    private Thread watcher;
    private volatile boolean reloadPending;
    private volatile long reloadRequestedNanos;
    private int renderedFrames;
    private boolean screenshotTaken;
    private boolean closed;

    /**
     * Package-visible for render-thread tests; production entry is {@link #main(String[])}.
     */
    PreviewApp(CliOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /** Entry point; usage failures exit 1 before any GL context is created. */
    public static void main(String[] args) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (IllegalArgumentException failure) {
            System.err.println(failure.getMessage());
            System.err.print(CliOptions.usage());
            System.exit(1);
            return;
        }
        com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration config =
                new com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration();
        config.setTitle("gdx-ui-markup preview");
        config.setWindowedMode(1280, 720);
        config.disableAudio(true);
        new com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application(
                new PreviewApp(options), config);
    }

    @Override public void create() {
        stage = new Stage(new ScreenViewport());
        Gdx.input.setInputProcessor(stage);
        stage.getRoot().addListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.ESCAPE) {
                    Gdx.app.exit();
                    return true;
                }
                return false;
            }
        });
        errorStyle = new Label.LabelStyle();
        errorStyle.font = new BitmapFont();
        errorStyle.fontColor = ERROR_TEXT;
        errorLabel = new Label("", errorStyle);
        errorLabel.setWrap(true);
        errorLabel.setBounds(16, 16, 1248, 120);
        errorLabel.setVisible(false);
        // The overlay is staged up front, before the first rebuild, so a failed initial build
        // can render it even though no scene was ever committed. Successful rebuilds clear the
        // stage and re-add the overlay last (on top of the scene).
        stage.getRoot().addActor(errorLabel);

        if (options.mcp()) {
            mcp = new PreviewMcp(stage);
        }
        rebuild();
        startWatcher();
    }

    /**
     * Rebuilds the scene from disk on the GL thread and reports one bounded status line. The
     * rebuild is transactional: everything the new scene needs (document, stylesheet, skin,
     * actor tree, runtime registration) is prepared off the live stage inside a {@link
     * Candidate}, the candidate runtime registration is attached first (preserving or
     * reinstalling the last-good registration on failure), and only then is the stage swapped
     * and the old skin disposed. A failed rebuild disposes only the candidate's resources and
     * keeps the last-good skin, actors, and runtime registration live, with the typed error
     * overlay staged on top. Package-visible so render-thread test children can drive
     * rebuilds deterministically.
     */
    void rebuild() {
        Candidate candidate = null;
        try {
            MarkupDocument document = new MarkupParser().parse(options.ui());
            CssDocument css = new CssParser().parse(options.css());
            candidate = new Candidate(document, css, createSkin());
            BuiltUi built = MarkupBuilder.build(document, css, candidate.skin(), sink());
            candidate.adoptBuilt(built);
            // The ui root group must cover the viewport or harness actionability (parent
            // intersection and Group.hit) sees a zero-sized parent and rejects every actor.
            built.root().setSize(stage.getViewport().getWorldWidth(),
                    stage.getViewport().getWorldHeight());
            if (mcp != null) {
                candidate.adoptRuntime(mcp.attachRuntime(document, built));
            }
            commit(candidate);
            candidate = null; // ownership transferred; never dispose the committed scene
            status(MarkupStatus.ok(built.actors().size()));
        } catch (MarkupException failure) {
            publishFailure(failure.formatted(), MarkupStatus.error(failure));
            if (candidate != null) {
                candidate.close();
            }
            if (options.exit()) {
                System.out.flush();
                System.err.flush();
                System.exit(2);
            }
        } catch (IOException failure) {
            publishFailure(failure.getMessage(), MarkupStatus.error(failure.getMessage()));
            if (candidate != null) {
                candidate.close();
            }
            if (options.exit()) {
                System.out.flush();
                System.err.flush();
                System.exit(2);
            }
        } catch (RuntimeException failure) {
            // Runtime-attach failures (for example the agent runtime's DUPLICATE_ENTITY) and any
            // other unexpected runtime failure are candidate failures: keep the last-good scene
            // live, dispose only the candidate, and publish a typed generic error.
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            publishFailure(message, MarkupStatus.error(message));
            if (candidate != null) {
                candidate.close();
            }
            if (options.exit()) {
                System.out.flush();
                System.err.flush();
                System.exit(2);
            }
        }
    }

    private Skin createSkin() {
        return options.skin() == null ? DefaultSkin.create()
                : new Skin(Gdx.files.absolute(options.skin().toString()));
    }

    /**
     * Stage swap then old dispose: the last-good actors stay staged and the last-good skin
     * stays live until this exact point, the candidate's runtime registration was already
     * retired into {@link PreviewMcp} by {@code attachRuntime}, and the old skin is disposed
     * exactly once after the swap.
     */
    private void commit(Candidate candidate) {
        stage.getRoot().clearChildren();
        stage.getRoot().addActor(candidate.built().root());
        stage.getRoot().addActor(errorLabel);
        errorLabel.setVisible(false);
        Skin previous = skin;
        skin = candidate.skin();
        if (previous != null) {
            previous.dispose();
        }
    }

    /** Stages the typed error overlay and publishes one bounded status line. */
    private void publishFailure(String text, MarkupStatus status) {
        errorLabel.setText(text);
        errorLabel.setVisible(true);
        status(status);
    }

    /**
     * One prepared rebuild. Owns every candidate resource — the parsed document, stylesheet,
     * candidate skin, built actor tree, and (once attached) the runtime registration — until
     * the rebuild is committed or disposed. {@link #close()} releases exactly the candidate's
     * resources in reverse acquisition order (runtime registration first, then the skin), so a
     * failed rebuild can never touch the last-good skin, actors, or runtime registration.
     */
    private static final class Candidate implements AutoCloseable {
        private final MarkupDocument document;
        private final CssDocument css;
        private final Skin skin;
        private BuiltUi built;
        private MarkupRuntimeSource runtime;

        Candidate(MarkupDocument document, CssDocument css, Skin skin) {
            this.document = Objects.requireNonNull(document, "document");
            this.css = Objects.requireNonNull(css, "css");
            this.skin = Objects.requireNonNull(skin, "skin");
        }

        void adoptBuilt(BuiltUi ui) {
            built = Objects.requireNonNull(ui, "ui");
        }

        void adoptRuntime(MarkupRuntimeSource source) {
            runtime = Objects.requireNonNull(source, "source");
        }

        Skin skin() {
            return skin;
        }

        BuiltUi built() {
            return built;
        }

        @Override public void close() {
            MarkupRuntimeSource pending = runtime;
            runtime = null;
            if (pending != null) {
                pending.close();
            }
            skin.dispose();
        }
    }

    /** Returns the currently committed (live) skin, or {@code null} before the first success. */
    Skin skin() {
        return skin;
    }

    /** Returns whether the typed error overlay is visible and staged in the scene. */
    boolean errorOverlayVisible() {
        return errorLabel != null && errorLabel.isVisible() && errorLabel.getStage() == stage;
    }

    /** Returns whether the staged scene contains an actor with the given id/name. */
    boolean stageContains(String actorName) {
        return stage != null && stage.getRoot().findActor(actorName) != null;
    }

    /** Returns the harness MCP wiring, or {@code null} when not in {@code --mcp} mode. */
    PreviewMcp mcp() {
        return mcp;
    }

    private SemanticSink sink() {
        return mcp == null ? new NoopSink() : mcp.semanticSink();
    }

    private void status(MarkupStatus status) {
        // In --mcp mode stdout carries JSON-RPC; status lines must not corrupt the framing.
        if (mcp != null) {
            System.err.println("markup-status: " + status.json());
            System.err.flush();
        } else {
            System.out.println("markup-status: " + status.json());
            System.out.flush();
        }
    }

    private void startWatcher() {
        try {
            WatchService service = FileSystems.getDefault().newWatchService();
            Set<Path> directories = new HashSet<>();
            directories.add(options.ui().toAbsolutePath().getParent());
            directories.add(options.css().toAbsolutePath().getParent());
            for (Path directory : directories) {
                directory.register(service, StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_CREATE);
            }
            watcher = Thread.ofPlatform().name("markup-watcher").daemon().start(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key;
                    try {
                        key = service.poll(1, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                    if (key == null) {
                        continue;
                    }
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Object context = event.context();
                        if (context instanceof Path name
                                && (name.toString().endsWith(".xml")
                                || name.toString().endsWith(".css"))) {
                            reloadPending = true;
                            reloadRequestedNanos = System.nanoTime();
                        }
                    }
                    key.reset();
                }
            });
        } catch (IOException failure) {
            System.err.println("markup-watcher: hot reload unavailable: " + failure.getMessage());
        }
    }

    @Override public void render() {
        if (reloadPending && System.nanoTime() - reloadRequestedNanos >= RELOAD_DEBOUNCE_NANOS) {
            reloadPending = false;
            rebuild();
        }
        if (mcp != null) {
            mcp.beforeDraw();
        } else {
            stage.act(Math.min(Gdx.graphics.getDeltaTime(), 1 / 30f));
        }
        // Deterministic frames: set the fixed clear color and clear color+depth before every
        // draw. Without this, the back buffer keeps stale pixels from prior frames (or its
        // undefined initial contents), which leaks into the screenshot as ghost history.
        Gdx.gl.glClearColor(CLEAR_COLOR.r, CLEAR_COLOR.g, CLEAR_COLOR.b, CLEAR_COLOR.a);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        stage.draw();
        if (mcp != null) {
            mcp.afterDraw();
        }
        renderedFrames++;
        if (options.frames() > 0 && renderedFrames >= options.frames() && !screenshotTaken) {
            screenshotTaken = true;
            if (options.screenshot() != null) {
                takeScreenshot();
            }
            if (options.exit()) {
                Gdx.app.exit();
            }
        }
    }

    private void takeScreenshot() {
        Pixmap pixmap = Pixmap.createFromFrameBuffer(0, 0,
                Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            // OpenGL reads the default framebuffer bottom-up, so pixmap row 0 is the scene's
            // bottom row, and the PNG encoder writes row 0 at the top. Flip the rows exactly
            // once so the PNG is top-left normalized instead of vertically inverted.
            flipVertically(pixmap);
            com.badlogic.gdx.graphics.PixmapIO.writePNG(
                    Gdx.files.absolute(options.screenshot().toAbsolutePath().toString()), pixmap);
        } finally {
            pixmap.dispose();
        }
    }

    /** Swaps each pixmap row with its vertical opposite, exactly once. */
    private static void flipVertically(Pixmap pixmap) {
        int width = pixmap.getWidth();
        int height = pixmap.getHeight();
        pixmap.setBlending(Pixmap.Blending.None);
        for (int row = 0; row < height / 2; row++) {
            int opposite = height - 1 - row;
            for (int x = 0; x < width; x++) {
                int top = pixmap.getPixel(x, row);
                pixmap.drawPixel(x, row, pixmap.getPixel(x, opposite));
                pixmap.drawPixel(x, opposite, top);
            }
        }
    }

    @Override public void resize(int width, int height) {
        if (stage != null) {
            stage.getViewport().update(width, height, true);
            if (!stage.getRoot().getChildren().isEmpty()) {
                stage.getRoot().getChildren().first().setSize(width, height);
            }
        }
    }

    @Override public void dispose() {
        close();
    }

    @Override public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (watcher != null) {
            watcher.interrupt();
        }
        if (mcp != null) {
            mcp.close();
        }
        if (stage != null) {
            stage.dispose();
        }
        if (skin != null) {
            skin.dispose();
        }
    }
}
