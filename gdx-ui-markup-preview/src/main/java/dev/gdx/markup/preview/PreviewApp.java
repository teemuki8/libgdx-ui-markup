package dev.gdx.markup.preview;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
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

    private PreviewApp(CliOptions options) {
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

        if (options.mcp()) {
            mcp = new PreviewMcp(stage);
        }
        rebuild();
        startWatcher();
    }

    /** Rebuilds the scene from disk on the GL thread and reports one bounded status line. */
    private void rebuild() {
        try {
            MarkupDocument document = new MarkupParser().parse(
                    Files.readString(options.ui(), StandardCharsets.UTF_8));
            CssDocument css = new CssParser().parse(
                    Files.readString(options.css(), StandardCharsets.UTF_8));
            Skin newSkin = options.skin() == null ? DefaultSkin.create()
                    : new Skin(Gdx.files.absolute(options.skin().toString()));
            if (skin != null) {
                skin.dispose();
            }
            skin = newSkin;
            BuiltUi built = MarkupBuilder.build(document, css, skin, sink());
            // The ui root group must cover the viewport or harness actionability (parent
            // intersection and Group.hit) sees a zero-sized parent and rejects every actor.
            built.root().setSize(stage.getViewport().getWorldWidth(),
                    stage.getViewport().getWorldHeight());
            stage.getRoot().clearChildren();
            stage.getRoot().addActor(built.root());
            stage.getRoot().addActor(errorLabel);
            errorLabel.setVisible(false);
            if (mcp != null) {
                mcp.attachRuntime(document, built);
            }
            status(MarkupStatus.ok(built.actors().size()));
        } catch (MarkupException failure) {
            errorLabel.setText(failure.formatted());
            errorLabel.setVisible(true);
            status(MarkupStatus.error(failure.formatted(), failure.line(), failure.column()));
            if (options.exit()) {
                System.out.flush();
                System.err.flush();
                System.exit(2);
            }
        } catch (IOException failure) {
            errorLabel.setText(failure.getMessage());
            errorLabel.setVisible(true);
            status(MarkupStatus.error(failure.getMessage(), 0, 0));
            if (options.exit()) {
                System.out.flush();
                System.err.flush();
                System.exit(2);
            }
        }
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
            com.badlogic.gdx.graphics.PixmapIO.writePNG(
                    Gdx.files.absolute(options.screenshot().toAbsolutePath().toString()), pixmap);
        } finally {
            pixmap.dispose();
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
