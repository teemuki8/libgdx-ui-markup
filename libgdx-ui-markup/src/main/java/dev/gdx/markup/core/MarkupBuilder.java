package dev.gdx.markup.core;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ProgressBar;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import dev.gdx.markup.core.style.CssDocument;
import dev.gdx.markup.core.style.CssStyleResolver;
import dev.gdx.markup.core.style.ResolvedStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a Scene2D actor tree from a parsed markup document and stylesheet on the render thread.
 * The build is two-phase by construction: {@link MarkupParser} and the CSS engine are GL-free,
 * while {@code build} creates actors, compiles CSS into the skin, applies cell constraints, and
 * emits semantics through the {@link SemanticSink}. Must be called on the GL/render thread; the
 * preview app calls it from {@code render()}.
 */
public final class MarkupBuilder {
    private static final Map<String, String> ROLE_BY_TAG = roles();

    private final MarkupDocument document;
    private final CssDocument css;
    private final Skin skin;
    private final SemanticSink sink;
    private final MarkupRegistry registry;
    private final CssStyleResolver resolver;
    private final int maxElements;
    private final int maxDepth;

    private MarkupBuilder(MarkupDocument document, CssDocument css, Skin skin, SemanticSink sink,
            MarkupRegistry registry, int maxElements, int maxDepth) {
        this.document = Objects.requireNonNull(document, "document");
        this.css = Objects.requireNonNull(css, "css");
        this.skin = Objects.requireNonNull(skin, "skin");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.resolver = new CssStyleResolver(css);
        this.maxElements = maxElements;
        this.maxDepth = maxDepth;
    }

    /**
     * Builds a UI with the default registry and the standard bounds. The caller owns the skin
     * (pass {@code DefaultSkin.create()} for a programmatic default) and must invoke this on the
     * render thread.
     */
    public static BuiltUi build(MarkupDocument document, CssDocument css, Skin skin,
            SemanticSink sink) {
        return build(document, css, skin, sink, MarkupRegistry.defaultRegistry());
    }

    /** Builds a UI with an explicit registry (custom widgets) and the standard bounds. */
    public static BuiltUi build(MarkupDocument document, CssDocument css, Skin skin,
            SemanticSink sink, MarkupRegistry registry) {
        return new MarkupBuilder(document, css, skin, sink, registry,
                MarkupParser.MAX_ELEMENTS, MarkupParser.MAX_DEPTH).build();
    }

    private BuiltUi build() {
        SkinStyleCompiler.compile(skin, css);
        Walk walk = new Walk();
        if ("table".equals(document.root().tag())) {
            Actor rootActor = walk.buildActor(document.root(), null);
            return new BuiltUi((Group) rootActor, walk.actors());
        }
        Group root = new Group();
        walk.pushRootPath("ui");
        walk.addChildren(root, null, document.root().children());
        walk.popRootPath();
        return new BuiltUi(root, walk.actors());
    }

    private static Map<String, String> roles() {
        Map<String, String> map = new HashMap<>();
        for (TagSpec spec : TagSpec.VOCABULARY.values()) {
            if (spec.role() != null) {
                map.put(spec.tag(), spec.role());
            }
        }
        return Map.copyOf(map);
    }

    /** One build traversal; owns element counting, paths, the actor list, and the style cache. */
    private final class Walk {
        private final List<Actor> actors = new ArrayList<>();
        private final ElementPathTracker paths = new ElementPathTracker();
        private final IdentityHashMap<Element, Map<String, ResolvedStyle>> styleCache =
                new IdentityHashMap<>();
        private int elements;
        private int depth;

        private Walk() {
        }

        /**
         * Resolves one element's style once per distinct element and pseudo-state for the
         * duration of this build; the immutable result is reused by every consumer.
         */
        private ResolvedStyle resolveStyle(Element element, String pseudo) {
            Map<String, ResolvedStyle> byPseudo = styleCache.get(element);
            if (byPseudo == null) {
                byPseudo = new HashMap<>(2);
                styleCache.put(element, byPseudo);
            }
            ResolvedStyle style = byPseudo.get(pseudo);
            if (style == null) {
                style = resolver.resolve(element, pseudo);
                byPseudo.put(pseudo, style);
            }
            return style;
        }

        List<Actor> actors() {
            return actors;
        }

        void pushRootPath(String tag) {
            paths.enter(tag);
        }

        void popRootPath() {
            paths.exit();
        }

        private void enter(String path, int line, int column) {
            if (++elements > maxElements) {
                throw new MarkupException(MarkupException.Kind.TOO_LARGE, path, line, column,
                        "build exceeds the " + maxElements + "-element limit");
            }
            if (++depth > maxDepth) {
                throw new MarkupException(MarkupException.Kind.TOO_LARGE, path, line, column,
                        "build exceeds the " + maxDepth + "-level depth limit");
            }
        }

        private void exit() {
            paths.exit();
            depth--;
        }

        /** Adds every child of a ui/group-style parent with no cell semantics. */
        void addChildren(Group parent, Table cellTable, List<Element> children) {
            for (Element child : children) {
                // A <row> here reaches buildActor with a null cell table and fails there typed.
                Actor actor = buildActor(child, cellTable);
                if (actor != null) {
                    parent.addActor(actor);
                }
            }
        }

        private MarkupException rowOutsideTable(int line, int column) {
            return new MarkupException(MarkupException.Kind.INVALID_VALUE,
                    paths.current(), line, column,
                    "<row> is only valid directly inside a <table> or <window>");
        }

        /** Builds one element into the given cell table (or {@code null} outside tables). */
        Actor buildActor(Element element, Table cellTable) {
            return buildActor(element, cellTable, null);
        }

        /**
         * Builds one element; {@code beforeExit} runs after the actor is built but while the
         * element's path frame is still entered, so post-build steps such as cell-constraint
         * application can report {@code paths.current()} for this element.
         */
        Actor buildActor(Element element, Table cellTable,
                java.util.function.Consumer<Actor> beforeExit) {
            String path = paths.enter(element.tag());
            int line = element.line();
            int column = element.column();
            enter(path, line, column);
            try {
                Actor actor = switch (element.tag()) {
                    case "ui" -> throw new MarkupException(MarkupException.Kind.INVALID_VALUE,
                            path, line, column, "<ui> must be the document root");
                    case "row" -> {
                        if (cellTable == null) {
                            throw rowOutsideTable(line, column);
                        }
                        cellTable.row();
                        yield null;
                    }
                    case "table", "window" -> buildTable(element, path, cellTable);
                    case "stack" -> buildStack(element, path, cellTable);
                    case "group" -> buildGroup(element, path, cellTable);
                    case "scrollpane" -> buildScrollPane(element, path, cellTable);
                    default -> buildLeaf(element, path, cellTable);
                };
                if (beforeExit != null) {
                    beforeExit.accept(actor);
                }
                return actor;
            } finally {
                exit();
            }
        }

        private Table buildTable(Element element, String path, Table cellTable) {
            Table table = (Table) registry.require(element.tag(), path, element.line(),
                    element.column()).create(element, context(element, path));
            actors.add(table);
            applyCommon(element, table, cellTable);
            ResolvedStyle style = resolveStyle(element, null);
            if (style.has("padding")) {
                List<Float> values = style.lengths("padding", List.of());
                if (values.size() == 1) {
                    table.pad(values.get(0));
                } else {
                    table.pad(values.get(0), values.get(1), values.get(2), values.get(3));
                }
            }
            buildCellChildren(table, element);
            applySemantics(table, element, path);
            return table;
        }

        private void buildCellChildren(Table table, Element element) {
            for (Element child : element.children()) {
                if ("row".equals(child.tag())) {
                    table.row();
                    continue;
                }
                buildActor(child, table, actor -> {
                    if (actor != null) {
                        applyCell(table.add(actor), child);
                    }
                });
            }
        }

        private Group buildStack(Element element, String path, Table cellTable) {
            Stack stack = (Stack) registry.require(element.tag(), path, element.line(),
                    element.column()).create(element, context(element, path));
            actors.add(stack);
            applyCommon(element, stack, cellTable);
            addChildren(stack, null, element.children());
            applySemantics(stack, element, path);
            return stack;
        }

        private Group buildGroup(Element element, String path, Table cellTable) {
            Group group = (Group) registry.require(element.tag(), path, element.line(),
                    element.column()).create(element, context(element, path));
            actors.add(group);
            applyCommon(element, group, cellTable);
            addChildren(group, null, element.children());
            applySemantics(group, element, path);
            return group;
        }

        private ScrollPane buildScrollPane(Element element, String path, Table cellTable) {
            if (element.children().size() != 1) {
                throw new MarkupException(MarkupException.Kind.INVALID_VALUE, path,
                        element.line(), element.column(),
                        "<scrollpane> requires exactly one child, found "
                                + element.children().size());
            }
            ScrollPane pane = (ScrollPane) registry.require(element.tag(), path,
                    element.line(), element.column()).create(element, context(element, path));
            actors.add(pane);
            applyCommon(element, pane, cellTable);
            Actor child = buildActor(element.children().get(0), null);
            if (child != null) {
                pane.setActor(child);
            }
            applySemantics(pane, element, path);
            return pane;
        }

        private Actor buildLeaf(Element element, String path, Table cellTable) {
            Actor actor = registry.require(element.tag(), path, element.line(),
                    element.column()).create(element, context(element, path));
            actors.add(actor);
            applyCommon(element, actor, cellTable);
            applyCssOverrides(element, actor, cellTable);
            applySemantics(actor, element, path);
            return actor;
        }

        private BuildContext context(Element element, String path) {
            return new BuildContext(element, skin, resolveStyle(element, null), sink, path);
        }

        private void applyCell(com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell, Element element) {
            ResolvedStyle style = resolveStyle(element, null);
            applyCellAttrs(cell, element);
            applyCellCss(cell, element, style);
        }

        private void applyCellAttrs(com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell,
                Element element) {
            String expand = element.attr("expand");
            if (expand != null) {
                switch (expand) {
                    case "false" -> { }
                    case "x" -> cell.expandX();
                    case "y" -> cell.expandY();
                    default -> cell.expand();
                }
            }
            String fill = element.attr("fill");
            if (fill != null) {
                switch (fill) {
                    case "false" -> { }
                    case "x" -> cell.fillX();
                    case "y" -> cell.fillY();
                    default -> cell.fill();
                }
            }
            String align = element.attr("align");
            if (align != null) {
                cell.align(alignValue(align, element));
            }
            String colspan = element.attr("colspan");
            if (colspan != null) {
                cell.colspan(Integer.parseInt(colspan));
            }
            applyPad(element, cell, "pad");
            applyEdge(element, cell, "pad-top", cell::padTop);
            applyEdge(element, cell, "pad-right", cell::padRight);
            applyEdge(element, cell, "pad-bottom", cell::padBottom);
            applyEdge(element, cell, "pad-left", cell::padLeft);
            applyPad(element, cell, "space");
            String grow = element.attr("grow");
            if (grow != null) {
                switch (grow) {
                    case "false" -> { }
                    case "x" -> cell.growX();
                    case "y" -> cell.growY();
                    default -> cell.grow();
                }
            }
            if (element.attr("uniform") != null) {
                cell.uniform(Boolean.parseBoolean(element.attr("uniform")));
            }
            applySizeAttr(element, cell, "width", cell::width);
            applySizeAttr(element, cell, "height", cell::height);
            applySizeAttr(element, cell, "min-width", cell::minWidth);
            applySizeAttr(element, cell, "min-height", cell::minHeight);
        }

        private void applyPad(Element element, com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell,
                String attribute) {
            String value = element.attr(attribute);
            if (value == null) {
                return;
            }
            String[] parts = value.split(",");
            if (parts.length == 1) {
                if ("pad".equals(attribute)) {
                    cell.pad(floatOf(element, value));
                } else {
                    cell.space(floatOf(element, value));
                }
            } else {
                if ("pad".equals(attribute)) {
                    cell.pad(floatOf(element, parts[0]), floatOf(element, parts[1]),
                            floatOf(element, parts[2]), floatOf(element, parts[3]));
                } else {
                    cell.space(floatOf(element, parts[0]), floatOf(element, parts[1]),
                            floatOf(element, parts[2]), floatOf(element, parts[3]));
                }
            }
        }

        private void applyEdge(Element element, com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell,
                String attribute, java.util.function.Consumer<Float> setter) {
            String value = element.attr(attribute);
            if (value != null) {
                setter.accept(floatOf(element, value));
            }
        }

        private void applySizeAttr(Element element, com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell,
                String attribute, java.util.function.Consumer<Float> setter) {
            String value = element.attr(attribute);
            if (value != null) {
                setter.accept(floatOf(element, value));
            }
        }

        private float floatOf(Element element, String raw) {
            try {
                float parsed = Float.parseFloat(raw);
                if (Float.isFinite(parsed)) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the typed failure
            }
            throw new MarkupException(MarkupException.Kind.INVALID_VALUE,
                    paths.current(), element.line(), element.column(),
                    "invalid numeric value \"" + raw + "\"");
        }

        private int alignValue(String align, Element element) {
            int value = 0;
            for (String token : align.toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
                value |= switch (token) {
                    case "top" -> Align.top;
                    case "bottom" -> Align.bottom;
                    case "left" -> Align.left;
                    case "right" -> Align.right;
                    case "center" -> Align.center;
                    default -> throw new MarkupException(MarkupException.Kind.INVALID_VALUE,
                            paths.current(), element.line(), element.column(),
                            "unknown align token \"" + token + "\"");
                };
            }
            return value;
        }

        private void applyCellCss(com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell,
                Element element, ResolvedStyle style) {
            applyLengthCss(element, cell, "width", style, cell::width);
            applyLengthCss(element, cell, "height", style, cell::height);
            applyLengthCss(element, cell, "min-width", style, cell::minWidth);
            applyLengthCss(element, cell, "min-height", style, cell::minHeight);
            applyPadCss(element, cell, style);
            applySpaceCss(element, cell, style);
        }

        private void applyLengthCss(Element element, com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell,
                String property, ResolvedStyle style, java.util.function.Consumer<Float> setter) {
            if (element.attr(property) == null && style.has(property)) {
                setter.accept(style.length(property, 0f));
            }
        }

        private void applyPadCss(Element element, com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell,
                ResolvedStyle style) {
            if (element.attr("pad") == null && style.has("padding")) {
                List<Float> values = style.lengths("padding", List.of());
                if (values.size() == 1) {
                    cell.pad(values.get(0));
                } else {
                    cell.pad(values.get(0), values.get(1), values.get(2), values.get(3));
                }
            }
            applyEdgeCss(element, cell, "pad-top", "padding-top", style, cell::padTop);
            applyEdgeCss(element, cell, "pad-right", "padding-right", style, cell::padRight);
            applyEdgeCss(element, cell, "pad-bottom", "padding-bottom", style, cell::padBottom);
            applyEdgeCss(element, cell, "pad-left", "padding-left", style, cell::padLeft);
        }

        private void applySpaceCss(Element element, com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell,
                ResolvedStyle style) {
            if (element.attr("space") == null && style.has("margin")) {
                List<Float> values = style.lengths("margin", List.of());
                if (values.size() == 1) {
                    cell.space(values.get(0));
                } else {
                    cell.space(values.get(0), values.get(1), values.get(2), values.get(3));
                }
            }
            applyEdgeCss(element, cell, null, "margin-top", style, cell::spaceTop);
            applyEdgeCss(element, cell, null, "margin-right", style, cell::spaceRight);
            applyEdgeCss(element, cell, null, "margin-bottom", style, cell::spaceBottom);
            applyEdgeCss(element, cell, null, "margin-left", style, cell::spaceLeft);
        }

        private void applyEdgeCss(Element element, com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell,
                String attribute, String property, ResolvedStyle style,
                java.util.function.Consumer<Float> setter) {
            if ((attribute == null || element.attr(attribute) == null) && style.has(property)) {
                setter.accept(style.length(property, 0f));
            }
        }

        private void applyCommon(Element element, Actor actor, Table cellTable) {
            ResolvedStyle style = resolveStyle(element, null);
            if (cellTable == null) {
                applySize(element, actor, "width", actor::setWidth);
                applySize(element, actor, "height", actor::setHeight);
                // Actor has no min-size setters; min-width/min-height are cell constraints.
                applySize(element, actor, "width", style, actor::setWidth);
                applySize(element, actor, "height", style, actor::setHeight);
            }
            String visible = element.attr("visible");
            if (visible != null) {
                actor.setVisible(Boolean.parseBoolean(visible));
            } else if (style.has("visible")) {
                actor.setVisible(style.booleanValue("visible", true));
            }
            if (element.attr("disabled") != null) {
                setDisabled(actor, Boolean.parseBoolean(element.attr("disabled")));
            }
            if (element.attr("focusable") != null && !Boolean.parseBoolean(
                    element.attr("focusable"))) {
                actor.setTouchable(Touchable.disabled);
            }
        }

        private void applySize(Element element, Actor actor, String attribute,
                java.util.function.Consumer<Float> setter) {
            String value = element.attr(attribute);
            if (value != null) {
                setter.accept(floatOf(element, value));
            }
        }

        private void applySize(Element element, Actor actor, String property, ResolvedStyle style,
                java.util.function.Consumer<Float> setter) {
            if (element.attr(property) == null && style.has(property)) {
                setter.accept(style.length(property, 0f));
            }
        }

        private void setDisabled(Actor actor, boolean disabled) {
            if (actor instanceof Button button) {
                button.setDisabled(disabled);
            } else if (actor instanceof TextField field) {
                field.setDisabled(disabled);
            } else if (actor instanceof SelectBox<?> box) {
                box.setDisabled(disabled);
            } else if (actor instanceof Slider slider) {
                slider.setDisabled(disabled);
            } else if (actor instanceof ProgressBar bar) {
                bar.setDisabled(disabled);
            }
        }

        /** Applies class-only/id-only CSS style overrides directly to the actor's style. */
        private void applyCssOverrides(Element element, Actor actor, Table cellTable) {
            ResolvedStyle style = resolveStyle(element, null);
            if (actor instanceof Table table && style.has("background")) {
                table.setBackground(requireDrawable(element, style.get("background")));
            }
            if (!style.has("font-color") && !style.has("color") && !style.has("font")
                    && !style.has("background") && !style.has("text-align")) {
                return;
            }
            Object copied = null;
            if (style.has("font-color") || style.has("color")) {
                Object styleObject = widgetStyle(actor);
                if (styleObject != null) {
                    copied = SkinStyleCompiler.copyOf(styleObject);
                    SkinStyleCompiler.setColor(copied, color(element, style));
                }
            }
            if (style.has("font")) {
                Object styleObject = copied != null ? copied : widgetStyle(actor);
                if (styleObject != null) {
                    if (copied == null) {
                        copied = SkinStyleCompiler.copyOf(styleObject);
                    }
                    SkinStyleCompiler.setFont(copied, requireFont(element, style.get("font")));
                }
            }
            if (style.has("background")) {
                Object styleObject = copied != null ? copied : widgetStyle(actor);
                if (styleObject != null) {
                    if (copied == null) {
                        copied = SkinStyleCompiler.copyOf(styleObject);
                    }
                    SkinStyleCompiler.setBaseDrawable(copied,
                            requireDrawable(element, style.get("background")));
                }
            }
            if (copied != null) {
                setWidgetStyle(actor, copied);
            }
            if (style.has("text-align")) {
                if (actor instanceof Label label) {
                    label.setAlignment(alignOf(style.get("text-align")));
                } else if (actor instanceof TextField field) {
                    field.setAlignment(alignOf(style.get("text-align")));
                }
            }
        }

        private Drawable requireDrawable(Element element, String name) {
            Drawable drawable = skin.optional(name, Drawable.class);
            if (drawable == null) {
                throw new MarkupException(MarkupException.Kind.UNRESOLVED_STYLE,
                        paths.current(), element.line(), element.column(),
                        "skin has no drawable named \"" + name + "\"");
            }
            return drawable;
        }

        private com.badlogic.gdx.graphics.Color color(Element element, ResolvedStyle style) {
            String name = style.has("font-color") ? style.get("font-color") : style.get("color");
            com.badlogic.gdx.graphics.Color color = BuildContext.parseColor(skin, name);
            if (color == null) {
                throw new MarkupException(MarkupException.Kind.UNRESOLVED_STYLE,
                        paths.current(), element.line(), element.column(),
                        "skin has no color named \"" + name + "\"");
            }
            return color;
        }

        private com.badlogic.gdx.graphics.g2d.BitmapFont requireFont(Element element,
                String name) {
            com.badlogic.gdx.graphics.g2d.BitmapFont font =
                    skin.optional(name, com.badlogic.gdx.graphics.g2d.BitmapFont.class);
            if (font == null) {
                throw new MarkupException(MarkupException.Kind.UNRESOLVED_STYLE,
                        paths.current(), element.line(), element.column(),
                        "skin has no font named \"" + name + "\"");
            }
            return font;
        }

        private Object widgetStyle(Actor actor) {
            if (actor instanceof Label label) {
                return label.getStyle();
            }
            if (actor instanceof TextButton button) {
                return button.getStyle();
            }
            if (actor instanceof CheckBox checkBox) {
                return checkBox.getStyle();
            }
            if (actor instanceof TextField field) {
                return field.getStyle();
            }
            if (actor instanceof SelectBox<?> box) {
                return box.getStyle();
            }
            if (actor instanceof Slider slider) {
                return slider.getStyle();
            }
            if (actor instanceof ProgressBar bar) {
                return bar.getStyle();
            }
            if (actor instanceof Window window) {
                return window.getStyle();
            }
            if (actor instanceof com.badlogic.gdx.scenes.scene2d.ui.List<?> list) {
                return list.getStyle();
            }
            if (actor instanceof ScrollPane pane) {
                return pane.getStyle();
            }
            return null;
        }

        private void setWidgetStyle(Actor actor, Object style) {
            if (actor instanceof Label label) {
                label.setStyle((Label.LabelStyle) style);
            } else if (actor instanceof TextButton button) {
                button.setStyle((TextButton.TextButtonStyle) style);
            } else if (actor instanceof CheckBox checkBox) {
                checkBox.setStyle((CheckBox.CheckBoxStyle) style);
            } else if (actor instanceof TextField field) {
                field.setStyle((TextField.TextFieldStyle) style);
            } else if (actor instanceof SelectBox<?> box) {
                box.setStyle((SelectBox.SelectBoxStyle) style);
            } else if (actor instanceof Slider slider) {
                slider.setStyle((Slider.SliderStyle) style);
            } else if (actor instanceof ProgressBar bar) {
                bar.setStyle((ProgressBar.ProgressBarStyle) style);
            } else if (actor instanceof Window window) {
                window.setStyle((Window.WindowStyle) style);
            } else if (actor instanceof com.badlogic.gdx.scenes.scene2d.ui.List<?> list) {
                list.setStyle((com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle) style);
            } else if (actor instanceof ScrollPane pane) {
                pane.setStyle((ScrollPane.ScrollPaneStyle) style);
            }
        }

        private static int alignOf(String value) {
            return switch (value) {
                case "left" -> Align.left;
                case "center" -> Align.center;
                case "right" -> Align.right;
                default -> throw new AssertionError(value);
            };
        }

        private void applySemantics(Actor actor, Element element, String path) {
            if (element.id() != null) {
                actor.setName(element.id());
                sink.testId(actor, element.id());
            }
            if (element.name() != null) {
                sink.accessibleName(actor, element.name());
            }
            if (element.label() != null) {
                sink.label(actor, element.label());
            }
            String role = ROLE_BY_TAG.get(element.tag());
            if (role != null) {
                sink.role(actor, role);
            }
            for (Map.Entry<String, String> attribute : element.attrs().entrySet()) {
                if (attribute.getKey().startsWith("data-")) {
                    sink.property(actor, attribute.getKey().substring("data-".length()),
                            attribute.getValue());
                }
            }
        }
    }
}
