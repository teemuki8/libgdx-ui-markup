package dev.gdx.markup.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntimeException;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntitySnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameSnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeErrorCode;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.UiCorrelationLimits;
import io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelation;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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

    @Test
    void failedRegistrationLeavesNoStateAndAllowsRetry_missingLateActor() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument uiDocument = markup.parse("""
                    <ui>
                      <table>
                        <textfield id="user" data-runtime-entity="user"/>
                      </table>
                    </ui>
                    """);
            MarkupDocument document = markup.parse("""
                    <ui>
                      <table>
                        <textfield id="user" data-runtime-entity="user"/>
                        <textfield id="late" data-runtime-entity="late"/>
                      </table>
                    </ui>
                    """);
            BuiltUi built = MarkupBuilder.build(
                    uiDocument, css.parse(""), skin, new NoopSink());
            BuiltUi correctedBuilt = MarkupBuilder.build(
                    document, css.parse(""), skin, new NoopSink());
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test")).build();
            runtime.start();
            try {
                MarkupException failure = assertThrows(MarkupException.class, () ->
                        MarkupRuntimeSource.register(runtime, document, built, "markup-preview"));
                assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
                assertTrue(failure.getMessage().contains("no built actor"));
                assertEquals("ui/table/textfield[1]", failure.elementPath());
                assertNoRuntimeState(runtime);
                try (MarkupRuntimeSource retried = MarkupRuntimeSource.register(
                        runtime, document, correctedBuilt, "markup-preview")) {
                    assertEquals(List.of("user", "late"), retried.registeredEntities());
                    runtime.beginFrame(Duration.ofMillis(16).toNanos());
                    runtime.endFrame();
                    FrameSnapshot frame = runtime.latestFrame().orElseThrow();
                    assertTrue(frame.entity(EntityId.of("user")).isPresent());
                    assertTrue(frame.entity(EntityId.of("late")).isPresent());
                }
            } finally {
                runtime.close();
            }
        });
    }

    @Test
    void failedRegistrationLeavesNoStateAndAllowsRetry_duplicateLateEntity() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument document = markup.parse("""
                    <ui>
                      <table>
                        <textfield id="first" data-runtime-entity="dup"/>
                        <textfield id="second" data-runtime-entity="dup"/>
                      </table>
                    </ui>
                    """);
            MarkupDocument corrected = markup.parse("""
                    <ui>
                      <table>
                        <textfield id="first" data-runtime-entity="first"/>
                        <textfield id="second" data-runtime-entity="second"/>
                      </table>
                    </ui>
                    """);
            BuiltUi built = MarkupBuilder.build(document, css.parse(""), skin, new NoopSink());
            BuiltUi correctedBuilt = MarkupBuilder.build(
                    corrected, css.parse(""), skin, new NoopSink());
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test")).build();
            runtime.start();
            try {
                AgentRuntimeException failure = assertThrows(AgentRuntimeException.class, () ->
                        MarkupRuntimeSource.register(runtime, document, built, "markup-preview"));
                assertEquals(RuntimeErrorCode.DUPLICATE_ENTITY, failure.code(),
                        "the runtime's own duplicate detection must reach the caller unchanged");
                assertNoRuntimeState(runtime);
                try (MarkupRuntimeSource retried = MarkupRuntimeSource.register(
                        runtime, corrected, correctedBuilt, "markup-preview")) {
                    assertEquals(List.of("first", "second"), retried.registeredEntities());
                    runtime.beginFrame(Duration.ofMillis(16).toNanos());
                    runtime.endFrame();
                    FrameSnapshot frame = runtime.latestFrame().orElseThrow();
                    assertTrue(frame.entity(EntityId.of("first")).isPresent());
                    assertTrue(frame.entity(EntityId.of("second")).isPresent());
                }
            } finally {
                runtime.close();
            }
        });
    }

    @Test
    void failedRegistrationLeavesNoStateAndAllowsRetry_invalidLateEntityId() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument document = markup.parse("""
                    <ui>
                      <table>
                        <textfield id="user" data-runtime-entity="user"/>
                        <textfield id="bad" data-runtime-entity="bad value!"/>
                      </table>
                    </ui>
                    """);
            MarkupDocument corrected = markup.parse("""
                    <ui>
                      <table>
                        <textfield id="user" data-runtime-entity="user"/>
                        <textfield id="bad" data-runtime-entity="bad"/>
                      </table>
                    </ui>
                    """);
            BuiltUi built = MarkupBuilder.build(document, css.parse(""), skin, new NoopSink());
            BuiltUi correctedBuilt = MarkupBuilder.build(
                    corrected, css.parse(""), skin, new NoopSink());
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test")).build();
            runtime.start();
            try {
                MarkupException failure = assertThrows(MarkupException.class, () ->
                        MarkupRuntimeSource.register(runtime, document, built, "markup-preview"));
                assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
                assertTrue(failure.getMessage().contains("invalid value for data-runtime-entity"));
                assertNoRuntimeState(runtime);
                try (MarkupRuntimeSource retried = MarkupRuntimeSource.register(
                        runtime, corrected, correctedBuilt, "markup-preview")) {
                    assertEquals(List.of("user", "bad"), retried.registeredEntities());
                    runtime.beginFrame(Duration.ofMillis(16).toNanos());
                    runtime.endFrame();
                    FrameSnapshot frame = runtime.latestFrame().orElseThrow();
                    assertTrue(frame.entity(EntityId.of("user")).isPresent());
                    assertTrue(frame.entity(EntityId.of("bad")).isPresent());
                }
            } finally {
                runtime.close();
            }
        });
    }

    @Test
    void failedRegistrationLeavesNoStateAndAllowsRetry_beyondMaxEntities() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            StringBuilder overflow = new StringBuilder("<ui><table>");
            StringBuilder exact = new StringBuilder("<ui><table>");
            for (int i = 0; i <= MarkupRuntimeSource.MAX_ENTITIES; i++) {
                overflow.append("<textfield id=\"e").append(i)
                        .append("\" data-runtime-entity=\"e").append(i).append("\"/>");
            }
            for (int i = 0; i < MarkupRuntimeSource.MAX_ENTITIES; i++) {
                exact.append("<textfield id=\"e").append(i)
                        .append("\" data-runtime-entity=\"e").append(i).append("\"/>");
            }
            MarkupDocument overflowDocument = markup.parse(overflow.append("</table></ui>").toString());
            MarkupDocument exactDocument = markup.parse(exact.append("</table></ui>").toString());
            BuiltUi overflowBuilt = MarkupBuilder.build(
                    overflowDocument, css.parse(""), skin, new NoopSink());
            BuiltUi exactBuilt = MarkupBuilder.build(
                    exactDocument, css.parse(""), skin, new NoopSink());
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test")).build();
            runtime.start();
            try {
                MarkupException failure = assertThrows(MarkupException.class, () ->
                        MarkupRuntimeSource.register(
                                runtime, overflowDocument, overflowBuilt, "markup-preview"));
                assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
                assertNoRuntimeState(runtime);
                try (MarkupRuntimeSource retried = MarkupRuntimeSource.register(
                        runtime, exactDocument, exactBuilt, "markup-preview")) {
                    assertEquals(MarkupRuntimeSource.MAX_ENTITIES,
                            retried.registeredEntities().size());
                    assertEquals("e0", retried.registeredEntities().get(0));
                    assertEquals("e" + (MarkupRuntimeSource.MAX_ENTITIES - 1),
                            retried.registeredEntities().get(MarkupRuntimeSource.MAX_ENTITIES - 1));
                    runtime.beginFrame(Duration.ofMillis(16).toNanos());
                    runtime.endFrame();
                    FrameSnapshot frame = runtime.latestFrame().orElseThrow();
                    assertEquals(MarkupRuntimeSource.MAX_ENTITIES, frame.entities().size());
                    assertTrue(frame.entity(EntityId.of("e0")).isPresent());
                    assertTrue(frame.entity(EntityId.of("e255")).isPresent());
                }
            } finally {
                runtime.close();
            }
        });
    }

    @Test
    void failedRegistrationLeavesNoStateAndAllowsRetry_bindingFailure() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument document = markup.parse("""
                    <ui>
                      <table>
                        <textfield id="user" data-runtime-entity="user"/>
                        <textfield id="extra" data-runtime-entity="extra"/>
                      </table>
                    </ui>
                    """);
            BuiltUi built = MarkupBuilder.build(document, css.parse(""), skin, new NoopSink());
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test"))
                    .uiCorrelationLimits(new UiCorrelationLimits(1, 100, 1_024, 512))
                    .build();
            runtime.start();
            try {
                AgentRuntimeException failure = assertThrows(AgentRuntimeException.class, () ->
                        MarkupRuntimeSource.register(runtime, document, built, "markup-preview"));
                assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED, failure.code());
                assertNoRuntimeState(runtime);
                AgentRuntime corrected = AgentRuntime.builder()
                        .sessionId(SessionId.of("runtime-test"))
                        .uiCorrelationLimits(new UiCorrelationLimits(2, 100, 1_024, 512))
                        .build();
                corrected.start();
                try (MarkupRuntimeSource retried = MarkupRuntimeSource.register(
                        corrected, document, built, "markup-preview")) {
                    assertEquals(List.of("user", "extra"), retried.registeredEntities());
                    assertEquals(2, corrected.uiCorrelations().list().size());
                    corrected.beginFrame(Duration.ofMillis(16).toNanos());
                    corrected.endFrame();
                    FrameSnapshot frame = corrected.latestFrame().orElseThrow();
                    assertTrue(frame.entity(EntityId.of("user")).isPresent());
                    assertTrue(frame.entity(EntityId.of("extra")).isPresent());
                }
                corrected.close();
            } finally {
                runtime.close();
            }
        });
    }

    @Test
    void runtimeDiagnosticsUseParentScopedCorePaths() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument document = markup.parse("""
                    <ui>
                      <table>
                        <textfield id="user" data-runtime-entity="user"/>
                      </table>
                      <table>
                        <textfield id="bad" data-runtime-entity="bad value!"/>
                      </table>
                    </ui>
                    """);
            BuiltUi built = MarkupBuilder.build(document, css.parse(""), skin, new NoopSink());
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test")).build();
            runtime.start();
            try {
                MarkupException failure = assertThrows(MarkupException.class, () ->
                        MarkupRuntimeSource.register(runtime, document, built, "markup-preview"));
                assertEquals("ui/table[1]/textfield", failure.elementPath(),
                        "runtime diagnostics must match the core parent-scoped path");
            } finally {
                runtime.close();
            }
        });
    }

    @Test
    void bindingsOnlyInstallsCorrelationsWithoutPropertySuppliers() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument document = markup.parse(
                    "<ui><table><textfield id=\"user\" data-runtime-entity=\"user\"/></table></ui>");
            BuiltUi built = MarkupBuilder.build(document, css.parse(""), skin, new NoopSink());
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test")).build();
            runtime.start();
            try (MarkupRuntimeSource source = MarkupRuntimeSource.registerBindings(
                    runtime, document, built, "markup-preview")) {
                assertEquals(1, runtime.uiCorrelations().list().size(),
                        "bindings-only mode installs the UI correlation");
                assertTrue(source.registeredEntities().isEmpty(),
                        "bindings-only mode registers no entities of its own");
                runtime.beginFrame(Duration.ofMillis(16).toNanos());
                runtime.endFrame();
                assertTrue(runtime.latestFrame().orElseThrow().entities().isEmpty(),
                        "bindings-only mode installs no property supplier");
            }
            runtime.close();
        });
    }

    @Test
    void authoritativeRegistrationReportsMismatchWhenDomainValueDiffersFromWidget()
            throws Exception {
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
            try (MarkupRuntimeSource source = MarkupRuntimeSource.registerAuthoritative(
                    runtime, document, built, "markup-preview",
                    (entityId, propertyId, actor) -> {
                        assertEquals("user", entityId);
                        assertEquals("value", propertyId);
                        assertSame(field, actor);
                        return () -> RuntimeValues.string("Carol");
                    })) {
                assertEquals(List.of("user"), source.registeredEntities());
                runtime.beginFrame(Duration.ofMillis(16).toNanos());
                runtime.endFrame();
                RuntimeValue authoritative = runtime.latestFrame().orElseThrow()
                        .entity(EntityId.of("user")).orElseThrow()
                        .property("value").orElseThrow();
                assertEquals("Carol", ((RuntimeValue.StringValue) authoritative).value(),
                        "the supplied domain value wins over widget readback");
                assertEquals("Alice", field.getText(),
                        "the widget still shows stale UI state; the divergence is observable");
            }
            runtime.close();
        });
    }

    @Test
    void authoritativeRegistrationFailsPreflightWhenSupplierMissing() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument document = markup.parse("""
                    <ui>
                      <table>
                        <textfield id="user" data-runtime-entity="user"/>
                        <textfield id="late" data-runtime-entity="late"/>
                      </table>
                    </ui>
                    """);
            BuiltUi built = MarkupBuilder.build(document, css.parse(""), skin, new NoopSink());
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test")).build();
            runtime.start();
            try {
                MarkupException failure = assertThrows(MarkupException.class, () ->
                        MarkupRuntimeSource.registerAuthoritative(
                                runtime, document, built, "markup-preview",
                                (entityId, propertyId, actor) -> entityId.equals("user")
                                        ? () -> RuntimeValues.string("Alice") : null));
                assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
                assertTrue(failure.getMessage().contains("late"),
                        "the diagnostic names the entity without an authoritative supplier");
                assertEquals("ui/table/textfield[1]", failure.elementPath(),
                        "the missing-supplier failure is located at the offending element");
                assertNoRuntimeState(runtime);
            } finally {
                runtime.close();
            }
        });
    }

    @Test
    void widgetMirrorExplicitlyTracksLiveWidgets() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument document = markup.parse(
                    "<ui><table><textfield id=\"user\" data-runtime-entity=\"user\"/></table></ui>");
            BuiltUi built = MarkupBuilder.build(document, css.parse(""), skin, new NoopSink());
            Group root = built.root();
            root.setSize(1280, 720);
            TextField field = (TextField) root.findActor("user");
            field.setText("Alice");

            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test")).build();
            runtime.start();
            try (MarkupRuntimeSource source = MarkupRuntimeSource.registerWidgetMirror(
                    runtime, document, built, "markup-preview")) {
                assertEquals(List.of("user"), source.registeredEntities());
                runtime.beginFrame(Duration.ofMillis(16).toNanos());
                runtime.endFrame();
                RuntimeValue value = runtime.latestFrame().orElseThrow()
                        .entity(EntityId.of("user")).orElseThrow()
                        .property("value").orElseThrow();
                assertEquals("Alice", ((RuntimeValue.StringValue) value).value());
                field.setText("Bob");
                runtime.beginFrame(Duration.ofMillis(16).toNanos());
                runtime.endFrame();
                RuntimeValue updated = runtime.latestFrame().orElseThrow()
                        .entity(EntityId.of("user")).orElseThrow()
                        .property("value").orElseThrow();
                assertEquals("Bob", ((RuntimeValue.StringValue) updated).value(),
                        "widget mirror tracks the live widget, not a one-off readback");
            }
            runtime.close();
        });
    }

    /**
     * The harness observation source proves a frame only through a {@link UiFrameCorrelation}
     * whose token equals the binding's correlation token. Recording every correlation under a
     * different token therefore leaves the latest frame unprovable: the adapter emits no
     * observation and {@code ui_runtime_compare} reports {@code UNAVAILABLE} (never
     * {@code STALE}/{@code UNCORRELATED}) through that source.
     */
    @Test
    void tokenMismatchLeavesTheLatestFrameUncorrelated() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument document = markup.parse(
                    "<ui><table><textfield id=\"user\" data-runtime-entity=\"user\"/></table></ui>");
            BuiltUi built = MarkupBuilder.build(document, css.parse(""), skin, new NoopSink());
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test")).build();
            runtime.start();
            try (MarkupRuntimeSource source = MarkupRuntimeSource.registerWidgetMirror(
                    runtime, document, built, "markup-preview")) {
                assertEquals(List.of("user"), source.registeredEntities());
                runtime.beginFrame(Duration.ofMillis(16).toNanos());
                runtime.endFrame();
                runtime.uiCorrelations().recordFrame(new UiFrameCorrelation(
                        runtime.currentEpoch(),
                        runtime.latestFrame().orElseThrow().frameId(),
                        "markup-preview",
                        Optional.of("1"),
                        Optional.of("application-owned-token")));
                runtime.beginFrame(Duration.ofMillis(16).toNanos());
                runtime.endFrame();

                List<UiFrameCorrelation> correlations = runtime.uiCorrelations()
                        .framesForUiSession("markup-preview", 64).items();
                assertEquals(1, correlations.size(),
                        "the session recorded one correlation, under the application's token");
                assertTrue(correlations.stream().noneMatch(correlation ->
                                correlation.correlationToken()
                                        .equals(Optional.of("markup-preview-frame"))),
                        "no correlation matches the binding's token, so the observation source "
                                + "resolves nothing (UNAVAILABLE) for a token mismatch");
                assertTrue(correlations.stream().noneMatch(correlation ->
                                correlation.runtimeFrameId().equals(
                                        runtime.latestFrame().orElseThrow().frameId())),
                        "the latest frame carries no correlation under the binding's token, so "
                                + "the observation source cannot prove it (UNAVAILABLE)");
            }
            runtime.close();
        });
    }

    /**
     * Reversed drain/frame-order: the frame capture outpaced the correlation recording, so the
     * correlation describes a frame that is no longer the latest when the observation runs. The
     * adapter proves frames only by exact runtime-frame id, so the latest frame is unprovable:
     * it emits no observation and {@code ui_runtime_compare} reports {@code UNAVAILABLE} (never
     * {@code STALE}/{@code UNCORRELATED}) through that source.
     */
    @Test
    void correlationRecordedForAnOlderFrameLeavesTheLatestFrameUnprovable() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument document = markup.parse(
                    "<ui><table><textfield id=\"user\" data-runtime-entity=\"user\"/></table></ui>");
            BuiltUi built = MarkupBuilder.build(document, css.parse(""), skin, new NoopSink());
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of("runtime-test")).build();
            runtime.start();
            try (MarkupRuntimeSource source = MarkupRuntimeSource.registerWidgetMirror(
                    runtime, document, built, "markup-preview")) {
                assertEquals(List.of("user"), source.registeredEntities());
                runtime.beginFrame(Duration.ofMillis(16).toNanos());
                runtime.endFrame();
                runtime.uiCorrelations().recordFrame(new UiFrameCorrelation(
                        runtime.currentEpoch(),
                        runtime.latestFrame().orElseThrow().frameId(),
                        "markup-preview",
                        Optional.of("1"),
                        Optional.of("markup-preview-frame")));
                runtime.beginFrame(Duration.ofMillis(16).toNanos());
                runtime.endFrame();

                List<UiFrameCorrelation> correlations = runtime.uiCorrelations()
                        .framesForUiSession("markup-preview", 64).items();
                assertEquals(1, correlations.size(),
                        "only the first frame's correlation was recorded before the second capture");
                assertTrue(correlations.stream().noneMatch(correlation ->
                                correlation.runtimeFrameId().equals(
                                        runtime.latestFrame().orElseThrow().frameId())),
                        "no correlation references the latest frame, so the observation source "
                                + "cannot prove it (UNAVAILABLE) after a reversed frame order");
                assertTrue(correlations.stream().allMatch(correlation ->
                                correlation.correlationToken()
                                        .equals(Optional.of("markup-preview-frame"))),
                        "the recorded correlation still carries the matching token; the failure "
                                + "is the frame-order mismatch, not a token mismatch");
            }
            runtime.close();
        });
    }

    private static void assertNoRuntimeState(AgentRuntime runtime) {
        assertTrue(runtime.uiCorrelations().list().isEmpty(),
                "no UI bindings may remain after a failed registration");
        runtime.beginFrame(Duration.ofMillis(16).toNanos());
        runtime.endFrame();
        FrameSnapshot frame = runtime.latestFrame().orElseThrow();
        assertTrue(frame.entities().isEmpty(),
                "no entities may remain after a failed registration");
    }
}
