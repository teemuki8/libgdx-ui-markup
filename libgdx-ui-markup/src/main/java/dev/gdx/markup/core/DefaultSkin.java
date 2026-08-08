package dev.gdx.markup.core;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
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
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;

/**
 * Programmatic default skin for the whole vocabulary: a 1&times;1 pixel texture, the built-in
 * bitmap font, named colors and drawables (so CSS can reference {@code accent}, {@code panel},
 * …), and a named style per tag. Styles are named after their tag ({@code button},
 * {@code textfield}, …) plus a {@code default} alias for each type. Must be created on the GL
 * thread; the caller owns disposal.
 */
public final class DefaultSkin {
    /**
     * Optional system property pointing at a small flat-JSON palette override file, for
     * example {@code {"panel": "#16323aff", "accent": "#c05a2aff"}}. Each recognized color
     * name replaces the built-in default; unknown names and malformed values are ignored so
     * the default skin stays usable anywhere the property is absent. The qualification corpus
     * uses this to render recreations in each reference game's palette.
     */
    public static final String PALETTE_PROPERTY = "markup.skin.palette";

    /** Byte cap for the optional flat-JSON palette override file; read as cap + 1. */
    static final int MAX_PALETTE_BYTES = 8192;

    /** The named colors the default skin exposes; any subset may be overridden. */
    static final String[] COLOR_NAMES = {
        "background", "panel", "panel-alt", "accent", "accent-over", "accent-down",
        "text", "muted", "field-focused", "selection", "disabled",
    };

    private static final String BACKGROUND_HEX = "172033ff";
    private static final String PANEL_HEX = "26324aff";
    private static final String PANEL_ALT_HEX = "303e5aff";
    private static final String ACCENT_HEX = "69d2e7ff";
    private static final String ACCENT_OVER_HEX = "8ce2efff";
    private static final String ACCENT_DOWN_HEX = "3d9fb4ff";
    private static final String TEXT_HEX = "f4f7ffff";
    private static final String MUTED_HEX = "aebbd0ff";
    private static final String FOCUSED_FIELD_HEX = "3a4c6eff";
    private static final String SELECTION_HEX = "477f91ff";
    private static final String DISABLED_HEX = "56647aff";

    private DefaultSkin() {
    }

    /** Builds a fresh skin; call on the render thread and dispose when done. */
    public static Skin create() {
        return create(DefaultSkin::createPixel, pixmap -> new Texture(pixmap));
    }

    /** The effective palette: built-in hex values overridden by the optional palette file. */
    private static java.util.Map<String, String> palette() {
        java.util.Map<String, String> defaults = new java.util.LinkedHashMap<>();
        defaults.put("background", BACKGROUND_HEX);
        defaults.put("panel", PANEL_HEX);
        defaults.put("panel-alt", PANEL_ALT_HEX);
        defaults.put("accent", ACCENT_HEX);
        defaults.put("accent-over", ACCENT_OVER_HEX);
        defaults.put("accent-down", ACCENT_DOWN_HEX);
        defaults.put("text", TEXT_HEX);
        defaults.put("muted", MUTED_HEX);
        defaults.put("field-focused", FOCUSED_FIELD_HEX);
        defaults.put("selection", SELECTION_HEX);
        defaults.put("disabled", DISABLED_HEX);
        String property = System.getProperty(PALETTE_PROPERTY);
        if (property == null || property.isBlank()) {
            return defaults;
        }
        java.util.Map<String, String> overrides = readPaletteFile(java.nio.file.Path.of(property));
        for (java.util.Map.Entry<String, String> entry : overrides.entrySet()) {
            if (defaults.containsKey(entry.getKey())) {
                defaults.put(entry.getKey(), entry.getValue());
            }
        }
        return defaults;
    }

    /**
     * Reads a bounded flat JSON object of {@code "name": "#rrggbbaa"} pairs. Only the
     * recognized color names are used; the reader is intentionally tiny and strict so a
     * corrupted file cannot change the skin's shape. The file is read byte-bounded
     * (cap + 1) before any UTF-8/JSON string is allocated, so an oversized palette fails
     * without a full-file read.
     */
    static java.util.Map<String, String> readPaletteFile(java.nio.file.Path file) {
        java.util.Map<String, String> overrides = new java.util.LinkedHashMap<>();
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(MAX_PALETTE_BYTES + 1);
            if (bytes.length > MAX_PALETTE_BYTES) {
                throw new IllegalArgumentException("palette file too large: " + file);
            }
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"([a-zA-Z-]+)\"\\s*:\\s*\"(#[0-9a-fA-F]{8})\"")
                    .matcher(text);
            java.util.Set<String> names = java.util.Set.of(COLOR_NAMES);
            while (matcher.find()) {
                String key = matcher.group(1);
                if (names.contains(key)) {
                    overrides.put(key, matcher.group(2));
                }
            }
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("cannot read skin palette " + file, failure);
        }
        return overrides;
    }

    /**
     * Package-visible ownership seam: the render-thread tests inject tracking pixel/texture
     * factories to observe disposal. Production callers use {@link #create()}.
     */
    static Skin create(PixmapFactory pixels, TextureFactory textures) {
        Skin skin = new Skin();
        Pixmap pixmap = pixels.create();
        Texture pixelTexture;
        try {
            // Texture(Pixmap) copies the pixels during construction; the Pixmap is never retained.
            pixelTexture = textures.create(pixmap);
        } finally {
            pixmap.dispose();
        }
        pixelTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        BitmapFont font = new BitmapFont();
        font.getData().markupEnabled = false;
        skin.add("pixel", pixelTexture);
        skin.add("default-font", font);

        java.util.Map<String, String> palette = palette();
        addColors(skin, palette);
        TextureRegionDrawable pixel = new TextureRegionDrawable(new TextureRegion(pixelTexture));
        addDrawables(skin, pixel, palette);
        addStyles(skin, font, pixel, palette);
        return skin;
    }

    /** Pixel source for the skin's 1&times;1 upload. */
    @FunctionalInterface
    interface PixmapFactory {
        Pixmap create();
    }

    /** Texture uploader; the pixel is uploaded during construction. */
    @FunctionalInterface
    interface TextureFactory {
        Texture create(Pixmap pixmap);
    }

    private static Pixmap createPixel() {
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(Color.WHITE);
        pixmap.fill();
        return pixmap;
    }

    private static void addColors(Skin skin, java.util.Map<String, String> palette) {
        skin.add("background", new Color(Color.valueOf(palette.get("background"))));
        skin.add("panel", new Color(Color.valueOf(palette.get("panel"))));
        skin.add("panel-alt", new Color(Color.valueOf(palette.get("panel-alt"))));
        skin.add("accent", new Color(Color.valueOf(palette.get("accent"))));
        skin.add("accent-over", new Color(Color.valueOf(palette.get("accent-over"))));
        skin.add("accent-down", new Color(Color.valueOf(palette.get("accent-down"))));
        skin.add("pressed", new Color(Color.valueOf(palette.get("accent-down"))));
        skin.add("text", new Color(Color.valueOf(palette.get("text"))));
        skin.add("muted", new Color(Color.valueOf(palette.get("muted"))));
        skin.add("field-focused", new Color(Color.valueOf(palette.get("field-focused"))));
        skin.add("selection", new Color(Color.valueOf(palette.get("selection"))));
        skin.add("disabled", new Color(Color.valueOf(palette.get("disabled"))));
    }

    private static void addDrawables(Skin skin, TextureRegionDrawable pixel,
            java.util.Map<String, String> palette) {
        Color panel = Color.valueOf(palette.get("panel"));
        Color panelAlt = Color.valueOf(palette.get("panel-alt"));
        Color accent = Color.valueOf(palette.get("accent"));
        Color accentOver = Color.valueOf(palette.get("accent-over"));
        Color accentDown = Color.valueOf(palette.get("accent-down"));
        Color background = Color.valueOf(palette.get("background"));
        Color focused = Color.valueOf(palette.get("field-focused"));
        Color selection = Color.valueOf(palette.get("selection"));
        Color disabled = Color.valueOf(palette.get("disabled"));
        addDrawable(skin, "background", pixel.tint(background));
        addDrawable(skin, "panel", pixel.tint(panel));
        addDrawable(skin, "panel-alt", pixel.tint(panelAlt));
        addDrawable(skin, "accent", pixel.tint(accent));
        addDrawable(skin, "accent-over", pixel.tint(accentOver));
        addDrawable(skin, "accent-down", pixel.tint(accentDown));
        addDrawable(skin, "pressed", pixel.tint(accentDown));
        addDrawable(skin, "field", pixel.tint(panelAlt));
        addDrawable(skin, "field-focused", pixel.tint(focused));
        addDrawable(skin, "field-disabled", pixel.tint(disabled));
        addDrawable(skin, "checkbox-off", pixel.tint(disabled));
        addDrawable(skin, "checkbox-on", pixel.tint(accent));
        addDrawable(skin, "checkbox-over", pixel.tint(accentOver));
        addDrawable(skin, "checkbox-off-disabled", pixel.tint(Color.valueOf("3a4354ff")));
        addDrawable(skin, "checkbox-on-disabled", pixel.tint(Color.valueOf("477f91ff")));
        addDrawable(skin, "button-disabled", pixel.tint(disabled));
        addDrawable(skin, "window", pixel.tint(panel));
        addDrawable(skin, "scroll-bg", pixel.tint(Color.valueOf("202a3fff")));
        addDrawable(skin, "scroll-bar", pixel.tint(Color.valueOf("1a2233ff")));
        addDrawable(skin, "scroll-knob", pixel.tint(accent));
        addDrawable(skin, "selection", pixel.tint(selection));
        addDrawable(skin, "slider-bg", pixel.tint(Color.valueOf("202a3fff")));
        addDrawable(skin, "slider-knob", pixel.tint(accent));
        addDrawable(skin, "progress-bg", pixel.tint(Color.valueOf("202a3fff")));
        addDrawable(skin, "progress-fill", pixel.tint(accent));
    }

    private static void addDrawable(Skin skin, String name, Drawable drawable) {
        // Skin keys resources by their runtime class; tint() returns SpriteDrawable, so register
        // under the Drawable.class key to make getDrawable(name) resolve.
        skin.add(name, drawable, Drawable.class);
    }

    private static void addStyles(Skin skin, BitmapFont font, TextureRegionDrawable pixel,
            java.util.Map<String, String> palette) {
        Color text = Color.valueOf(palette.get("text"));
        Color muted = Color.valueOf(palette.get("muted"));
        Color accent = Color.valueOf(palette.get("accent"));
        Color accentOver = Color.valueOf(palette.get("accent-over"));
        Color accentDown = Color.valueOf(palette.get("accent-down"));
        LabelStyle label = new LabelStyle(font, text);
        skin.add("label", label);
        skin.add("default", label);

        TextButtonStyle button = new TextButtonStyle();
        button.up = pixel.tint(accent);
        button.down = pixel.tint(accentDown);
        button.over = pixel.tint(accentOver);
        button.checked = pixel.tint(accentDown);
        button.disabled = skin.getDrawable("button-disabled");
        button.font = font;
        button.fontColor = text;
        skin.add("button", button);
        skin.add("default", button);

        CheckBoxStyle checkBox = new CheckBoxStyle();
        checkBox.checkboxOff = skin.getDrawable("checkbox-off");
        checkBox.checkboxOn = skin.getDrawable("checkbox-on");
        checkBox.checkboxOver = skin.getDrawable("checkbox-over");
        checkBox.checkboxOnOver = skin.getDrawable("checkbox-over");
        checkBox.checkboxOffDisabled = skin.getDrawable("checkbox-off-disabled");
        checkBox.checkboxOnDisabled = skin.getDrawable("checkbox-on-disabled");
        checkBox.font = font;
        checkBox.fontColor = text;
        checkBox.disabledFontColor = muted;
        skin.add("checkbox", checkBox);
        skin.add("default", checkBox);

        TextFieldStyle field = new TextFieldStyle();
        field.font = font;
        field.fontColor = text;
        field.messageFont = font;
        field.messageFontColor = muted;
        field.background = skin.getDrawable("field");
        field.focusedBackground = skin.getDrawable("field-focused");
        field.disabledBackground = skin.getDrawable("field-disabled");
        field.cursor = skin.getDrawable("accent");
        field.selection = skin.getDrawable("selection");
        skin.add("textfield", field);
        skin.add("default", field);

        WindowStyle window = new WindowStyle();
        window.titleFont = font;
        window.titleFontColor = text;
        window.background = skin.getDrawable("window");
        skin.add("window", window);
        skin.add("default", window);

        ScrollPaneStyle scroll = new ScrollPaneStyle();
        scroll.background = skin.getDrawable("scroll-bg");
        scroll.vScroll = skin.getDrawable("scroll-bar");
        scroll.vScrollKnob = skin.getDrawable("scroll-knob");
        scroll.hScroll = skin.getDrawable("scroll-bar");
        scroll.hScrollKnob = skin.getDrawable("scroll-knob");
        skin.add("scrollpane", scroll);
        skin.add("default", scroll);

        ListStyle list = new ListStyle();
        list.font = font;
        list.fontColorSelected = Color.valueOf("10202aff");
        list.fontColorUnselected = text;
        list.selection = skin.getDrawable("selection");
        list.background = skin.getDrawable("scroll-bg");
        skin.add("list", list);
        skin.add("default", list);
        skin.add("selectbox-list", list);

        ScrollPaneStyle selectScroll = new ScrollPaneStyle();
        selectScroll.background = skin.getDrawable("scroll-bg");
        selectScroll.vScroll = skin.getDrawable("scroll-bar");
        selectScroll.vScrollKnob = skin.getDrawable("scroll-knob");
        selectScroll.hScroll = skin.getDrawable("scroll-bar");
        selectScroll.hScrollKnob = skin.getDrawable("scroll-knob");
        skin.add("selectbox-scroll", selectScroll);

        SelectBoxStyle selectBox = new SelectBoxStyle();
        selectBox.font = font;
        selectBox.fontColor = text;
        selectBox.background = skin.getDrawable("field");
        selectBox.backgroundOver = skin.getDrawable("field-focused");
        selectBox.backgroundOpen = skin.getDrawable("field-focused");
        selectBox.backgroundDisabled = skin.getDrawable("field-disabled");
        selectBox.scrollStyle = selectScroll;
        selectBox.listStyle = list;
        skin.add("selectbox", selectBox);
        skin.add("default", selectBox);

        SliderStyle slider = new SliderStyle();
        slider.background = skin.getDrawable("slider-bg");
        slider.knob = skin.getDrawable("slider-knob");
        skin.add("slider", slider);
        skin.add("default", slider);

        ProgressBarStyle progressBar = new ProgressBarStyle();
        progressBar.background = skin.getDrawable("progress-bg");
        progressBar.knobBefore = skin.getDrawable("progress-fill");
        skin.add("progressbar", progressBar);
        skin.add("default", progressBar);
    }
}
