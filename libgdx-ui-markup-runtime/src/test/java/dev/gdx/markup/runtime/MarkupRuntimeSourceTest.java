package dev.gdx.markup.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import dev.gdx.markup.core.BuiltUi;
import dev.gdx.markup.core.DefaultSkin;
import dev.gdx.markup.core.MarkupBuilder;
import dev.gdx.markup.core.MarkupDocument;
import dev.gdx.markup.core.MarkupException;
import dev.gdx.markup.core.MarkupParser;
import dev.gdx.markup.core.NoopSink;
import dev.gdx.markup.core.style.CssDocument;
import dev.gdx.markup.core.style.CssParser;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntitySnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameSnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Render-thread runtime source tests; run with {@code xvfb-run} for a real GL context. */
final class MarkupRuntimeSourceTest {
    private final MarkupParser markup = new MarkupParser();
    private final CssParser css = new CssParser();

    @Test
    void entityValueReflectsLiveWidgetState() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument document = markup.parse("""
                    <ui>
                      <table>
                        <textfield id="user" data-runtime-entity="user"/>
                      </table>
                    </ui>
                    """);
            BuiltUi built = MarkupBuilder.build(document, css.parse(""), skin, new NoopSink());
            Group root = built.root();
            root.setSize(1280, 720);

            TextField field = (TextField) root.findActor("user");
            field.setText("Alice");

            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test")).build();
            runtime.start();
            try (MarkupRuntimeSource source = MarkupRuntimeSource.register(
                    runtime, document, built, "markup-preview")) {
                assertEquals(List.of("user"), source.registeredEntities());

                runtime.beginFrame(Duration.ofMillis(16).toNanos());
                runtime.endFrame();
                FrameSnapshot frame = runtime.latestFrame().orElseThrow();
                EntitySnapshot entity = frame.entity(EntityId.of("user")).orElseThrow();
                assertEquals("widget", entity.type().value());
                RuntimeValue value = entity.property("value").orElseThrow();
                assertTrue(value instanceof RuntimeValue.StringValue);
                assertEquals("Alice", ((RuntimeValue.StringValue) value).value());

                field.setText("Bob");
                runtime.beginFrame(Duration.ofMillis(16).toNanos());
                runtime.endFrame();
                RuntimeValue updated = runtime.latestFrame().orElseThrow()
                        .entity(EntityId.of("user")).orElseThrow()
                        .property("value").orElseThrow();
                assertEquals("Bob", ((RuntimeValue.StringValue) updated).value(),
                        "the property supplier reads the actor live, not once");
            }
            runtime.close();
        });
    }

    @Test
    void extractsTypedValuesPerWidget() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument document = markup.parse("""
                    <ui>
                      <table>
                        <checkbox id="remember" data-runtime-entity="remember" text="R"/>
                        <slider id="level" min="0" max="10" step="0.5" value="4"
                            data-runtime-entity="level"/>
                      </table>
                    </ui>
                    """);
            BuiltUi built = MarkupBuilder.build(document, css.parse(""), skin, new NoopSink());
            Group root = built.root();
            root.setSize(1280, 720);
            ((CheckBox) root.findActor("remember")).setChecked(true);
            ((Slider) root.findActor("level")).setValue(7.5f);

            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test")).build();
            runtime.start();
            try (MarkupRuntimeSource source = MarkupRuntimeSource.register(
                    runtime, document, built, "markup-preview")) {
                assertEquals(List.of("remember", "level"), source.registeredEntities());
                runtime.beginFrame(Duration.ofMillis(16).toNanos());
                runtime.endFrame();
                FrameSnapshot frame = runtime.latestFrame().orElseThrow();

                RuntimeValue checked = frame.entity(EntityId.of("remember")).orElseThrow()
                        .property("value").orElseThrow();
                assertTrue(checked instanceof RuntimeValue.BooleanValue);
                assertTrue(((RuntimeValue.BooleanValue) checked).value());

                RuntimeValue level = frame.entity(EntityId.of("level")).orElseThrow()
                        .property("value").orElseThrow();
                assertTrue(level instanceof RuntimeValue.DecimalValue);
                assertEquals(0, ((RuntimeValue.DecimalValue) level).value()
                        .compareTo(java.math.BigDecimal.valueOf(7.5)));
            }
            runtime.close();
        });
    }

    @Test
    void registersUiBindingBetweenEntityAndActor() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument document = markup.parse("""
                    <ui>
                      <table>
                        <textfield id="user" data-runtime-entity="user" data-runtime-property="name"/>
                      </table>
                    </ui>
                    """);
            BuiltUi built = MarkupBuilder.build(document, css.parse(""), skin, new NoopSink());
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test")).build();
            runtime.start();
            try (MarkupRuntimeSource source = MarkupRuntimeSource.register(
                    runtime, document, built, "markup-preview")) {
                assertEquals(List.of("user"), source.registeredEntities());
                runtime.beginFrame(Duration.ofMillis(16).toNanos());
                runtime.endFrame();
                var frame = runtime.latestFrame().orElseThrow();
                var result = runtime.uiCorrelations().uiToRuntime(
                        "markup-preview", "user", runtime.currentEpoch(), frame.frameId(),
                        java.util.Optional.empty(), 16);
                assertEquals(io.github.teemuki8.libgdx.agent.runtime.core.UiBindingStatus.MATCHED,
                        result.status(), "the binding maps the UI control to the runtime entity");
                assertEquals("user", result.bindings().get(0).runtimeEntityId().value());
                assertEquals(java.util.Optional.of("name"),
                        result.bindings().get(0).runtimeProperty());
            }
            runtime.close();
        });
    }

    @Test
    void entityRequiresAnId() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument document = markup.parse(
                    "<ui><textfield data-runtime-entity=\"user\"/></ui>");
            BuiltUi built = MarkupBuilder.build(document, css.parse(""), skin, new NoopSink());
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test")).build();
            runtime.start();
            try {
                MarkupException failure = assertThrows(MarkupException.class, () ->
                        MarkupRuntimeSource.register(runtime, document, built, "markup-preview"));
                assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
                assertTrue(failure.getMessage().contains("requires an id"));
            } finally {
                runtime.close();
            }
        });
    }

    @Test
    void closeUnregistersEntities() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument document = markup.parse(
                    "<ui><textfield id=\"user\" data-runtime-entity=\"user\"/></ui>");
            BuiltUi built = MarkupBuilder.build(document, css.parse(""), skin, new NoopSink());
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test")).build();
            runtime.start();
            MarkupRuntimeSource source = MarkupRuntimeSource.register(
                    runtime, document, built, "markup-preview");
            source.close();
            runtime.beginFrame(Duration.ofMillis(16).toNanos());
            runtime.endFrame();
            assertTrue(runtime.latestFrame().orElseThrow()
                    .entity(EntityId.of("user")).isEmpty(),
                    "closed registrations no longer appear in runtime frames");
            runtime.close();
        });
    }
}
