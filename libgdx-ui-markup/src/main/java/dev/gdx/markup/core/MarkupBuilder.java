package dev.gdx.markup.core;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
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
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import dev.gdx.markup.core.style.CssDocument;
import dev.gdx.markup.core.style.CssLength;
import dev.gdx.markup.core.style.CssRule;
import dev.gdx.markup.core.style.CssSpacing;
import dev.gdx.markup.core.style.CssStyleResolver;
import dev.gdx.markup.core.style.ResolvedStyle;
import dev.gdx.markup.core.style.Selector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private final Set<String> taglessPseudoStates;
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
        this.taglessPseudoStates = taglessPseudoStates(css);
        this.maxElements = maxElements;
        this.maxDepth = maxDepth;
    }

    /**
     * The pseudo-states used by class-only/id-only selectors in this stylesheet. Tag selectors
     * (and {@code tag.class}) compile into skin styles, so only tagless pseudo rules need
     * per-actor application; resolving every state for every actor would multiply cascade work.
     */
    private static Set<String> taglessPseudoStates(CssDocument css) {
        Set<String> states = new HashSet<>();
        for (CssRule rule : css.rules()) {
            for (Selector selector : rule.selectors()) {
                if (selector.tag() == null && selector.pseudo() != null) {
                    states.add(selector.pseudo());
                }
            }
        }
        return Set.copyOf(states);
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

    /**
     * Package-private seam for tests: explicit cascade work limits on the per-build resolver.
     */
    static BuiltUi build(MarkupDocument document, CssDocument css, Skin skin, SemanticSink sink,
            MarkupRegistry registry, int maxComparisonsPerResolve, int maxComparisonsPerBuild) {
        return new MarkupBuilder(document, css, skin, sink, registry,
                MarkupParser.MAX_ELEMENTS, MarkupParser.MAX_DEPTH,
                maxComparisonsPerResolve, maxComparisonsPerBuild).build();
    }

    private MarkupBuilder(MarkupDocument document, CssDocument css, Skin skin, SemanticSink sink,
            MarkupRegistry registry, int maxElements, int maxDepth,
            int maxComparisonsPerResolve, int maxComparisonsPerBuild) {
        this.document = Objects.requireNonNull(document, "document");
        this.css = Objects.requireNonNull(css, "css");
        this.skin = Objects.requireNonNull(skin, "skin");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.resolver = new CssStyleResolver(css, maxComparisonsPerResolve, maxComparisonsPerBuild);
        this.taglessPseudoStates = taglessPseudoStates(css);
        this.maxElements = maxElements;
        this.maxDepth = maxDepth;
    }

    private BuiltUi build() {
        SkinStyleCompiler.compile(skin, css);
        Walk walk = new Walk();
        if ("table".equals(document.root().tag())) {
            Actor rootActor = walk.buildActor(document.root(), null);
            return new BuiltUi(rootActor == null ? new Group() : (Group) rootActor,
                    walk.actors());
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
                // The element's path frame is entered for every caller, so the current tracked
                // path is this element's own; limit failures then report the full path.
                style = resolver.resolve(element, pseudo, paths.current());
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
                if (displayNone(element)) {
                    return null;
                }
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
            assignPseudoStyle(element, table);
            applyCssOverrides(element, table, cellTable);
            ResolvedStyle style = resolveStyle(element, null);
            applyTablePadding(table, style);
            applyTableGaps(table, style);
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
                        applyCell(table, table.add(actor), child);
                    }
                });
            }
        }

        private Group buildStack(Element element, String path, Table cellTable) {
            Stack stack = (Stack) registry.require(element.tag(), path, element.line(),
                    element.column()).create(element, context(element, path));
            actors.add(stack);
            applyCommon(element, stack, cellTable);
            assignPseudoStyle(element, stack);
            addChildren(stack, null, element.children());
            applySemantics(stack, element, path);
            return stack;
        }

        private Group buildGroup(Element element, String path, Table cellTable) {
            Group group = (Group) registry.require(element.tag(), path, element.line(),
                    element.column()).create(element, context(element, path));
            actors.add(group);
            applyCommon(element, group, cellTable);
            assignPseudoStyle(element, group);
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
            assignPseudoStyle(element, pane);
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

        private void applyCell(Table table, com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell,
                Element element) {
            ResolvedStyle style = resolveStyle(element, null);
            applyCellAttrs(cell, element);
            applyCellCss(table, cell, element, style);
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

        private void applyCellCss(Table table, com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell,
                Element element, ResolvedStyle style) {
            applyLengthCss(table, element, cell, "width", style, Axis.X, Constraint.SIZE);
            applyLengthCss(table, element, cell, "height", style, Axis.Y, Constraint.SIZE);
            applyLengthCss(table, element, cell, "min-width", style, Axis.X, Constraint.MIN);
            applyLengthCss(table, element, cell, "min-height", style, Axis.Y, Constraint.MIN);
            applyLengthCss(table, element, cell, "max-width", style, Axis.X, Constraint.MAX);
            applyLengthCss(table, element, cell, "max-height", style, Axis.Y, Constraint.MAX);
            if (!isTableContainer(element)) {
                applyPadCss(element, cell, style);
            }
            applySpaceCss(element, cell, style);
            applyCellVerticalAlign(element, cell, style);
        }

        private void applyLengthCss(Table table, Element element,
                com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell, String property,
                ResolvedStyle style, Axis axis, Constraint constraint) {
            if (element.attr(property) != null || !style.has(property)) {
                return;
            }
            CssLength length = style.lengthValue(property);
            if (length instanceof CssLength.Auto) {
                return;
            }
            Value value = value(length, table, axis);
            if (constraint == Constraint.MAX) {
                value = nonZeroMaximum(value);
            }
            switch (constraint) {
                case SIZE -> {
                    if (axis == Axis.X) {
                        cell.width(value);
                    } else {
                        cell.height(value);
                    }
                }
                case MIN -> {
                    if (axis == Axis.X) {
                        cell.minWidth(value);
                    } else {
                        cell.minHeight(value);
                    }
                }
                case MAX -> {
                    if (axis == Axis.X) {
                        cell.maxWidth(value);
                    } else {
                        cell.maxHeight(value);
                    }
                }
            }
        }

        private Value value(CssLength length, Table containingTable, Axis axis) {
            if (length instanceof CssLength.Pixels pixels) {
                return Value.Fixed.valueOf(pixels.value());
            }
            CssLength.Percent percent = (CssLength.Percent) length;
            return axis == Axis.X
                    ? Value.percentWidth(percent.ratio(), containingTable)
                    : Value.percentHeight(percent.ratio(), containingTable);
        }

        private Value nonZeroMaximum(Value value) {
            return new Value() {
                @Override public float get(Actor context) {
                    float evaluated = value.get(context);
                    return evaluated == 0f ? Float.MIN_NORMAL : evaluated;
                }
            };
        }

        private void applyPadCss(Element element, com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell,
                ResolvedStyle style) {
            if (element.attr("pad") == null && style.has("padding")) {
                CssSpacing value = style.spacing("padding");
                cell.pad(value.top(), value.left(), value.bottom(), value.right());
            }
            applyEdgeCss(element, cell, "pad-top", "padding-top", style, cell::padTop);
            applyEdgeCss(element, cell, "pad-right", "padding-right", style, cell::padRight);
            applyEdgeCss(element, cell, "pad-bottom", "padding-bottom", style, cell::padBottom);
            applyEdgeCss(element, cell, "pad-left", "padding-left", style, cell::padLeft);
        }

        private void applySpaceCss(Element element, com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell,
                ResolvedStyle style) {
            if (element.attr("space") == null && style.has("margin")) {
                CssSpacing value = style.spacing("margin");
                cell.space(value.top(), value.left(), value.bottom(), value.right());
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
                if (actor instanceof Table table && hasFullParentDimensions(element, style)) {
                    rejectUnsupportedTopLevelDimensions(element, style,
                            Set.of("width", "height"));
                    table.setFillParent(true);
                } else {
                    rejectUnsupportedTopLevelDimensions(element, style, Set.of());
                    // Actor has no min/max-size setters; those remain cell constraints.
                    applyFixedActorSize(element, "width", style, actor::setWidth);
                    applyFixedActorSize(element, "height", style, actor::setHeight);
                }
            }
            rejectTableOnlyProperties(element, actor, cellTable, style);
            applyOverflow(element, actor, style);
            String visible = element.attr("visible");
            if (visible != null) {
                actor.setVisible(Boolean.parseBoolean(visible));
            } else if (style.has("visibility")) {
                actor.setVisible("visible".equals(style.get("visibility")));
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

        private void applyFixedActorSize(Element element, String property, ResolvedStyle style,
                java.util.function.Consumer<Float> setter) {
            if (element.attr(property) == null && style.has(property)) {
                CssLength length = style.lengthValue(property);
                if (length instanceof CssLength.Pixels pixels) {
                    setter.accept(pixels.value());
                }
            }
        }

        private boolean hasFullParentDimensions(Element element, ResolvedStyle style) {
            if (element.attr("width") != null || element.attr("height") != null) {
                return false;
            }
            return isExactFullPercent(style.lengthValue("width"))
                    && isExactFullPercent(style.lengthValue("height"));
        }

        private boolean isExactFullPercent(CssLength length) {
            return length instanceof CssLength.Percent percent && percent.ratio() == 1f;
        }

        private void rejectUnsupportedTopLevelDimensions(Element element, ResolvedStyle style,
                Set<String> allowed) {
            for (String property : List.of("width", "height", "min-width", "min-height",
                    "max-width", "max-height")) {
                if (element.attr(property) != null || !style.has(property)) {
                    continue;
                }
                CssLength length = style.lengthValue(property);
                if ((property.startsWith("min-") || property.startsWith("max-"))
                        && !(length instanceof CssLength.Auto)) {
                    throw unsupportedCellConstraint(element, style, property);
                }
                if (length instanceof CssLength.Percent && !allowed.contains(property)) {
                    throw unsupportedPercent(element, style, property);
                }
            }
        }

        private MarkupException unsupportedCellConstraint(Element element, ResolvedStyle style,
                String property) {
            CssRule source = style.sourceRule(property);
            return new MarkupException(MarkupException.Kind.STYLE_ERROR, "css",
                    source.line(), source.column(), "property \"" + property
                    + "\" requires a Table cell (matched <" + element.tag() + "> at "
                    + paths.current() + ")");
        }

        private MarkupException unsupportedPercent(Element element, ResolvedStyle style,
                String property) {
            CssRule source = style.sourceRule(property);
            return new MarkupException(MarkupException.Kind.STYLE_ERROR, "css",
                    source.line(), source.column(), "percentage property \"" + property
                    + "\" requires a Table cell; a top-level Table supports only the exact "
                    + "width: 100% and height: 100% pair");
        }

        private void applyTablePadding(Table table, ResolvedStyle style) {
            if (style.has("padding")) {
                CssSpacing value = style.spacing("padding");
                table.pad(value.top(), value.left(), value.bottom(), value.right());
            }
            if (style.has("padding-top")) {
                table.padTop(style.length("padding-top", 0f));
            }
            if (style.has("padding-right")) {
                table.padRight(style.length("padding-right", 0f));
            }
            if (style.has("padding-bottom")) {
                table.padBottom(style.length("padding-bottom", 0f));
            }
            if (style.has("padding-left")) {
                table.padLeft(style.length("padding-left", 0f));
            }
        }

        private void applyTableGaps(Table table, ResolvedStyle style) {
            CssSpacing gap = style.spacing("gap");
            float top = gap == null ? 0f : gap.top();
            float right = gap == null ? 0f : gap.right();
            float bottom = gap == null ? 0f : gap.bottom();
            float left = gap == null ? 0f : gap.left();
            if (style.has("row-gap")) {
                top = style.length("row-gap", 0f);
                bottom = top;
            }
            if (style.has("column-gap")) {
                right = style.length("column-gap", 0f);
                left = right;
            }
            if (gap != null || style.has("row-gap") || style.has("column-gap")) {
                table.defaults().space(top, left, bottom, right);
            }
        }

        private boolean displayNone(Element element) {
            ResolvedStyle style = resolveStyle(element, null);
            return "none".equals(style.get("display"));
        }

        private void rejectTableOnlyProperties(Element element, Actor actor, Table cellTable,
                ResolvedStyle style) {
            if (actor instanceof Table) {
                return;
            }
            for (String property : List.of("gap", "row-gap", "column-gap")) {
                if (style.has(property)) {
                    throw unsupportedTarget(element, style, property,
                            "property \"" + property + "\" requires a Table actor");
                }
            }
            if (style.has("vertical-align") && !(actor instanceof Label)
                    && cellTable == null) {
                throw unsupportedTarget(element, style, "vertical-align",
                        "property \"vertical-align\" requires a Label or containing Table Cell");
            }
        }

        private void applyOverflow(Element element, Actor actor, ResolvedStyle style) {
            if (!style.has("overflow")) {
                return;
            }
            if (actor instanceof Table table) {
                table.setClip("hidden".equals(style.get("overflow")));
                return;
            }
            throw unsupportedTarget(element, style, "overflow",
                    "property \"overflow\" requires a Table actor");
        }

        private void applyCellVerticalAlign(Element element,
                com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell, ResolvedStyle style) {
            if (element.attr("align") != null || !style.has("vertical-align")) {
                return;
            }
            int align = cell.getAlign() & ~(Align.top | Align.bottom);
            align |= verticalAlignOf(style.get("vertical-align"));
            cell.align(align);
        }

        private int verticalAlignOf(String value) {
            return switch (value) {
                case "top" -> Align.top;
                case "bottom" -> Align.bottom;
                case "middle" -> 0;
                default -> throw new AssertionError("validated vertical-align " + value);
            };
        }

        private MarkupException unsupportedTarget(Element element, ResolvedStyle style,
                String property, String message) {
            CssRule source = style.sourceRule(property);
            return new MarkupException(MarkupException.Kind.STYLE_ERROR, "css",
                    source.line(), source.column(), message + " (matched <" + element.tag()
                    + "> at " + paths.current() + ")");
        }

        private boolean isTableContainer(Element element) {
            return "table".equals(element.tag()) || "window".equals(element.tag());
        }

        private enum Axis {
            X,
            Y,
        }

        private enum Constraint {
            SIZE,
            MIN,
            MAX,
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

        /**
         * Applies class-only/id-only CSS style overrides directly to the actor's style. The
         * base variant mutates a clone of the actor's style; every tagless pseudo variant in
         * this stylesheet mutates the same clone through the shared pseudo-to-field mapping, so
         * the derived style is assigned only to this actor and the shared skin style is never
         * touched.
         */
        private void applyCssOverrides(Element element, Actor actor, Table cellTable) {
            ResolvedStyle base = resolveStyle(element, null);
            if (actor instanceof Table table && base.has("background")) {
                table.setBackground(requireDrawable(element, base.get("background")));
            }
            if (actor instanceof Table table && base.has("background-color")) {
                table.setBackground(tintedDrawable(element, table.getBackground(),
                        base.get("background-color")));
            }
            Object copied = null;
            if (hasStateStyle(base) || hasXmlFontOverride(element)) {
                copied = applyStateStyle(element, actor, base, null, copied);
            }
            copied = applyPseudoCssOverrides(element, actor, copied);
            if (copied != null) {
                setWidgetStyle(actor, copied);
            }
            if (base.has("text-align")) {
                if (actor instanceof Label label) {
                    int vertical = label.getLabelAlign() & (Align.top | Align.bottom);
                    label.setAlignment(alignOf(base.get("text-align")) | vertical);
                } else if (actor instanceof TextField field) {
                    field.setAlignment(alignOf(base.get("text-align")));
                }
            }
            if (base.has("vertical-align") && actor instanceof Label label) {
                int horizontal = label.getLabelAlign() & (Align.left | Align.right);
                label.setAlignment(horizontal | verticalAlignOf(base.get("vertical-align")));
            }
            applyTextAndImageProperties(element, actor, base);
        }

        private void applyTextAndImageProperties(Element element, Actor actor,
                ResolvedStyle style) {
            if (style.has("white-space")) {
                if (!(actor instanceof Label label)) {
                    throw unsupportedTarget(element, style, "white-space",
                            "property \"white-space\" requires a Label actor");
                }
                label.setWrap("normal".equals(style.get("white-space")));
            }
            if (style.has("text-overflow")) {
                if (!(actor instanceof Label label)) {
                    throw unsupportedTarget(element, style, "text-overflow",
                            "property \"text-overflow\" requires a Label actor");
                }
                label.setEllipsis("ellipsis".equals(style.get("text-overflow")));
            }
            if (style.has("object-fit")) {
                if (!(actor instanceof Image image)) {
                    throw unsupportedTarget(element, style, "object-fit",
                            "property \"object-fit\" requires an Image actor");
                }
                image.setScaling(scalingOf(style.get("object-fit")));
            }
            if (style.has("object-position")) {
                if (!(actor instanceof Image image)) {
                    throw unsupportedTarget(element, style, "object-position",
                            "property \"object-position\" requires an Image actor");
                }
                image.setAlign(objectAlignOf(style.get("object-position")));
            }
        }

        private Scaling scalingOf(String value) {
            return switch (value) {
                case "contain" -> Scaling.fit;
                case "cover" -> Scaling.fill;
                case "fill" -> Scaling.stretch;
                case "none" -> Scaling.none;
                default -> throw new AssertionError("validated object-fit " + value);
            };
        }

        private int objectAlignOf(String value) {
            String[] parts = value.split("\\s+");
            String horizontal;
            String vertical;
            if (parts.length == 1) {
                horizontal = switch (parts[0]) {
                    case "left", "right" -> parts[0];
                    default -> "center";
                };
                vertical = switch (parts[0]) {
                    case "top", "bottom" -> parts[0];
                    default -> "center";
                };
            } else {
                horizontal = parts[0];
                vertical = parts[1];
            }
            int alignment = switch (horizontal) {
                case "left" -> Align.left;
                case "right" -> Align.right;
                default -> 0;
            };
            alignment |= switch (vertical) {
                case "top" -> Align.top;
                case "bottom" -> Align.bottom;
                default -> 0;
            };
            return alignment == 0 ? Align.center : alignment;
        }

        /**
         * Validates and applies every tagless pseudo variant for one actor: a pseudo rule with
         * state-style properties either maps to a supported state field on the actor's widget
         * style (returning the clone to assign) or fails with a located {@code STYLE_ERROR}.
         * Container builders call this on their own (after their base handling) so tagless
         * pseudo selectors are never silently ignored for containers; leaves reach it through
         * {@link #applyCssOverrides}. Base-state behavior of the caller is intentionally
         * untouched.
         */
        private Object applyPseudoCssOverrides(Element element, Actor actor, Object copied) {
            if (!needsTaglessPseudoStyles(element)) {
                return copied;
            }
            for (String pseudo : taglessPseudoStates) {
                ResolvedStyle variant = resolveStyle(element, pseudo);
                if (hasStateStyle(variant)) {
                    copied = applyStateStyle(element, actor, variant, pseudo, copied);
                }
            }
            return copied;
        }

        /**
         * Runs the tagless pseudo validation for a container actor (no container style has
         * state fields today, so a state-style pseudo variant either fails located or leaves
         * the clone null; the assignment would only matter for a future style with state
         * fields). Container base-state behavior is untouched.
         */
        private void assignPseudoStyle(Element element, Actor actor) {
            Object copied = applyPseudoCssOverrides(element, actor, null);
            if (copied != null) {
                setWidgetStyle(actor, copied);
            }
        }

        /** Style-field properties applied per actor for tagless selectors. */
        private static final List<String> STATE_STYLE_PROPERTIES = List.of(
                "background", "background-over", "background-down", "background-checked",
                "background-disabled", "background-color", "font-color", "color", "font",
                "font-size");

        private static boolean hasXmlFontOverride(Element element) {
            return element.attr("font") != null || element.attr("font-size") != null;
        }

        private static boolean hasStateStyle(ResolvedStyle style) {
            for (String property : STATE_STYLE_PROPERTIES) {
                if (style.has(property)) {
                    return true;
                }
            }
            return false;
        }

        private boolean needsTaglessPseudoStyles(Element element) {
            return !taglessPseudoStates.isEmpty()
                    && (element.id() != null || !element.classes().isEmpty());
        }

        /**
         * Applies every state style property of one pseudo variant to the actor's widget style,
         * cloning it before the first mutation. Returns the (possibly new) clone, or {@code null}
         * when nothing was applied.
         */
        private Object applyStateStyle(Element element, Actor actor, ResolvedStyle style,
                String pseudo, Object copied) {
            Object styleObject = copied != null ? copied : widgetStyle(actor);
            if (styleObject == null) {
                if (pseudo != null) {
                    throw unsupportedState(element, style, pseudo);
                }
                return copied; // base font-color on an image: preserved silent no-op
            }
            if (copied == null) {
                copied = SkinStyleCompiler.copyOf(styleObject);
            }
            for (String property : STATE_STYLE_PROPERTIES) {
                if (!style.has(property)) {
                    continue;
                }
                String state = SkinStyleCompiler.propertyState(property, pseudo);
                CssRule source = style.sourceRule(property);
                switch (property) {
                    case "background", "background-over", "background-down", "background-checked",
                            "background-disabled" -> SkinStyleCompiler.setStateDrawable(copied,
                                    state, requireDrawable(element, style.get(property)),
                                    element.tag(), property, source);
                    case "background-color" -> {
                        Drawable current = SkinStyleCompiler.stateDrawable(copied, state,
                                element.tag(), property, source);
                        SkinStyleCompiler.setStateDrawable(copied, state,
                                tintedDrawable(element, current, style.get(property)),
                                element.tag(), property, source);
                    }
                    case "color", "font-color" -> SkinStyleCompiler.setStateColor(copied, state,
                            color(element, style), element.tag(), property, source);
                    case "font" -> {
                        if (pseudo != null && sourceDeclaresPseudo(
                                source, element, pseudo)) {
                            throw SkinStyleCompiler.unsupported(
                                    element.tag(), pseudo, property, source);
                        }
                    }
                    case "font-size" -> {
                        // The parser forbids pseudo-state sizes. Base selection is applied below.
                    }
                    default -> throw new AssertionError(property);
                }
            }
            if (pseudo == null && supportsFont(actor)) {
                com.badlogic.gdx.graphics.g2d.BitmapFont font = effectiveFont(element, style);
                if (font != null) {
                    SkinStyleCompiler.setStateFont(copied, SkinStyleCompiler.propertyState(
                            "font", null), font, element.tag(), "font", null);
                }
            }
            return copied;
        }

        private boolean sourceDeclaresPseudo(
                CssRule source, Element element, String pseudo) {
            for (Selector selector : source.selectors()) {
                if (pseudo.equals(selector.pseudo()) && selector.matches(element.tag(),
                        element.id(), element.classes(), pseudo)) {
                    return true;
                }
            }
            return false;
        }

        private com.badlogic.gdx.graphics.g2d.BitmapFont effectiveFont(
                Element element, ResolvedStyle style) {
            String family = element.attr("font");
            if (family == null && style.has("font")) {
                family = style.get("font");
            }
            String sizeValue = element.attr("font-size");
            if (sizeValue == null && style.has("font-size")) {
                sizeValue = style.get("font-size");
            }
            if (family == null && sizeValue == null) {
                return null;
            }

            FreeTypeFontManager manager = FreeTypeFontManager.optional(skin);
            if (sizeValue == null) {
                com.badlogic.gdx.graphics.g2d.BitmapFont named = skin.optional(
                        family, com.badlogic.gdx.graphics.g2d.BitmapFont.class);
                if (named != null) {
                    return named;
                }
                if (manager == null) {
                    throw unresolvedFont(element,
                            "skin has no font named \"" + family + "\"");
                }
                sizeValue = Integer.toString(FreeTypeFontManager.DEFAULT_FONT_SIZE);
            } else if (manager == null) {
                throw unresolvedFont(element,
                        "skin has no FreeType font manager for font-size");
            }
            if (family == null) {
                family = manager.defaultFamily();
            }

            int logicalSize = Integer.parseInt(sizeValue.endsWith("px")
                    ? sizeValue.substring(0, sizeValue.length() - 2) : sizeValue);
            try {
                return manager.font(family, logicalSize);
            } catch (FreeTypeFontManager.CacheLimitException failure) {
                throw new MarkupException(MarkupException.Kind.TOO_LARGE,
                        paths.current(), element.line(), element.column(), failure.getMessage());
            } catch (IllegalArgumentException failure) {
                throw unresolvedFont(element, failure.getMessage());
            } catch (FreeTypeFontManager.ResourceCollisionException failure) {
                throw unresolvedFont(element, failure.getMessage());
            }
        }

        private boolean supportsFont(Actor actor) {
            return actor instanceof Label
                    || actor instanceof TextButton
                    || actor instanceof CheckBox
                    || actor instanceof TextField
                    || actor instanceof SelectBox<?>
                    || actor instanceof Window
                    || actor instanceof com.badlogic.gdx.scenes.scene2d.ui.List<?>;
        }

        private MarkupException unresolvedFont(Element element, String message) {
            return new MarkupException(MarkupException.Kind.UNRESOLVED_STYLE,
                    paths.current(), element.line(), element.column(), message);
        }

        private MarkupException unsupportedState(Element element, ResolvedStyle style,
                String pseudo) {
            String property = null;
            for (String candidate : STATE_STYLE_PROPERTIES) {
                if (style.has(candidate)) {
                    property = candidate;
                    break;
                }
            }
            CssRule source = style.sourceRule(property);
            return SkinStyleCompiler.unsupported(element.tag(), pseudo, property, source);
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

        private Drawable tintedDrawable(Element element, Drawable base, String colorValue) {
            Drawable selected = base;
            if (selected == null) {
                selected = skin.optional("white", Drawable.class);
                if (selected == null) {
                    throw new MarkupException(MarkupException.Kind.UNRESOLVED_STYLE,
                            paths.current(), element.line(), element.column(),
                            "skin has no drawable named \"white\" for background-color");
                }
            }
            com.badlogic.gdx.graphics.Color tint = color(element, colorValue);
            try {
                return skin.newDrawable(selected, tint);
            } catch (com.badlogic.gdx.utils.GdxRuntimeException failure) {
                throw new MarkupException(MarkupException.Kind.UNRESOLVED_STYLE,
                        paths.current(), element.line(), element.column(),
                        "background drawable is not tintable");
            }
        }

        private com.badlogic.gdx.graphics.Color color(Element element, ResolvedStyle style) {
            String name = style.has("font-color") ? style.get("font-color") : style.get("color");
            return color(element, name);
        }

        private com.badlogic.gdx.graphics.Color color(Element element, String name) {
            com.badlogic.gdx.graphics.Color color = BuildContext.parseColor(skin, name);
            if (color == null) {
                throw new MarkupException(MarkupException.Kind.UNRESOLVED_STYLE,
                        paths.current(), element.line(), element.column(),
                        "skin has no color named \"" + name + "\"");
            }
            return color;
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
