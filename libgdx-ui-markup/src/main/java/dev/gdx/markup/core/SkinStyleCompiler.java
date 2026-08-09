package dev.gdx.markup.core;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox.CheckBoxStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle;
import com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle;
import com.badlogic.gdx.scenes.scene2d.ui.ProgressBar.ProgressBarStyle;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider.SliderStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Window.WindowStyle;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import dev.gdx.markup.core.style.CssDocument;
import dev.gdx.markup.core.style.CssRule;
import dev.gdx.markup.core.style.Selector;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Compiles the CSS subset into named skin styles: {@code tag} and {@code tag.class} selectors
 * with style-field properties extend the skin's per-widget style (copying the base style so the
 * default skin is never mutated in place). Pseudo-states map to the widget style's state fields
 * ({@code :hover} &rarr; {@code over}, {@code :pressed} &rarr; {@code down}, {@code :checked}
 * &rarr; {@code checked}, {@code :disabled} &rarr; {@code disabled}); the {@code background-*}
 * properties target the same fields explicitly, and {@code font-color}/{@code color}
 * follow the same state mapping ({@code :hover} &rarr; {@code overFontColor}, …). Fonts are
 * resolved per actor by {@link MarkupBuilder}, after the full cascade and XML overrides. Class-only
 * and id-only selectors are applied per-actor by the builder, which shares the same
 * pseudo-to-field mapping through the {@code setState*} methods below; a widget/state/property
 * combination without a target field fails with a located {@code STYLE_ERROR} instead of being
 * silently dropped.
 */
final class SkinStyleCompiler {
    private static final String BASE = "base";

    private final Skin skin;

    private SkinStyleCompiler(Skin skin) {
        this.skin = Objects.requireNonNull(skin, "skin");
    }

    static void compile(Skin skin, CssDocument css) {
        new SkinStyleCompiler(skin).compile(css);
    }

    private void compile(CssDocument css) {
        for (CssRule rule : css.rules()) {
            for (Selector selector : rule.selectors()) {
                compileSelector(selector, rule);
            }
        }
    }

    private void compileSelector(Selector selector, CssRule rule) {
        if (selector.tag() == null) {
            return; // class-only and id-only selectors apply per-actor at build time
        }
        Class<?> styleClass = styleClass(selector.tag());
        if (styleClass == null) {
            return; // ui/table/row/stack/group/image carry no skin style
        }
        String styleName = selector.className() == null
                ? selector.tag() : selector.tag() + "." + selector.className();
        Object base = style(styleName, styleClass, rule);
        Object copy = copyOf(base);
        for (Map.Entry<String, String> property : rule.properties().entrySet()) {
            applyProperty(copy, property.getKey(), property.getValue(), selector.pseudo(),
                    selector.tag(), rule);
        }
        skin.add(styleName, copy);
    }

    private Object style(String styleName, Class<?> styleClass, CssRule rule) {
        Object named = skin.optional(styleName, styleClass);
        if (named != null) {
            return named;
        }
        Object fallback = skin.optional("default", styleClass);
        if (fallback == null) {
            throw unresolved(rule, "no " + styleClass.getSimpleName() + " style \"" + styleName
                    + "\" (or \"default\") in the skin");
        }
        return fallback;
    }

    private void applyProperty(Object style, String property, String value, String pseudo,
            String tag, CssRule rule) {
        switch (property) {
            case "background", "background-over", "background-down", "background-checked",
                    "background-disabled" -> {
                String state = propertyState(property, pseudo);
                setStateDrawable(style, state, drawable(value, rule), tag, property, rule);
            }
            case "color", "font-color" -> setStateColor(style, propertyState(property, pseudo),
                    color(value, rule), tag, property, rule);
            case "font" -> {
                if (pseudo != null) {
                    throw unsupported(tag, pseudo, property, rule);
                }
                // Base fonts are resolved per actor after XML-over-CSS precedence is known.
            }
            default -> {
                // Layout, visibility, typography alignment, and sizing are actor/cell
                // properties handled by the builder rather than compiled into the Skin.
            }
        }
    }

    /**
     * Maps one CSS property plus its selector pseudo-state to the shared state vocabulary used
     * by the {@code setState*} field setters: explicit {@code background-*} properties name
     * their state directly, otherwise the selector's pseudo is the state and a {@code null}
     * pseudo (base rule) targets the base field.
     */
    static String propertyState(String property, String pseudo) {
        String explicit = switch (property) {
            case "background-over" -> "hover";
            case "background-down" -> "pressed";
            case "background-checked" -> "checked";
            case "background-disabled" -> "disabled";
            default -> null;
        };
        return explicit != null ? explicit : pseudo == null ? BASE : pseudo;
    }

    private Drawable drawable(String name, CssRule rule) {
        Drawable drawable = skin.optional(name, Drawable.class);
        if (drawable == null) {
            throw unresolved(rule, "skin has no drawable named \"" + name + "\"");
        }
        return drawable;
    }

    private Color color(String name, CssRule rule) {
        Color color = BuildContext.parseColor(skin, name);
        if (color == null) {
            throw unresolved(rule, "skin has no color named \"" + name + "\"");
        }
        return color;
    }

    private static MarkupException unresolved(CssRule rule, String message) {
        return new MarkupException(MarkupException.Kind.UNRESOLVED_STYLE, "css",
                rule.line(), rule.column(), message);
    }

    /**
     * Located {@code STYLE_ERROR} for a widget/state/property combination with no target field
     * (for example {@code label:hover { font-color: … }}). Shared with the builder so tagless
     * selectors report the same diagnostic at the source rule's selector coordinates.
     */
    static MarkupException unsupported(String tag, String state, String property,
            CssRule source) {
        return new MarkupException(MarkupException.Kind.STYLE_ERROR, "css",
                source.line(), source.column(),
                tag + " does not support the " + state + " state for " + property);
    }

    private static Class<?> styleClass(String tag) {
        return switch (tag) {
            case "label" -> LabelStyle.class;
            case "button" -> TextButtonStyle.class;
            case "checkbox" -> CheckBoxStyle.class;
            case "textfield" -> TextFieldStyle.class;
            case "selectbox" -> SelectBoxStyle.class;
            case "slider" -> SliderStyle.class;
            case "progressbar" -> ProgressBarStyle.class;
            case "window" -> WindowStyle.class;
            case "list" -> ListStyle.class;
            case "scrollpane" -> ScrollPaneStyle.class;
            default -> null;
        };
    }

    static Object copyOf(Object style) {
        Objects.requireNonNull(style, "style");
        return switch (style) {
            // Subclasses first: CheckBoxStyle extends TextButtonStyle, SliderStyle extends
            // ProgressBarStyle; a pattern switch would otherwise mark them dominated.
            case CheckBoxStyle s -> new CheckBoxStyle(s);
            case TextButtonStyle s -> new TextButtonStyle(s);
            case TextFieldStyle s -> new TextFieldStyle(s);
            case SelectBoxStyle s -> new SelectBoxStyle(s);
            case SliderStyle s -> new SliderStyle(s);
            case ProgressBarStyle s -> new ProgressBarStyle(s);
            case WindowStyle s -> new WindowStyle(s);
            case ListStyle s -> new ListStyle(s);
            case ScrollPaneStyle s -> new ScrollPaneStyle(s);
            case LabelStyle s -> new LabelStyle(s);
            default -> throw new IllegalArgumentException(
                    "unsupported style class " + style.getClass().getName());
        };
    }

    /**
     * Applies one state's drawable field (the shared pseudo-to-field mapping for
     * {@code background} and {@code background-*}, used by both tag compilation and the
     * builder's per-actor class/id application). Widgets without a field for the state fail
     * with a located {@code STYLE_ERROR}; a base-state combination on a widget that genuinely
     * has no such field stays a silent no-op to preserve prior behavior.
     */
    static void setStateDrawable(Object style, String state, Drawable drawable,
            String tag, String property, CssRule source) {
        switch (style) {
            case CheckBoxStyle s -> {
                switch (state) {
                    case BASE -> s.checkboxOff = drawable;
                    case "hover" -> s.checkboxOver = drawable;
                    case "pressed" -> s.checkboxOnOver = drawable;
                    case "checked" -> s.checkboxOn = drawable;
                    case "disabled" -> s.checkboxOffDisabled = drawable;
                    default -> throw unsupported(tag, state, property, source);
                }
            }
            case TextButtonStyle s -> {
                switch (state) {
                    case BASE -> s.up = drawable;
                    case "hover" -> s.over = drawable;
                    case "pressed" -> s.down = drawable;
                    case "checked" -> s.checked = drawable;
                    case "disabled" -> s.disabled = drawable;
                    default -> throw unsupported(tag, state, property, source);
                }
            }
            case TextFieldStyle s -> {
                switch (state) {
                    case BASE -> s.background = drawable;
                    case "hover" -> s.focusedBackground = drawable;
                    case "disabled" -> s.disabledBackground = drawable;
                    default -> throw unsupported(tag, state, property, source);
                }
            }
            case SelectBoxStyle s -> {
                switch (state) {
                    case BASE -> s.background = drawable;
                    case "hover" -> s.backgroundOver = drawable;
                    case "disabled" -> s.backgroundDisabled = drawable;
                    default -> throw unsupported(tag, state, property, source);
                }
            }
            case LabelStyle s -> {
                if (BASE.equals(state)) {
                    s.background = drawable;
                } else {
                    throw unsupported(tag, state, property, source);
                }
            }
            case WindowStyle s -> {
                if (BASE.equals(state)) {
                    s.background = drawable;
                } else {
                    throw unsupported(tag, state, property, source);
                }
            }
            case ScrollPaneStyle s -> {
                if (BASE.equals(state)) {
                    s.background = drawable;
                } else {
                    throw unsupported(tag, state, property, source);
                }
            }
            case ListStyle s -> {
                if (BASE.equals(state)) {
                    s.background = drawable;
                } else {
                    throw unsupported(tag, state, property, source);
                }
            }
            case SliderStyle s -> {
                if (BASE.equals(state)) {
                    s.background = drawable;
                } else {
                    throw unsupported(tag, state, property, source);
                }
            }
            case ProgressBarStyle s -> {
                if (BASE.equals(state)) {
                    s.background = drawable;
                } else {
                    throw unsupported(tag, state, property, source);
                }
            }
            default -> throw unsupported(tag, state, property, source);
        }
    }

    /**
     * Applies one state's font-color field (the shared pseudo-to-field mapping for
     * {@code color}/{@code font-color}); unsupported widget/state combinations fail located.
     */
    static void setStateColor(Object style, String state, Color color, String tag,
            String property, CssRule source) {
        switch (style) {
            case CheckBoxStyle s -> setTextButtonColor(s, state, color, tag, property, source);
            case TextButtonStyle s -> setTextButtonColor(s, state, color, tag, property, source);
            case TextFieldStyle s -> {
                switch (state) {
                    case BASE -> s.fontColor = color;
                    case "hover" -> s.focusedFontColor = color;
                    case "disabled" -> s.disabledFontColor = color;
                    default -> throw unsupported(tag, state, property, source);
                }
            }
            case SelectBoxStyle s -> {
                switch (state) {
                    case BASE -> s.fontColor = color;
                    case "hover" -> s.overFontColor = color;
                    case "disabled" -> s.disabledFontColor = color;
                    default -> throw unsupported(tag, state, property, source);
                }
            }
            case LabelStyle s -> {
                if (BASE.equals(state)) {
                    s.fontColor = color;
                } else {
                    throw unsupported(tag, state, property, source);
                }
            }
            case ListStyle s -> {
                if (BASE.equals(state)) {
                    s.fontColorUnselected = color;
                } else {
                    throw unsupported(tag, state, property, source);
                }
            }
            case WindowStyle s -> {
                if (BASE.equals(state)) {
                    s.titleFontColor = color;
                } else {
                    throw unsupported(tag, state, property, source);
                }
            }
            default -> {
                if (!BASE.equals(state)) {
                    throw unsupported(tag, state, property, source);
                }
                // Slider/ProgressBar/ScrollPane carry no font color; a base combination is
                // genuinely field-less and stays a silent no-op (prior behavior).
            }
        }
    }

    private static void setTextButtonColor(TextButtonStyle style, String state, Color color,
            String tag, String property, CssRule source) {
        switch (state) {
            case BASE -> style.fontColor = color;
            case "hover" -> style.overFontColor = color;
            case "pressed" -> style.downFontColor = color;
            case "checked" -> style.checkedFontColor = color;
            case "disabled" -> style.disabledFontColor = color;
            default -> throw unsupported(tag, state, property, source);
        }
    }

    /**
     * Applies one state's font field (the shared pseudo-to-field mapping for {@code font}).
     * No widget has state-specific fonts, so every pseudo state is unsupported and fails
     * located; only the base font field exists.
     */
    static void setStateFont(Object style, String state, BitmapFont font, String tag,
            String property, CssRule source) {
        switch (style) {
            case CheckBoxStyle s -> setTextButtonFont(s, state, font, tag, property, source);
            case TextButtonStyle s -> setTextButtonFont(s, state, font, tag, property, source);
            case TextFieldStyle s -> {
                if (BASE.equals(state)) {
                    s.font = font;
                    s.messageFont = font;
                } else {
                    throw unsupported(tag, state, property, source);
                }
            }
            case SelectBoxStyle s -> {
                if (BASE.equals(state)) {
                    s.font = font;
                    if (s.listStyle != null) {
                        s.listStyle = new ListStyle(s.listStyle);
                        s.listStyle.font = font;
                    }
                } else {
                    throw unsupported(tag, state, property, source);
                }
            }
            case LabelStyle s -> {
                if (BASE.equals(state)) {
                    s.font = font;
                } else {
                    throw unsupported(tag, state, property, source);
                }
            }
            case ListStyle s -> {
                if (BASE.equals(state)) {
                    s.font = font;
                } else {
                    throw unsupported(tag, state, property, source);
                }
            }
            case WindowStyle s -> {
                if (BASE.equals(state)) {
                    s.titleFont = font;
                } else {
                    throw unsupported(tag, state, property, source);
                }
            }
            default -> {
                if (!BASE.equals(state)) {
                    throw unsupported(tag, state, property, source);
                }
                // Slider/ProgressBar/ScrollPane carry no font; base stays a silent no-op.
            }
        }
    }

    private static void setTextButtonFont(TextButtonStyle style, String state, BitmapFont font,
            String tag, String property, CssRule source) {
        if (BASE.equals(state)) {
            style.font = font;
        } else {
            throw unsupported(tag, state, property, source);
        }
    }

}
