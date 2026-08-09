package dev.gdx.markup.preview;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import dev.gdx.markup.core.BuiltUi;
import dev.gdx.markup.core.DefaultSkin;
import dev.gdx.markup.core.FreeTypeFontManager;
import dev.gdx.markup.core.MarkupBuilder;
import dev.gdx.markup.core.MarkupDocument;
import dev.gdx.markup.core.MarkupException;
import dev.gdx.markup.core.MarkupParser;
import dev.gdx.markup.core.NoopSink;
import dev.gdx.markup.core.SemanticSink;
import dev.gdx.markup.core.style.CssDocument;
import dev.gdx.markup.core.style.CssParser;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
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
    private Skin overlaySkin;
    private float overlayRasterScale = Float.NaN;
    private float committedRasterScale = Float.NaN;
    private PreviewMcp mcp;
    private Label errorLabel;
    private Label.LabelStyle errorStyle;
    private Thread watcher;
    private volatile boolean reloadPending;
    private volatile long reloadRequestedNanos;
    private int renderedFrames;
    private boolean screenshotTaken;
    private boolean closed;
    /** Set after an unrestorable runtime failure: rebuilds stop, the watcher stops, and the
     * MCP session is closed (see {@link #enterTerminal}). */
    private boolean terminal;

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
        overlayRasterScale = rasterScaleSource.get();
        overlaySkin = createOverlaySkin(overlayRasterScale);
        errorStyle = new Label.LabelStyle();
        errorStyle.font = overlaySkin.getFont("default-font");
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
     * rebuild is transactional with explicit phases: everything the new scene needs (document,
     * stylesheet, skin, actor tree) is prepared off the live stage inside a {@link Candidate},
     * the runtime attachment is {@linkplain PreviewMcp#acquireRuntime acquired} without
     * publishing anything, the visible stage swap runs through the {@link #stageSwap} seam,
     * and only after the swap succeeds is the runtime {@linkplain PreviewMcp#commitRuntime
     * committed} and the old skin disposed. Any failure rolls back in reverse order — the
     * pending runtime attachment, the stage, and the candidate — so the last-good skin,
     * actors, and runtime registration stay live with the typed error overlay on top. An
     * unrestorable runtime enters the {@linkplain #enterTerminal terminal} state, stopping
     * rebuilds, the watcher, and the MCP session. Package-visible so render-thread test
     * children can drive rebuilds deterministically.
     */
    void rebuild() {
        if (terminal) {
            return; // the preview stopped after an unrestorable runtime failure
        }
        Candidate candidate = null;
        PreviewMcp.PendingRuntime pendingRuntime = null;
        StageState stageBefore = null;
        Skin candidateOverlaySkin = null;
        float candidateRasterScale = rasterScaleSource.get();
        try {
            if (Math.abs(candidateRasterScale - overlayRasterScale) > 0.001f) {
                candidateOverlaySkin = createOverlaySkin(candidateRasterScale);
            }
            MarkupDocument document = new MarkupParser().parse(options.ui());
            CssDocument css = new CssParser().parse(options.css());
            candidate = new Candidate(document, css, createSkin(candidateRasterScale));
            BuiltUi built = MarkupBuilder.build(document, css, candidate.skin(), sink());
            candidate.adoptBuilt(built);
            // The ui root group must cover the viewport or harness actionability (parent
            // intersection and Group.hit) sees a zero-sized parent and rejects every actor.
            built.root().setSize(stage.getViewport().getWorldWidth(),
                    stage.getViewport().getWorldHeight());
            if (mcp != null) {
                pendingRuntime = mcp.acquireRuntime(document, built);
            }
            stageBefore = captureStage();
            stageSwap.swap(candidate.built().root());
            if (mcp != null) {
                mcp.commitRuntime(pendingRuntime);
                pendingRuntime = null; // committed; PreviewMcp owns the registration
            }
            // Old dispose: only after the stage swap and runtime commit succeeded.
            Skin newSkin = candidate.skin();
            candidate = null; // ownership transferred; never dispose the committed skin
            Skin previous = skin;
            skin = newSkin;
            committedRasterScale = candidateRasterScale;
            if (candidateOverlaySkin != null) {
                Skin previousOverlay = overlaySkin;
                overlaySkin = candidateOverlaySkin;
                candidateOverlaySkin = null;
                overlayRasterScale = candidateRasterScale;
                errorStyle.font = overlaySkin.getFont("default-font");
                errorLabel.setStyle(errorStyle);
                disposePreviousOverlaySkin(previousOverlay);
            }
            if (previous != null) {
                disposePreviousSkin(previous);
            }
            status(MarkupStatus.ok(built.actors().size()));
        } catch (MarkupException failure) {
            handleRebuildFailure(failure, failure.formatted(), MarkupStatus.error(failure),
                    candidate, candidateOverlaySkin, pendingRuntime, stageBefore);
        } catch (IOException failure) {
            handleRebuildFailure(failure, failure.getMessage(),
                    MarkupStatus.error(failure.getMessage()), candidate, candidateOverlaySkin,
                    pendingRuntime, stageBefore);
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            handleRebuildFailure(failure, message, MarkupStatus.error(message), candidate,
                    candidateOverlaySkin, pendingRuntime, stageBefore);
        }
    }

    private static Skin createOverlaySkin(float scale) {
        Skin candidate = new Skin();
        try {
            FreeTypeFontManager.installDefault(candidate, scale);
            return candidate;
        } catch (RuntimeException | Error failure) {
            candidate.dispose();
            throw failure;
        }
    }

    private Skin createSkin(float scale) {
        if (options.skin() == null) {
            return DefaultSkin.create(scale);
        }
        Skin custom = new Skin(Gdx.files.absolute(options.skin().toString()));
        try {
            FreeTypeFontManager.installDefault(custom, scale);
            return custom;
        } catch (RuntimeException | Error failure) {
            custom.dispose();
            throw failure;
        }
    }

    /**
     * Returns the physical-pixel raster density for a logical viewport. The larger axis avoids
     * undersampling on asymmetric backing buffers; invalid startup dimensions use one.
     */
    static float rasterScale(
            int logicalWidth, int logicalHeight, int backBufferWidth, int backBufferHeight) {
        if (logicalWidth <= 0 || logicalHeight <= 0
                || backBufferWidth <= 0 || backBufferHeight <= 0) {
            return 1f;
        }
        float horizontal = (float) backBufferWidth / logicalWidth;
        float vertical = (float) backBufferHeight / logicalHeight;
        return Math.clamp(Math.max(horizontal, vertical), 1f, 4f);
    }

    private static float currentRasterScale() {
        return rasterScale(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(),
                Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    /**
     * Rolls back an acquired runtime attachment and any stage mutation, disposes the candidate,
     * and publishes the typed failure — or enters the terminal state when the runtime can no
     * longer be reinstated. Rollback failures are suppressed onto the original failure so the
     * rebuild always ends in one typed error.
     */
    private void handleRebuildFailure(Throwable failure, String text,
            MarkupStatus status, Candidate candidate, Skin candidateOverlaySkin,
            PreviewMcp.PendingRuntime pendingRuntime, StageState stageBefore) {
        if (mcp != null && pendingRuntime != null) {
            // rollbackRuntime already aggregated candidate close + reinstatement with
            // finally-style semantics; its cleanup failure (if any) is suppressed here.
            RuntimeException cleanup = mcp.rollbackRuntime(pendingRuntime);
            if (cleanup != null) {
                failure.addSuppressed(cleanup);
            }
        }
        if (stageBefore != null) {
            try {
                restoreStage(stageBefore);
            } catch (RuntimeException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
        }
        if (candidate != null) {
            try {
                candidate.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        if (candidateOverlaySkin != null) {
            try {
                candidateOverlaySkin.dispose();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        if (mcp != null && mcp.runtimeLost()) {
            enterTerminal(terminalMessage(failure));
            return;
        }
        publishFailure(text, status);
        if (options.exit()) {
            System.out.flush();
            System.err.flush();
            System.exit(2);
        }
    }

    /**
     * Builds the bounded terminal message from the primary failure and every linked cause at
     * any depth — the direct {@link Throwable#getCause()} chain and the suppressed tree,
     * recursively — so the typed TERMINAL status and overlay carry the actual injected cause
     * text (component-close, candidate-close, retirement, reinstatement), not only the generic
     * wrappers. The walk is deterministic (cause chain before suppressed, depth-first), bounded
     * to the status string limit (append-level, so a single huge message cannot blow the
     * builder), and cycle-safe (identity-based visited set, so a cyclic cause graph terminates
     * instead of overflowing).
     */
    private static String terminalMessage(Throwable failure) {
        StringBuilder message = new StringBuilder();
        appendFailure(message, failure);
        appendLinked(message, failure, visitedWith(failure));
        return MarkupStatus.bound(message.toString());
    }

    /**
     * Appends every linked cause of {@code failure}: the direct cause chain first, then each
     * suppressed failure, each throwable at most once (identity-based visited set, breaking
     * cycles), stopping as soon as the message reaches the status string limit.
     */
    private static void appendLinked(StringBuilder message, Throwable failure,
            Set<Throwable> visited) {
        if (message.length() >= MarkupStatus.MAX_STRING_LENGTH) {
            return; // bounded: no more room for another cause
        }
        Throwable cause = failure.getCause();
        if (cause != null && visited.add(cause)) {
            appendCause(message, cause);
            appendLinked(message, cause, visited);
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (message.length() >= MarkupStatus.MAX_STRING_LENGTH) {
                return;
            }
            if (visited.add(suppressed)) {
                appendCause(message, suppressed);
                appendLinked(message, suppressed, visited);
            }
        }
    }

    /** A fresh identity-keyed visited set, seeded with the root so a cycle back to it stops. */
    private static Set<Throwable> visitedWith(Throwable root) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(root);
        return visited;
    }

    /** Appends one linked failure as {@code "; cleanup failed: …"}, only when it fully fits. */
    private static void appendCause(StringBuilder message, Throwable failure) {
        if (message.length() + CLEANUP_MARKER.length() > MarkupStatus.MAX_STRING_LENGTH) {
            return; // bounded: the marker would overflow the status string limit
        }
        message.append(CLEANUP_MARKER);
        appendFailure(message, failure);
    }

    /** The separator between the wrapper and each linked cause in terminal messages. */
    private static final String CLEANUP_MARKER = "; cleanup failed: ";

    /** Appends the failure's message or class name, never exceeding the status string limit
     * and never splitting a surrogate pair at the cut. */
    private static void appendFailure(StringBuilder message, Throwable failure) {
        String text = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        int remaining = MarkupStatus.MAX_STRING_LENGTH - message.length();
        if (remaining <= 0) {
            return;
        }
        if (text.length() > remaining) {
            int cut = remaining;
            if (Character.isHighSurrogate(text.charAt(cut - 1))
                    && Character.isLowSurrogate(text.charAt(cut))) {
                cut--; // never split a surrogate pair at the cut
            }
            message.append(text, 0, cut);
        } else {
            message.append(text);
        }
    }

    /**
     * Appends one terminal-cleanup failure (and its linked causes) to the already-published
     * terminal message, bounded to the status string limit and surrogate-safe through
     * {@link MarkupStatus#bound}.
     */
    private static String appendCleanupCause(String message, Throwable cleanup) {
        StringBuilder builder = new StringBuilder(message);
        appendCause(builder, cleanup);
        appendLinked(builder, cleanup, visitedWith(cleanup));
        return MarkupStatus.bound(builder.toString());
    }

    /**
     * Disposes the previous (retired) skin after the new scene is fully committed. Cleanup
     * failure is handled explicitly: the committed scene stays live and a bounded warning is
     * emitted, because the retired skin can never be used again and failing the rebuild would
     * discard a committed scene for an unrecoverable cleanup error.
     */
    private void disposePreviousSkin(Skin previous) {
        try {
            previous.dispose();
        } catch (RuntimeException cleanupFailure) {
            System.err.println("markup-warning: failed to dispose the previous skin: "
                    + cleanupFailure.getMessage());
            System.err.flush();
        }
    }

    private static void disposePreviousOverlaySkin(Skin previous) {
        try {
            previous.dispose();
        } catch (RuntimeException cleanupFailure) {
            System.err.println("markup-warning: failed to dispose the previous overlay skin: "
                    + cleanupFailure.getMessage());
            System.err.flush();
        }
    }

    /**
     * Enters the terminal state after an unrestorable runtime failure: the last-good
     * registration can no longer be reinstated, so the runtime cannot be kept consistent with
     * the retained scene. Rebuilds stop, the watcher stops, and the MCP session is closed; a
     * typed {@code TERMINAL} status is published and the restored last-good scene stays on
     * screen with the terminal overlay. The MCP reference is detached in a {@code finally}
     * (never re-closed), the watcher is stopped regardless, and any terminal-cleanup failure
     * (with its causes) is appended to the already-published bounded TERMINAL status instead
     * of throwing out of the render loop.
     */
    private void enterTerminal(String message) {
        terminal = true;
        if (watcher != null) {
            watcher.interrupt();
            watcher = null;
        }
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        // In --mcp mode status lines go to stderr; keep the appended re-publication on the
        // same stream even after the MCP session is detached.
        boolean statusToStderr = mcp != null;
        status(MarkupStatus.terminal(message));
        RuntimeException cleanup = null;
        if (mcp != null) {
            try {
                mcp.close();
            } catch (RuntimeException closeFailure) {
                cleanup = closeFailure;
            } finally {
                mcp = null;
            }
        }
        if (cleanup != null) {
            String appended = appendCleanupCause(message, cleanup);
            errorLabel.setText(appended);
            errorLabel.setVisible(true);
            publishStatus(MarkupStatus.terminal(appended), statusToStderr);
        }
    }

    /** Stages the typed error overlay and publishes one bounded status line. */
    private void publishFailure(String text, MarkupStatus status) {
        errorLabel.setText(text);
        errorLabel.setVisible(true);
        status(status);
    }

    /** Package-visible test seam: performs the visible stage swap for a committed candidate.
     * Production uses {@link #defaultStageSwap}; tests inject failures to prove rollback. */
    @FunctionalInterface
    interface StageSwap {
        void swap(Group root);
    }

    StageSwap stageSwap = this::defaultStageSwap;

    /** The production stage swap: clear the old scene and stage the new root with the error
     * overlay on top. Package-visible so tests can restore the default after injecting a
     * failure. */
    void defaultStageSwap(Group root) {
        stage.getRoot().clearChildren();
        stage.getRoot().addActor(root);
        stage.getRoot().addActor(errorLabel);
        errorLabel.setVisible(false);
    }

    /** A failure-free snapshot of the live stage root and overlay state, taken before the
     * stage swap so a failed swap can restore the exact old scene (children in order, overlay
     * state). */
    private static final class StageState {
        private final List<Actor> children;
        private final boolean overlayVisible;

        StageState(Group root, Label overlay) {
            children = List.of(root.getChildren().toArray());
            overlayVisible = overlay.isVisible();
        }
    }

    private StageState captureStage() {
        return new StageState(stage.getRoot(), errorLabel);
    }

    /** Restores the exact pre-swap stage: children in their original order and overlay state. */
    private void restoreStage(StageState state) {
        stage.getRoot().clearChildren();
        for (Actor child : state.children) {
            stage.getRoot().addActor(child);
        }
        errorLabel.setVisible(state.overlayVisible);
    }

    /**
     * One prepared rebuild. Owns every candidate resource — the parsed document, stylesheet,
     * candidate skin, and built actor tree — until the rebuild is committed or disposed.
     * {@link #close()} releases exactly the candidate's skin, so a failed rebuild can never
     * touch the last-good skin or actors. The candidate's runtime registration is owned by a
     * {@link PreviewMcp.PendingRuntime} until commit.
     */
    private static final class Candidate implements AutoCloseable {
        private final MarkupDocument document;
        private final CssDocument css;
        private final Skin skin;
        private BuiltUi built;

        Candidate(MarkupDocument document, CssDocument css, Skin skin) {
            this.document = Objects.requireNonNull(document, "document");
            this.css = Objects.requireNonNull(css, "css");
            this.skin = Objects.requireNonNull(skin, "skin");
        }

        void adoptBuilt(BuiltUi ui) {
            built = Objects.requireNonNull(ui, "ui");
        }

        Skin skin() {
            return skin;
        }

        BuiltUi built() {
            return built;
        }

        @Override public void close() {
            skin.dispose();
        }
    }

    /** Returns the currently committed (live) skin, or {@code null} before the first success. */
    Skin skin() {
        return skin;
    }

    Actor actor(String actorName) {
        return stage == null ? null : stage.getRoot().findActor(actorName);
    }

    BitmapFont errorOverlayFont() {
        return errorStyle == null ? null : errorStyle.font;
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
        publishStatus(status, mcp != null);
    }

    /** Publishes one status line to stderr ({@code toStderr}) or stdout. */
    private void publishStatus(MarkupStatus status, boolean toStderr) {
        if (toStderr) {
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
                        if (context instanceof Path name && isWatchedSource(name)) {
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

    static boolean isWatchedSource(Path name) {
        String value = name.toString();
        return value.endsWith(".xml") || value.endsWith(".gdxcss")
                || value.endsWith(".css");
    }

    @Override public void render() {
        if (!terminal && reloadPending
                && System.nanoTime() - reloadRequestedNanos >= RELOAD_DEBOUNCE_NANOS) {
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
        // The OpenGL framebuffer origin is bottom-left, so the raw capture is vertically
        // flipped; qualification compares recreations against upright references, so the
        // PNG must be upright too.
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

    @FunctionalInterface
    interface RasterScaleSource {
        float get();
    }

    RasterScaleSource rasterScaleSource = PreviewApp::currentRasterScale;

    @Override public void resize(int width, int height) {
        if (stage != null) {
            stage.getViewport().update(width, height, true);
            if (!stage.getRoot().getChildren().isEmpty()) {
                stage.getRoot().getChildren().first().setSize(width, height);
            }
            float scale = rasterScaleSource.get();
            if (skin != null && Math.abs(scale - committedRasterScale) > 0.001f) {
                rebuild();
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
        if (overlaySkin != null) {
            overlaySkin.dispose();
        }
    }
}
