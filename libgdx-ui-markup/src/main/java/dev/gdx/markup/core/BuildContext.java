package dev.gdx.markup.core;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import dev.gdx.markup.core.style.ResolvedStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Per-element build context handed to {@link TagFactory} implementations: the owning skin, the
 * element's resolved base CSS style, the semantic sink, and typed style/drawable/color/font
 * lookups that fail with {@code UNRESOLVED_STYLE} diagnostics carrying the element location.
 */
public final class BuildContext {
    private final Element element;
    private final Skin skin;
    private final ResolvedStyle style;
    private final SemanticSink sink;
    private final String elementPath;
    private final int line;
    private final int column;

    BuildContext(Element element, Skin skin, ResolvedStyle style, SemanticSink sink,
            String elementPath) {
        this.element = Objects.requireNonNull(element, "element");
        this.skin = Objects.requireNonNull(skin, "skin");
        this.style = Objects.requireNonNull(style, "style");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.elementPath = Objects.requireNonNull(elementPath, "elementPath");
        this.line = element.line();
        this.column = element.column();
    }

    /** Returns the element being built. */
    public Element element() {
        return element;
    }

    /** Returns the skin the builder compiles into. */
    public Skin skin() {
        return skin;
    }

    /** Returns the element's resolved base CSS style (empty when no stylesheet matched). */
    public ResolvedStyle style() {
        return style;
    }

    /** Returns the semantic sink receiving role/testId/accessibleName/label/property calls. */
    public SemanticSink sink() {
        return sink;
    }

    /** Returns the element path for typed diagnostics, for example {@code ui/table/button[2]}. */
    public String elementPath() {
        return elementPath;
    }

    /** Returns the element's source line, or 0 when unknown. */
    public int line() {
        return line;
    }

    /** Returns the element's source column, or 0 when unknown. */
    public int column() {
        return column;
    }

    /**
     * Resolves the widget style: the {@code style} attribute, else the first existing skin style
     * among {@code tag.class…}, {@code tag.class}, {@code tag}, else {@code default}. Missing
     * styles fail with {@code UNRESOLVED_STYLE} at the element location.
     */
    public <T> T resolveStyle(Class<T> styleClass) {
        Objects.requireNonNull(styleClass, "styleClass");
        String explicit = element.attr("style");
        if (explicit != null) {
            T style = skin.optional(explicit, styleClass);
            if (style != null) {
                return style;
            }
            throw unresolved("no " + styleClass.getSimpleName() + " style \"" + explicit
                    + "\" in the skin");
        }
        for (String candidate : styleCandidates()) {
            T style = skin.optional(candidate, styleClass);
            if (style != null) {
                return style;
            }
        }
        T fallback = skin.optional("default", styleClass);
        if (fallback != null) {
            return fallback;
        }
        throw unresolved("no " + styleClass.getSimpleName() + " style \"" + element.tag()
                + "\" (or \"default\") in the skin");
    }

    /** Returns the candidate skin style names for this element, most specific first. */
    public List<String> styleCandidates() {
        ArrayList<String> candidates = new ArrayList<>();
        if (!element.classes().isEmpty()) {
            candidates.add(element.tag() + "." + String.join(".", element.classes()));
            candidates.add(element.tag() + "." + element.classes().get(0));
        }
        candidates.add(element.tag());
        return List.copyOf(candidates);
    }

    /** Looks up one named drawable, failing with {@code UNRESOLVED_STYLE} when absent. */
    public Drawable requireDrawable(String name) {
        Objects.requireNonNull(name, "name");
        Drawable drawable = skin.optional(name, Drawable.class);
        if (drawable == null) {
            throw unresolved("skin has no drawable named \"" + name + "\"");
        }
        return drawable;
    }

    /** Looks up a named color or parses {@code #rrggbb[aa]}, failing when unknown. */
    public Color requireColor(String name) {
        Objects.requireNonNull(name, "name");
        Color color = parseColor(skin, name);
        if (color == null) {
            throw unresolved("skin has no color named \"" + name + "\"");
        }
        return color;
    }

    /** Looks up one named font, failing with {@code UNRESOLVED_STYLE} when absent. */
    public BitmapFont requireFont(String name) {
        Objects.requireNonNull(name, "name");
        BitmapFont font = skin.optional(name, BitmapFont.class);
        if (font == null) {
            throw unresolved("skin has no font named \"" + name + "\"");
        }
        return font;
    }

    /** Parses one float attribute with a typed invalid-value failure at the element location. */
    public float floatAttr(String name, float fallback) {
        String raw = element.attr(name);
        if (raw == null) {
            return fallback;
        }
        try {
            float parsed = Float.parseFloat(raw);
            if (Float.isFinite(parsed)) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the typed failure
        }
        throw new MarkupException(MarkupException.Kind.INVALID_VALUE, elementPath, line, column,
                "invalid numeric value for \"" + name + "\": \"" + raw + "\"");
    }

    private MarkupException unresolved(String message) {
        return new MarkupException(MarkupException.Kind.UNRESOLVED_STYLE, elementPath, line,
                column, message);
    }

    /** Parses {@code #rrggbb[aa]} or looks up a named skin color; {@code null} when unknown. */
    static Color parseColor(Skin skin, String value) {
        Objects.requireNonNull(value, "value");
        if (value.startsWith("#")) {
            try {
                return Color.valueOf(value);
            } catch (IllegalArgumentException failure) {
                return null;
            }
        }
        return skin.optional(value, Color.class);
    }
}
