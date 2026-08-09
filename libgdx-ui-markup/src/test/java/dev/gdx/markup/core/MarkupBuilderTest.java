package dev.gdx.markup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
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
    void cascadeLimitReportsFullTrackedPath() throws Exception {
        GdxTestHost.run(() -> {
            MarkupException failure = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse("""
                            <ui>
                              <table>
                                <button/>
                                <button/>
                              </table>
                            </ui>
                            """), css.parse("button { width: 1px; }\nlabel { height: 2px; }"),
                            DefaultSkin.create(), new NoopSink(), MarkupRegistry.defaultRegistry(),
                            dev.gdx.markup.core.style.CssStyleResolver.MAX_COMPARISONS_PER_RESOLVE,
                            5));
            assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
            assertEquals("ui/table/button[1]", failure.elementPath(),
                    "cascade limit failures carry the full tracked path of the element "
                            + "being resolved");
        });
    }

    @Test
    void builderElementPathsAreScopedPerParent() throws Exception {
        GdxTestHost.run(() -> {
            MarkupException firstTableFirstButton = buildWithInvalidPad(
                    table(button("a", invalidPad()), button("b", Map.of())),
                    table(button("c", Map.of()), button("d", Map.of())));
            assertEquals(MarkupException.Kind.INVALID_VALUE, firstTableFirstButton.kind());
            assertEquals("ui/table/button", firstTableFirstButton.elementPath());

            MarkupException firstTableSecondButton = buildWithInvalidPad(
                    table(button("a", Map.of()), button("b", invalidPad())),
                    table(button("c", Map.of()), button("d", Map.of())));
            assertEquals("ui/table/button[1]", firstTableSecondButton.elementPath());

            MarkupException secondTableFirstButton = buildWithInvalidPad(
                    table(button("a", Map.of()), button("b", Map.of())),
                    table(button("c", invalidPad()), button("d", Map.of())));
            assertEquals("ui/table[1]/button", secondTableFirstButton.elementPath());

            MarkupException secondTableSecondButton = buildWithInvalidPad(
                    table(button("a", Map.of()), button("b", Map.of())),
                    table(button("c", Map.of()), button("d", invalidPad())));
            assertEquals("ui/table[1]/button[1]", secondTableSecondButton.elementPath());
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
    void xmlAndCssFontSizesProduceCachedExactSizeFonts() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            BuiltUi built = MarkupBuilder.build(markup.parse("""
                    <ui>
                      <label id="small" font-size="14" text="A"/>
                      <label id="large" class="large" font="inter" text="B"/>
                      <label id="large-again" class="large" text="C"/>
                      <label id="xml-wins" class="large" font-size="20" text="D"/>
                    </ui>
                    """), css.parse(".large { font: inter; font-size: 28px; }"),
                    skin, new NoopSink());

            Label small = built.root().findActor("small");
            Label large = built.root().findActor("large");
            Label largeAgain = built.root().findActor("large-again");
            Label xmlWins = built.root().findActor("xml-wins");
            assertTrue(large.getStyle().font.getLineHeight()
                    > small.getStyle().font.getLineHeight());
            assertSame(large.getStyle().font, largeAgain.getStyle().font,
                    "the family/size cache is shared within the skin");
            assertTrue(large.getStyle().font.getLineHeight()
                    > xmlWins.getStyle().font.getLineHeight(),
                    "the XML size independently overrides the CSS size");

            skin.dispose();
        });
    }

    @Test
    void generatedFontReachesEveryTextBearingWidgetWithoutMutatingSharedStyles()
            throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            BitmapFont shared = skin.get("label", Label.LabelStyle.class).font;
            BuiltUi built = MarkupBuilder.build(markup.parse("""
                    <ui>
                      <label id="label" font-size="24" text="Label"/>
                      <button id="button" font-size="24" text="Button"/>
                      <checkbox id="checkbox" font-size="24" text="Checkbox"/>
                      <textfield id="field" font-size="24" text="Field"/>
                      <selectbox id="select" font-size="24" items="One,Two"/>
                      <window id="window" font-size="24" title="Window"/>
                      <list id="list" font-size="24" items="One,Two"/>
                    </ui>
                    """), css.parse(""), skin, new NoopSink());

            BitmapFont generated = ((Label) built.root().findActor("label")).getStyle().font;
            assertNotSame(shared, generated);
            assertSame(generated,
                    ((TextButton) built.root().findActor("button")).getStyle().font);
            assertSame(generated,
                    ((CheckBox) built.root().findActor("checkbox")).getStyle().font);
            TextField field = built.root().findActor("field");
            assertSame(generated, field.getStyle().font);
            assertSame(generated, field.getStyle().messageFont);
            SelectBox<?> select = built.root().findActor("select");
            assertSame(generated, select.getStyle().font);
            assertSame(generated, select.getList().getStyle().font);
            assertSame(generated,
                    ((Window) built.root().findActor("window")).getStyle().titleFont);
            assertSame(generated,
                    ((com.badlogic.gdx.scenes.scene2d.ui.List<?>) built.root().findActor("list"))
                            .getStyle().font);
            assertSame(shared, skin.get("label", Label.LabelStyle.class).font,
                    "shared skin styles remain unchanged");
            assertNotSame(skin.get("selectbox-list",
                            com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle.class),
                    select.getStyle().listStyle, "the nested list style is copied");

            skin.dispose();
        });
    }

    @Test
    void fontWithoutSizeKeepsNamedSkinFontCompatibility() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            BitmapFont named = new BitmapFont();
            skin.add("legacy-font", named, BitmapFont.class);

            BuiltUi built = MarkupBuilder.build(markup.parse(
                    "<ui><label id=\"label\" font=\"legacy-font\" text=\"Text\"/></ui>"),
                    css.parse(""), skin, new NoopSink());

            assertSame(named, ((Label) built.root().findActor("label")).getStyle().font);
            skin.dispose();
        });
    }

    @Test
    void unrelatedPseudoSelectorInACommaGroupDoesNotRejectBaseFont() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            BitmapFont named = new BitmapFont();
            skin.add("legacy-font", named, BitmapFont.class);

            BuiltUi built = MarkupBuilder.build(markup.parse(
                            "<ui><label id=\"label\" class=\"target\" text=\"Text\"/></ui>"),
                    css.parse(".other:hover, .target { font: legacy-font; }"),
                    skin, new NoopSink());

            assertSame(named, ((Label) built.root().findActor("label")).getStyle().font);
            skin.dispose();
        });
    }

    @Test
    void matchingTaglessPseudoFontStillFailsLocated() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupException failure = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse(
                                    "<ui><label class=\"target\" text=\"Text\"/></ui>"),
                            css.parse(".target:hover { font: default-font; }"),
                            skin, new NoopSink()));
            assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
            assertEquals("css", failure.elementPath());
            assertEquals(1, failure.line());
            assertEquals(1, failure.column());
            skin.dispose();
        });
    }

    @Test
    void unresolvedFreeTypeConfigurationProducesLocatedDiagnostics() throws Exception {
        GdxTestHost.run(() -> {
            Skin bare = new Skin();
            BitmapFont font = new BitmapFont();
            bare.add("default-font", font, BitmapFont.class);
            bare.add("label", new Label.LabelStyle(font, Color.WHITE));
            bare.add("default", new Label.LabelStyle(font, Color.WHITE));
            MarkupException missingManager = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse(
                                    "<ui><label font-size=\"20\" text=\"Text\"/></ui>"),
                            css.parse(""), bare, new NoopSink()));
            assertEquals(MarkupException.Kind.UNRESOLVED_STYLE, missingManager.kind());
            assertEquals("ui/label", missingManager.elementPath());
            bare.dispose();

            Skin skin = DefaultSkin.create();
            MarkupException unknownFamily = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse(
                                    "<ui><label font=\"missing\" font-size=\"20\" text=\"Text\"/>"
                                            + "</ui>"),
                            css.parse(""), skin, new NoopSink()));
            assertEquals(MarkupException.Kind.UNRESOLVED_STYLE, unknownFamily.kind());
            assertEquals("ui/label", unknownFamily.elementPath());
            skin.dispose();
        });
    }

    @Test
    void reservedFontCollisionIsLocatedButManagerLifecycleFailurePropagates() throws Exception {
        GdxTestHost.run(() -> {
            Skin collisionSkin = DefaultSkin.create();
            BitmapFont reserved = new BitmapFont();
            collisionSkin.add("__markup-freetype-font-inter-24", reserved, BitmapFont.class);
            MarkupException collision = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse(
                                    "<ui><label font-size=\"24\" text=\"Text\"/></ui>"),
                            css.parse(""), collisionSkin, new NoopSink()));
            assertEquals(MarkupException.Kind.UNRESOLVED_STYLE, collision.kind());
            assertEquals("ui/label", collision.elementPath());
            collisionSkin.dispose();

            Skin disposedSkin = DefaultSkin.create();
            FreeTypeFontManager.optional(disposedSkin).dispose();
            IllegalStateException lifecycle = assertThrows(IllegalStateException.class, () ->
                    MarkupBuilder.build(markup.parse(
                                    "<ui><label font-size=\"24\" text=\"Text\"/></ui>"),
                            css.parse(""), disposedSkin, new NoopSink()));
            assertTrue(lifecycle.getMessage().contains("disposed"));
            disposedSkin.dispose();
        });
    }

    @Test
    void sixtyFifthDistinctGeneratedFontFailsAtTheTriggeringElement() throws Exception {
        GdxTestHost.run(() -> {
            StringBuilder xml = new StringBuilder("<ui>");
            for (int size = 4; size <= 68; size++) {
                xml.append("<label font-size=\"").append(size).append("\" text=\"Text\"/>");
            }
            xml.append("</ui>");
            Skin skin = DefaultSkin.create();

            MarkupException failure = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse(xml.toString()), css.parse(""),
                            skin, new NoopSink()));

            assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
            assertEquals("ui/label[64]", failure.elementPath());
            assertTrue(failure.getMessage().contains("64-font limit"));
            skin.dispose();
        });
    }

    @Test
    void fontSizeOnANonTextWidgetDoesNotConsumeTheFontCache() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = new Skin();
            FileHandle inter = Gdx.files.classpath("META-INF/fonts/Inter-Regular.ttf");
            FreeTypeFontManager fonts = FreeTypeFontManager.install(skin, "inter",
                    Map.of("inter", inter), "abc", 1f, 1, 2);
            Slider.SliderStyle sliderStyle = new Slider.SliderStyle();
            skin.add("slider", sliderStyle);
            skin.add("default", new Slider.SliderStyle(sliderStyle));

            MarkupBuilder.build(markup.parse(
                            "<ui><slider min=\"0\" max=\"1\"/></ui>"),
                    css.parse("slider { font-size: 20px; }"), skin, new NoopSink());

            assertSame(fonts.font("inter", 18), fonts.font("inter", 18),
                    "non-text CSS must not allocate an unused exact-size font");
            skin.dispose();
        });
    }

    @Test
    void cssCompiledPseudoFontColorIsStateSpecific() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            BuiltUi built = MarkupBuilder.build(markup.parse("<ui><checkbox id=\"c\"/></ui>"),
                    css.parse("checkbox:hover { font-color: accent; }"), skin, new NoopSink());
            CheckBox checkBox = (CheckBox) built.root().getChildren().first();
            CheckBox.CheckBoxStyle style = checkBox.getStyle();
            assertEquals(skin.getColor("accent"), style.overFontColor,
                    "hover compiles into the over font color");
            assertEquals(skin.get("default", CheckBox.CheckBoxStyle.class).fontColor,
                    style.fontColor, "the base font color is unchanged");
            assertNull(style.downFontColor, "pressed stays untouched");
            assertNull(style.checkedFontColor, "checked stays untouched");
            assertEquals(skin.get("default", CheckBox.CheckBoxStyle.class).disabledFontColor,
                    style.disabledFontColor, "the disabled font color is unchanged");
        });
    }

    @Test
    void cssClassPseudoFontColorAppliesPerActorWithoutMutatingSkin() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            BuiltUi built = MarkupBuilder.build(markup.parse(
                    "<ui><button id=\"b\" class=\"warning\"/></ui>"),
                    css.parse(".warning:hover { font-color: accent; }"), skin, new NoopSink());
            TextButton button = (TextButton) built.root().getChildren().first();
            TextButton.TextButtonStyle style = button.getStyle();
            assertEquals(skin.getColor("accent"), style.overFontColor,
                    "class hover maps to the over font color");
            assertEquals(skin.get("button", TextButton.TextButtonStyle.class).fontColor,
                    style.fontColor, "the base font color is unchanged");
            assertNull(style.downFontColor, "only the hover field changes");
            assertNotSame(skin.get("button", TextButton.TextButtonStyle.class), style,
                    "the actor owns a per-actor clone");
            assertNull(skin.get("button", TextButton.TextButtonStyle.class).overFontColor,
                    "the shared skin style is never mutated");
        });
    }

    @Test
    void cssIdPseudoFontColorAppliesPerActor() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            BuiltUi built = MarkupBuilder.build(markup.parse(
                    "<ui><button id=\"save\"/></ui>"),
                    css.parse("#save:disabled { font-color: accent; }"), skin, new NoopSink());
            TextButton button = (TextButton) built.root().getChildren().first();
            TextButton.TextButtonStyle style = button.getStyle();
            assertEquals(skin.getColor("accent"), style.disabledFontColor,
                    "id disabled maps to the disabled font color");
            assertEquals(skin.get("button", TextButton.TextButtonStyle.class).fontColor,
                    style.fontColor, "the base font color is unchanged");
            assertNull(style.overFontColor, "only the disabled field changes");
            assertNotSame(skin.get("button", TextButton.TextButtonStyle.class), style,
                    "the actor owns a per-actor clone");
            assertNull(skin.get("button", TextButton.TextButtonStyle.class).disabledFontColor,
                    "the shared skin style is never mutated");
        });
    }

    @Test
    void cssUnsupportedPseudoCombinationFailsLocatedAtSelector() throws Exception {
        GdxTestHost.run(() -> {
            MarkupException failure = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse("<ui><label id=\"l\"/></ui>"),
                            css.parse("button { padding: 4px; }\n"
                                    + "label:hover { font-color: accent; }\n"),
                            DefaultSkin.create(), new NoopSink()));
            assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
            assertEquals("css", failure.elementPath());
            assertEquals(2, failure.line(), "selector coordinates, not the rule index");
            assertEquals(1, failure.column());
            assertTrue(failure.getMessage().contains("label"));
            assertTrue(failure.getMessage().contains("hover"));
        });
    }

    @Test
    void cssTaglessUnsupportedPseudoCombinationFailsLocatedAtSelector() throws Exception {
        GdxTestHost.run(() -> {
            MarkupException failure = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse(
                            "<ui><label id=\"l\" class=\"warning\"/></ui>"),
                            css.parse("button { padding: 4px; }\n"
                                    + ".warning:hover { font-color: accent; }\n"),
                            DefaultSkin.create(), new NoopSink()));
            assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
            assertEquals("css", failure.elementPath());
            assertEquals(2, failure.line(), "selector coordinates from the source rule");
            assertEquals(1, failure.column());
            assertTrue(failure.getMessage().contains("label"));
            assertTrue(failure.getMessage().contains("hover"));
        });
    }

    @Test
    void cssTaglessPseudoCombinationOnWindowFailsLocated() throws Exception {
        GdxTestHost.run(() -> {
            MarkupException failure = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse(
                            "<ui><window class=\"panel\" title=\"T\"/></ui>"),
                            css.parse("button { padding: 4px; }\n"
                                    + ".panel:hover { font-color: accent; }\n"),
                            DefaultSkin.create(), new NoopSink()));
            assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
            assertEquals("css", failure.elementPath());
            assertEquals(2, failure.line(), "selector coordinates from the source rule");
            assertEquals(1, failure.column());
            assertTrue(failure.getMessage().contains("window"));
            assertTrue(failure.getMessage().contains("hover"));
        });
    }

    @Test
    void cssTaglessPseudoCombinationOnTableFailsLocated() throws Exception {
        GdxTestHost.run(() -> {
            MarkupException failure = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse(
                            "<ui><table class=\"panel\" id=\"t\"><button id=\"b\"/></table></ui>"),
                            css.parse("button { padding: 4px; }\n"
                                    + ".panel:hover { font-color: accent; }\n"),
                            DefaultSkin.create(), new NoopSink()));
            assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
            assertEquals("css", failure.elementPath());
            assertEquals(2, failure.line(), "selector coordinates from the source rule");
            assertEquals(1, failure.column());
            assertTrue(failure.getMessage().contains("table"));
            assertTrue(failure.getMessage().contains("hover"));
        });
    }

    @Test
    void cssTaglessPseudoWithoutStateFieldsBuildsForContainers() throws Exception {
        GdxTestHost.run(() -> {
            // No container style has state fields, so no container pseudo state is
            // representable; a tagless pseudo rule carrying only non-state properties must
            // neither error nor disturb the base build.
            BuiltUi built = MarkupBuilder.build(markup.parse(
                    "<ui><window class=\"panel\" title=\"T\"/></ui>"),
                    css.parse(".panel:hover { padding: 4px; }\n.panel { padding: 8px; }"),
                    DefaultSkin.create(), new NoopSink());
            Window window = (Window) built.root().getChildren().first();
            window.validate();
            assertEquals(8f, window.getPadTop(), 0.001, "base padding still applies");
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
    void sliderRangeAndStepAreValidated() throws Exception {
        GdxTestHost.run(() -> {
            MarkupException range = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse("""
                            <ui>
                              <slider id="s" min="2" max="1"/>
                            </ui>
                            """), css.parse(""), DefaultSkin.create(), new NoopSink()));
            assertEquals(MarkupException.Kind.INVALID_VALUE, range.kind());
            assertEquals("ui/slider", range.elementPath());
            assertEquals(2, range.line());
            // JDK SAX reports the column just past the scanned start tag; assert presence.
            assertTrue(range.column() >= 5);
            assertTrue(range.getMessage().contains("min"),
                    "the message names the conflicting fields");
            assertTrue(range.getMessage().contains("max"),
                    "the message names the conflicting fields");

            MarkupException step = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse("""
                            <ui>
                              <slider id="s" min="0" max="1" step="0"/>
                            </ui>
                            """), css.parse(""), DefaultSkin.create(), new NoopSink()));
            assertEquals(MarkupException.Kind.INVALID_VALUE, step.kind());
            assertEquals("ui/slider", step.elementPath());
            assertEquals(2, step.line());
            assertTrue(step.getMessage().contains("step"),
                    "the message names the conflicting fields");
        });
    }

    @Test
    void progressBarRangeIsValidated() throws Exception {
        GdxTestHost.run(() -> {
            MarkupException failure = assertThrows(MarkupException.class, () ->
                    MarkupBuilder.build(markup.parse("""
                            <ui>
                              <progressbar id="p" min="2" max="1"/>
                            </ui>
                            """), css.parse(""), DefaultSkin.create(), new NoopSink()));
            assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
            assertEquals("ui/progressbar", failure.elementPath());
            assertEquals(2, failure.line());
            // JDK SAX reports the column just past the scanned start tag; assert presence.
            assertTrue(failure.column() >= 5);
            assertTrue(failure.getMessage().contains("min"),
                    "the message names the conflicting fields");
            assertTrue(failure.getMessage().contains("max"),
                    "the message names the conflicting fields");
        });
    }

    @Test
    void falseLayoutAxisValuesDisableBothAxes() throws Exception {
        GdxTestHost.run(() -> {
            BuiltUi built = MarkupBuilder.build(markup.parse("""
                    <ui>
                      <table id="grid">
                        <button id="a" expand="false"/>
                        <button id="b" fill="false"/>
                        <button id="c" grow="false"/>
                      </table>
                    </ui>
                    """), css.parse(""), DefaultSkin.create(), new NoopSink());
            Table table = (Table) built.root().getChildren().first();
            table.validate();
            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cellA = table.getCells().get(0);
            assertEquals(0, cellA.getExpandX(), "expand=false enables neither axis");
            assertEquals(0, cellA.getExpandY(), "expand=false enables neither axis");
            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cellB = table.getCells().get(1);
            assertEquals(0f, cellB.getFillX(), "fill=false enables neither axis");
            assertEquals(0f, cellB.getFillY(), "fill=false enables neither axis");
            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cellC = table.getCells().get(2);
            assertEquals(0, cellC.getExpandX(), "grow=false enables neither axis");
            assertEquals(0, cellC.getExpandY(), "grow=false enables neither axis");
            assertEquals(0f, cellC.getFillX(), "grow=false enables neither axis");
            assertEquals(0f, cellC.getFillY(), "grow=false enables neither axis");
        });
    }

    @Test
    void booleanAndAxisLayoutValuesPreserveBehavior() throws Exception {
        GdxTestHost.run(() -> {
            BuiltUi built = MarkupBuilder.build(markup.parse("""
                    <ui>
                      <table id="grid">
                        <button id="a" expand="true"/>
                        <button id="b" expand="x"/>
                        <button id="c" expand="y"/>
                        <button id="d" fill="true"/>
                        <button id="e" fill="x"/>
                        <button id="f" fill="y"/>
                        <button id="g" grow="true"/>
                        <button id="h" grow="x"/>
                        <button id="i" grow="y"/>
                      </table>
                    </ui>
                    """), css.parse(""), DefaultSkin.create(), new NoopSink());
            Table table = (Table) built.root().getChildren().first();
            table.validate();
            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> c0 = table.getCells().get(0);
            assertEquals(1, c0.getExpandX(), "expand=true enables both axes");
            assertEquals(1, c0.getExpandY(), "expand=true enables both axes");
            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> c1 = table.getCells().get(1);
            assertEquals(1, c1.getExpandX(), "expand=x enables x only");
            assertEquals(0, c1.getExpandY(), "expand=x enables x only");
            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> c2 = table.getCells().get(2);
            assertEquals(0, c2.getExpandX(), "expand=y enables y only");
            assertEquals(1, c2.getExpandY(), "expand=y enables y only");
            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> c3 = table.getCells().get(3);
            assertEquals(1f, c3.getFillX(), "fill=true enables both axes");
            assertEquals(1f, c3.getFillY(), "fill=true enables both axes");
            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> c4 = table.getCells().get(4);
            assertEquals(1f, c4.getFillX(), "fill=x enables x only");
            assertEquals(0f, c4.getFillY(), "fill=x enables x only");
            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> c5 = table.getCells().get(5);
            assertEquals(0f, c5.getFillX(), "fill=y enables y only");
            assertEquals(1f, c5.getFillY(), "fill=y enables y only");
            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> c6 = table.getCells().get(6);
            assertEquals(1, c6.getExpandX(), "grow=true expands both axes");
            assertEquals(1, c6.getExpandY(), "grow=true expands both axes");
            assertEquals(1f, c6.getFillX(), "grow=true fills both axes");
            assertEquals(1f, c6.getFillY(), "grow=true fills both axes");
            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> c7 = table.getCells().get(7);
            assertEquals(1, c7.getExpandX(), "grow=x expands x only");
            assertEquals(0, c7.getExpandY(), "grow=x expands x only");
            assertEquals(1f, c7.getFillX(), "grow=x fills x only");
            assertEquals(0f, c7.getFillY(), "grow=x fills x only");
            com.badlogic.gdx.scenes.scene2d.ui.Cell<?> c8 = table.getCells().get(8);
            assertEquals(0, c8.getExpandX(), "grow=y expands y only");
            assertEquals(1, c8.getExpandY(), "grow=y expands y only");
            assertEquals(0f, c8.getFillX(), "grow=y fills y only");
            assertEquals(1f, c8.getFillY(), "grow=y fills y only");
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

    @Test
    void defaultSkinDisposesUploadPixmapOnceAndSkinOwnsTexture() throws Exception {
        GdxTestHost.run(() -> {
            List<TrackingPixmap> pixmaps = new ArrayList<>();
            List<TrackingTexture> textures = new ArrayList<>();
            List<Skin> skins = new ArrayList<>();
            for (int round = 0; round < 2; round++) {
                TrackingPixmap pixmap = new TrackingPixmap();
                pixmaps.add(pixmap);
                Skin skin = DefaultSkin.create(() -> pixmap, pixel -> {
                    TrackingTexture texture = new TrackingTexture(pixel);
                    textures.add(texture);
                    return texture;
                });
                skins.add(skin);
                assertEquals(1, pixmap.disposeCount,
                        "the uploaded pixmap is disposed exactly once after upload");
                assertTrue(pixmap.isDisposed(), "the uploaded pixmap is disposed after upload");
                TrackingTexture texture = textures.get(round);
                assertSame(texture, skin.get("pixel", TrackingTexture.class),
                        "the pixel texture is registered with the skin");
                assertEquals(0, texture.disposeCount,
                        "create() keeps the texture alive; only the pixmap is disposed");
            }
            for (int i = 0; i < skins.size(); i++) {
                skins.get(i).dispose();
                TrackingTexture texture = textures.get(i);
                assertEquals(1, texture.disposeCount,
                        "skin disposal owns the texture and disposes it exactly once");
                assertEquals(1, pixmaps.get(i).disposeCount,
                        "the pixmap is not retained in the skin and is never double-disposed");
            }
        });
    }

    @Test
    void defaultSkinFailurePathDisposesPixmapExactlyOnce() throws Exception {
        GdxTestHost.run(() -> {
            RuntimeException uploadFailure = new RuntimeException("simulated upload failure");
            TrackingPixmap pixmap = new TrackingPixmap();
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                    DefaultSkin.create(() -> pixmap, pixel -> {
                        throw uploadFailure;
                    }));
            assertSame(uploadFailure, failure, "the construction failure propagates to the caller");
            assertEquals(1, pixmap.disposeCount,
                    "a failed texture construction still disposes the pixmap exactly once");
            assertTrue(pixmap.isDisposed(), "the pixmap is disposed on the failure path");

            TrackingPixmap first = new TrackingPixmap();
            TrackingPixmap second = new TrackingPixmap();
            assertThrows(RuntimeException.class, () ->
                    DefaultSkin.create(() -> first, pixel -> {
                        throw uploadFailure;
                    }));
            assertThrows(RuntimeException.class, () ->
                    DefaultSkin.create(() -> second, pixel -> {
                        throw uploadFailure;
                    }));
            assertEquals(1, first.disposeCount, "repeated failure leaks no pixmap");
            assertEquals(1, second.disposeCount, "repeated failure leaks no pixmap");
        });
    }

    /** Counts {@link Pixmap#dispose()} calls so ownership tests can assert exactly-once disposal. */
    private static final class TrackingPixmap extends Pixmap {
        int disposeCount;

        TrackingPixmap() {
            super(1, 1, Pixmap.Format.RGBA8888);
            setColor(Color.WHITE);
            fill();
        }

        @Override
        public void dispose() {
            disposeCount++;
            super.dispose();
        }
    }

    /** Counts {@link Texture#dispose()} calls; uploads a real texture from the given pixmap. */
    private static final class TrackingTexture extends Texture {
        int disposeCount;

        TrackingTexture(Pixmap pixmap) {
            super(pixmap);
        }

        @Override
        public void dispose() {
            disposeCount++;
            super.dispose();
        }
    }

    private static Map<String, String> invalidPad() {
        return Map.of("pad", "abc");
    }

    private static Element button(String id, Map<String, String> attrs) {
        return new Element("button", id, null, null, null, attrs, List.of(), List.of(), 1, 1);
    }

    private static Element table(Element... children) {
        return new Element("table", null, null, null, null, Map.of(), List.of(),
                List.of(children), 1, 1);
    }

    /**
     * Builds a programmatically constructed document whose second table's child carries an
     * invalid cell float. The document bypasses parser validation, so the invalid value reaches
     * the builder's cell-constraint application.
     */
    private static MarkupException buildWithInvalidPad(Element firstTable, Element secondTable) {
        Element ui = new Element("ui", null, null, null, null, Map.of(), List.of(),
                List.of(firstTable, secondTable), 1, 1);
        return assertThrows(MarkupException.class, () -> MarkupBuilder.build(
                new MarkupDocument(ui, 0), new CssParser().parse(""),
                DefaultSkin.create(), new NoopSink()));
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
