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
 * properties target the same fields explicitly. Class-only and id-only selectors are applied
 * per-actor by the builder, not here.
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
            applyProperty(copy, styleClass, property.getKey(), property.getValue(),
                    selector.pseudo(), rule);
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

    private void applyProperty(Object style, Class<?> styleClass, String property,
            String value, String pseudo, CssRule rule) {
        switch (property) {
            case "background", "background-over", "background-down", "background-checked",
                    "background-disabled" -> {
                String state = propertyState(property, pseudo);
                Drawable drawable = drawable(value, rule);
                setStateDrawable(style, state, drawable);
            }
            case "color", "font-color" -> setColorField(style, color(value, rule));
            case "font" -> setFontField(style, font(value, rule));
            default -> {
                // padding/margin/width/height/min-*/text-align/visible are actor and cell
                // properties handled by the builder, not the skin.
            }
        }
    }

    private static String propertyState(String property, String pseudo) {
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

    private BitmapFont font(String name, CssRule rule) {
        BitmapFont font = skin.optional(name, BitmapFont.class);
        if (font == null) {
            throw unresolved(rule, "skin has no font named \"" + name + "\"");
        }
        return font;
    }

    private static MarkupException unresolved(CssRule rule, String message) {
        return new MarkupException(MarkupException.Kind.UNRESOLVED_STYLE, "css",
                rule.ruleIndex(), 0, message);
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

    private static void setStateDrawable(Object style, String state, Drawable drawable) {
        switch (style) {
            case CheckBoxStyle s -> {
                switch (state) {
                    case BASE -> s.checkboxOff = drawable;
                    case "hover" -> s.checkboxOver = drawable;
                    case "pressed" -> s.checkboxOnOver = drawable;
                    case "checked" -> s.checkboxOn = drawable;
                    case "disabled" -> s.checkboxOffDisabled = drawable;
                    default -> {
                    }
                }
            }
            case TextButtonStyle s -> {
                switch (state) {
                    case BASE -> s.up = drawable;
                    case "hover" -> s.over = drawable;
                    case "pressed" -> s.down = drawable;
                    case "checked" -> s.checked = drawable;
                    case "disabled" -> s.disabled = drawable;
                    default -> {
                    }
                }
            }
            case TextFieldStyle s -> {
                switch (state) {
                    case BASE -> s.background = drawable;
                    case "hover" -> s.focusedBackground = drawable;
                    case "disabled" -> s.disabledBackground = drawable;
                    default -> {
                    }
                }
            }
            case SelectBoxStyle s -> {
                switch (state) {
                    case BASE -> s.background = drawable;
                    case "hover" -> s.backgroundOver = drawable;
                    case "disabled" -> s.backgroundDisabled = drawable;
                    default -> {
                    }
                }
            }
            case WindowStyle s -> {
                if (BASE.equals(state)) {
                    s.background = drawable;
                }
            }
            case ScrollPaneStyle s -> {
                if (BASE.equals(state)) {
                    s.background = drawable;
                }
            }
            case ListStyle s -> {
                if (BASE.equals(state)) {
                    s.background = drawable;
                }
            }
            case SliderStyle s -> {
                if (BASE.equals(state)) {
                    s.background = drawable;
                }
            }
            case ProgressBarStyle s -> {
                if (BASE.equals(state)) {
                    s.background = drawable;
                }
            }
            default -> {
            }
        }
    }

    static void setColor(Object style, Color color) {
        setColorField(style, color);
    }

    static void setFont(Object style, BitmapFont font) {
        setFontField(style, font);
    }

    static void setBaseDrawable(Object style, Drawable drawable) {
        setStateDrawable(style, BASE, drawable);
    }

    private static void setColorField(Object style, Color color) {
        switch (style) {
            case CheckBoxStyle s -> s.fontColor = color;
            case TextButtonStyle s -> s.fontColor = color;
            case TextFieldStyle s -> s.fontColor = color;
            case SelectBoxStyle s -> s.fontColor = color;
            case ListStyle s -> s.fontColorUnselected = color;
            case WindowStyle s -> s.titleFontColor = color;
            case LabelStyle s -> s.fontColor = color;
            default -> {
            }
        }
    }

    private static void setFontField(Object style, BitmapFont font) {
        switch (style) {
            case CheckBoxStyle s -> s.font = font;
            case TextButtonStyle s -> s.font = font;
            case TextFieldStyle s -> s.font = font;
            case SelectBoxStyle s -> s.font = font;
            case ListStyle s -> s.font = font;
            case WindowStyle s -> s.titleFont = font;
            case LabelStyle s -> s.font = font;
            default -> {
            }
        }
    }

}
