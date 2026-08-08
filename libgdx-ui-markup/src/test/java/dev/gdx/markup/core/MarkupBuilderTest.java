package dev.gdx.markup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import dev.gdx.markup.core.style.CssDocument;
import dev.gdx.markup.core.style.CssParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Render-thread builder tests; run with {@code xvfb-run} for a real GL context. */
final class MarkupBuilderTest {
    private final MarkupParser markup = new MarkupParser();
    private final CssParser css = new CssParser();

    @Test
    void buildsSigninSampleIntoExpectedTree() throws Exception {
        GdxTestHost.run(() -> {
            BuiltUi built = buildSample();
            Group root = built.root();
            assertFalse(root instanceof Table, "a <ui> root produces a plain root group");
            Table panel = (Table) root.getChildren().first();
            assertEquals("signin-panel", panel.getName());
            assertEquals(1, panel.getChildren().size);
            Window window = (Window) panel.getChildren().first();
            assertEquals("Sign in", window.getTitleLabel().getText().toString());
            assertEquals("signin-window", window.getName());

            Table form = (Table) window.findActor("signin-form");
            assertEquals(7, form.getChildren().size);
            Label title = (Label) form.findActor("signin-title");
            assertEquals("Sign in", title.getText().toString());

            TextButton save = (TextButton) form.findActor("save");
            assertEquals("Save", save.getText().toString());
            assertEquals("save", save.getName());

            CheckBox remember = (CheckBox) form.findActor("remember");
            assertEquals("Remember me", remember.getText().toString());
            assertFalse(remember.isChecked(), "checkbox starts unchecked");

            assertEquals(10, built.actors().size(),
                    "actors include containers before their children, in document order");
            assertEquals("save", built.actors().get(9).getName());
        });
    }

    @Test
    void sinkReceivesSemanticsByConstruction() throws Exception {
        GdxTestHost.run(() -> {
            FakeSink sink = new FakeSink();
            MarkupBuilder.build(markup.parse("""
                    <ui>
                      <table>
                        <button id="save" name="Save" label="Save button" data-kind="action"/>
                        <checkbox id="remember"/>
                      </table>
                    </ui>
                    """), css.parse(""), DefaultSkin.create(), sink);
            assertEquals("save", sink.testIds.get("save"));
            assertEquals("Save", sink.accessibleNames.get("save"));
            assertEquals("Save button", sink.labels.get("save"));
            assertEquals("button", sink.roles.get("save"));
            assertEquals("checkbox", sink.roles.get("remember"));
            assertEquals("action", sink.properties.get("save").get("kind"));
            assertNull(sink.roles.get("table"), "tables emit no role");
        });
    }

    @Test
    void cellConstraintsAreApplied() throws Exception {
        GdxTestHost.run(() -> {
            BuiltUi built = MarkupBuilder.build(markup.parse("""
                    <ui>
                      <table id="grid">
                        <row/>
                        <button id="a" expand="x" fill="y" align="right" pad="4"
                            pad-top="2" width="80" min-width="40" colspan="2" uniform="true"/>
                        <button id="b" grow="true" space="6" height="30"/>
                      </table>
                    </ui>
                    """), css.parse(""), DefaultSkin.create(), new NoopSink());
            Table table = (Table) built.root().getChildren().first();
            table.validate();
            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cellA = table.getCells().get(0);
            assertEquals(1, cellA.getExpandX());
            assertEquals(1f, cellA.getFillY());
            assertTrue((cellA.getAlign() & com.badlogic.gdx.utils.Align.right) != 0,
                    "align right is applied to the cell");
            assertEquals(2f, cellA.getPadTop(), 0.001);
            assertEquals(4f, cellA.getPadLeft(), 0.001);
            assertEquals(40f, cellA.getMinWidth(), 0.001);
            assertEquals(2, cellA.getColspan());
            assertTrue(cellA.getUniformX());

            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cellB = table.getCells().get(1);
            assertEquals(1, cellB.getExpandX());
            assertEquals(1, cellB.getExpandY());
            assertEquals(6f, cellB.getSpaceTop(), 0.001);
        });
    }

    @Test
    void checkboxCheckedAttributeApplies() throws Exception {
        GdxTestHost.run(() -> {
            BuiltUi built = MarkupBuilder.build(markup.parse(
                    "<ui><checkbox id=\"c\" checked=\"true\"/></ui>"),
                    css.parse(""), DefaultSkin.create(), new NoopSink());
            assertTrue(((CheckBox) built.root().getChildren().first()).isChecked());
        });
    }

    @Test
    void textfieldEditableFalseDisablesField() throws Exception {
        GdxTestHost.run(() -> {
            BuiltUi built = MarkupBuilder.build(markup.parse(
                    "<ui><textfield id=\"t\" editable=\"false\"/></ui>"),
                    css.parse(""), DefaultSkin.create(), new NoopSink());
            assertTrue(((TextField) built.root().getChildren().first()).isDisabled());
        });
    }

    @Test
    void disabledAndHiddenActorsAreConfigured() throws Exception {
        GdxTestHost.run(() -> {
            BuiltUi built = MarkupBuilder.build(markup.parse(
                    "<ui><button id=\"d\" disabled=\"true\" visible=\"false\"/></ui>"),
                    css.parse(""), DefaultSkin.create(), new NoopSink());
            TextButton button = (TextButton) built.root().getChildren().first();
            assertTrue(button.isDisabled());
            assertFalse(button.isVisible());
        });
    }

    @Test
    void unknownStyleNameFailsWithLocation() throws Exception {
        GdxTestHost.run(() -> {
            MarkupException failure = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse("<ui><button id=\"b\" style=\"missing\"/>"
                            + "</ui>"), css.parse(""), DefaultSkin.create(), new NoopSink()));
            assertEquals(MarkupException.Kind.UNRESOLVED_STYLE, failure.kind());
            assertEquals("ui/button", failure.elementPath());
            assertTrue(failure.getMessage().contains("missing"));
        });
    }

    @Test
    void secondBranchErrorPathIsParentScopedWithoutDoubleEnter() throws Exception {
        GdxTestHost.run(() -> {
            MarkupException failure = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse("""
                            <ui>
                              <table id="one">
                                <button id="a"/>
                              </table>
                              <table id="two">
                                <button id="b" class="bad"/>
                              </table>
                            </ui>
                            """), css.parse(".bad { font-color: missing; }"),
                            DefaultSkin.create(), new NoopSink()));
            assertEquals(MarkupException.Kind.UNRESOLVED_STYLE, failure.kind());
            assertEquals("ui/table[1]/button", failure.elementPath(),
                    "the second table's first button is indexed by its parent, and the "
                            + "error helper must not enter the current element a second time");
        });
    }

    @Test
    void missingDrawableFails() throws Exception {
        GdxTestHost.run(() -> {
            MarkupException failure = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse("<ui><image drawable=\"nope\"/></ui>"),
                            css.parse(""), DefaultSkin.create(), new NoopSink()));
            assertEquals(MarkupException.Kind.UNRESOLVED_STYLE, failure.kind());
            assertTrue(failure.getMessage().contains("nope"));
        });
    }

    @Test
    void rowOutsideTableFails() throws Exception {
        GdxTestHost.run(() -> {
            MarkupException failure = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse("<ui><row/></ui>"),
                            css.parse(""), DefaultSkin.create(), new NoopSink()));
            assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
            assertTrue(failure.getMessage().contains("row"));
        });
    }

    @Test
    void scrollpaneRequiresExactlyOneChild() throws Exception {
        GdxTestHost.run(() -> {
            MarkupException failure = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse(
                            "<ui><scrollpane><button/><button/></scrollpane></ui>"),
                            css.parse(""), DefaultSkin.create(), new NoopSink()));
            assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
            assertTrue(failure.getMessage().contains("one child"));
        });
    }

    @Test
    void cssBackgroundCompilesIntoSkinStyle() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupBuilder.build(markup.parse(
                    "<ui><button id=\"b\" class=\"primary\"/></ui>"),
                    css.parse("button.primary { background: accent; }\n"
                            + "button.primary:hover { background: accent-over; }"),
                    skin, new NoopSink());
            TextButton.TextButtonStyle primary =
                    skin.get("button.primary", TextButton.TextButtonStyle.class);
            assertNotNull(primary, "CSS creates the tag.class style");
            assertSame(skin.getDrawable("accent"), primary.up);
            assertSame(skin.getDrawable("accent-over"), primary.over);
            TextButton button = (TextButton) MarkupBuilder.build(markup.parse(
                    "<ui><button id=\"b\" class=\"primary\"/></ui>"),
                    css.parse(""), skin, new NoopSink()).root().getChildren().first();
            assertSame(primary, button.getStyle(), "the element resolves the CSS style");
        });
    }

    @Test
    void cssPaddingAppliesToTableAndCell() throws Exception {
        GdxTestHost.run(() -> {
            BuiltUi built = MarkupBuilder.build(markup.parse("""
                    <ui>
                      <table id="panel" class="panel">
                        <button id="inner" class="padded"/>
                      </table>
                    </ui>
                    """), css.parse(".panel { padding: 28px; }\n"
                            + ".padded { padding: 6px; margin: 3px; }"),
                    DefaultSkin.create(), new NoopSink());
            Table panel = (Table) built.root().getChildren().first();
            panel.validate();
            assertEquals(28f, panel.getPadTop(), 0.001, "root table padding from CSS");
            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell =
                    panel.getCells().first();
            assertEquals(6f, cell.getPadTop(), 0.001);
            assertEquals(3f, cell.getSpaceTop(), 0.001, "CSS margin maps to cell space");
        });
    }

    @Test
    void cssPerActorColorAppliesToLabel() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            BuiltUi built = MarkupBuilder.build(markup.parse(
                    "<ui><label id=\"t\" class=\"title\">Sign in</label></ui>"),
                    css.parse(".title { font-color: accent; }"), skin, new NoopSink());
            Label label = (Label) built.root().getChildren().first();
            Color expected = skin.getColor("accent");
            assertEquals(expected, label.getStyle().fontColor);
            assertFalse(label.getStyle() == skin.get("label", Label.LabelStyle.class),
                    "per-actor overrides clone the shared style");
        });
    }

    @Test
    void customFactoryExtendsTheVocabulary() throws Exception {
        GdxTestHost.run(() -> {
            MarkupRegistry registry = MarkupRegistry.defaultRegistry();
            registry.register("badge", (element, context) -> {
                Label label = new Label("B", context.resolveStyle(Label.LabelStyle.class));
                label.setName("badge:" + element.id());
                return label;
            });
            MarkupParser withBadge = new MarkupParser(java.util.Set.of("badge"));
            BuiltUi built = MarkupBuilder.build(withBadge.parse(
                    "<ui><badge id=\"b1\"/></ui>"), css.parse(""), DefaultSkin.create(),
                    new NoopSink(), registry);
            Label badge = (Label) built.root().getChildren().first();
            assertEquals("B", badge.getText().toString(), "the custom factory ran");
            assertEquals("b1", badge.getName(), "the common id pipeline still applies");
        });
    }

    @Test
    void cssVisibleAndWidthApplyOutsideTables() throws Exception {
        GdxTestHost.run(() -> {
            BuiltUi built = MarkupBuilder.build(markup.parse(
                    "<ui><button id=\"b\"/></ui>"),
                    css.parse("button { visible: false; width: 120px; }"),
                    DefaultSkin.create(), new NoopSink());
            TextButton button = (TextButton) built.root().getChildren().first();
            assertFalse(button.isVisible());
            assertEquals(120f, button.getWidth(), 0.001);
        });
    }

    @Test
    void windowInsideTableGetsCellConstraints() throws Exception {
        GdxTestHost.run(() -> {
            BuiltUi built = MarkupBuilder.build(markup.parse("""
                    <ui>
                      <table id="outer">
                        <window id="w" title="T" expand="true" fill="true"/>
                      </table>
                    </ui>
                    """), css.parse(""), DefaultSkin.create(), new NoopSink());
            Table outer = (Table) built.root().getChildren().first();
            outer.validate();
            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell = outer.getCells().first();
            assertEquals(1, cell.getExpandX());
            assertEquals(1, cell.getExpandY());
            assertEquals(1, cell.getFillX());
            assertEquals(1, cell.getFillY());
        });
    }

    @Test
    void selectboxAndListReceiveItems() throws Exception {
        GdxTestHost.run(() -> {
            BuiltUi built = MarkupBuilder.build(markup.parse("""
                    <ui>
                      <selectbox id="s" items="Alpha, Beta,Gamma"/>
                      <list id="l" items="One,Two"/>
                    </ui>
                    """), css.parse(""), DefaultSkin.create(), new NoopSink());
            com.badlogic.gdx.scenes.scene2d.ui.SelectBox<?> box =
                    (com.badlogic.gdx.scenes.scene2d.ui.SelectBox<?>)
                            built.root().getChildren().get(0);
            assertEquals(3, box.getItems().size);
            assertEquals("Alpha", box.getItems().get(0));
            assertEquals("Beta", box.getItems().get(1));
            assertEquals("Gamma", box.getItems().get(2));
            com.badlogic.gdx.scenes.scene2d.ui.List<?> list =
                    (com.badlogic.gdx.scenes.scene2d.ui.List<?>)
                            built.root().getChildren().get(1);
            assertEquals(2, list.getItems().size);
        });
    }

    private BuiltUi buildSample() throws Exception {
        Skin skin = DefaultSkin.create();
        String samplesDir = System.getProperty("markup.samples.dir");
        if (samplesDir == null || samplesDir.isBlank()) {
            throw new IllegalStateException("Gradle did not provide markup.samples.dir");
        }
        Path samples = Path.of(samplesDir);
        MarkupDocument document = markup.parse(Files.readString(
                samples.resolve("signin.xml"), StandardCharsets.UTF_8));
        CssDocument styles = css.parse(Files.readString(
                samples.resolve("signin.css"), StandardCharsets.UTF_8));
        return MarkupBuilder.build(document, styles, skin, new NoopSink());
    }

    /** Records every semantic call for assertion. */
    private static final class FakeSink implements SemanticSink {
        private final Map<String, String> roles = new LinkedHashMap<>();
        private final Map<String, String> accessibleNames = new LinkedHashMap<>();
        private final Map<String, String> testIds = new LinkedHashMap<>();
        private final Map<String, String> labels = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> properties = new LinkedHashMap<>();

        @Override public void role(Actor actor, String role) {
            roles.put(actor.getName(), role);
        }

        @Override public void accessibleName(Actor actor, String name) {
            accessibleNames.put(actor.getName(), name);
        }

        @Override public void testId(Actor actor, String id) {
            testIds.put(id, id);
        }

        @Override public void label(Actor actor, String label) {
            labels.put(actor.getName(), label);
        }

        @Override public void property(Actor actor, String key, String value) {
            properties.computeIfAbsent(actor.getName(), ignored -> new LinkedHashMap<>())
                    .put(key, value);
        }
    }
}
