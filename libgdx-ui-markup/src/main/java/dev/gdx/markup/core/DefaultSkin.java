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
    private static final Color BACKGROUND = Color.valueOf("172033ff");
    private static final Color PANEL = Color.valueOf("26324aff");
    private static final Color PANEL_ALT = Color.valueOf("303e5aff");
    private static final Color ACCENT = Color.valueOf("69d2e7ff");
    private static final Color ACCENT_OVER = Color.valueOf("8ce2efff");
    private static final Color ACCENT_DOWN = Color.valueOf("3d9fb4ff");
    private static final Color TEXT = Color.valueOf("f4f7ffff");
    private static final Color MUTED = Color.valueOf("aebbd0ff");
    private static final Color FOCUSED_FIELD = Color.valueOf("3a4c6eff");
    private static final Color SELECTION = Color.valueOf("477f91ff");
    private static final Color DISABLED = Color.valueOf("56647aff");

    private DefaultSkin() {
    }

    /** Builds a fresh skin; call on the render thread and dispose when done. */
    public static Skin create() {
        Skin skin = new Skin();
        Texture pixelTexture = new Texture(createPixel());
        pixelTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        BitmapFont font = new BitmapFont();
        font.getData().markupEnabled = false;
        skin.add("pixel", pixelTexture);
        skin.add("default-font", font);

        addColors(skin);
        TextureRegionDrawable pixel = new TextureRegionDrawable(new TextureRegion(pixelTexture));
        addDrawables(skin, pixel);
        addStyles(skin, font, pixel);
        return skin;
    }

    private static Pixmap createPixel() {
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(Color.WHITE);
        pixmap.fill();
        return pixmap;
    }

    private static void addColors(Skin skin) {
        skin.add("background", new Color(BACKGROUND));
        skin.add("panel", new Color(PANEL));
        skin.add("panel-alt", new Color(PANEL_ALT));
        skin.add("accent", new Color(ACCENT));
        skin.add("accent-over", new Color(ACCENT_OVER));
        skin.add("accent-down", new Color(ACCENT_DOWN));
        skin.add("pressed", new Color(ACCENT_DOWN));
        skin.add("text", new Color(TEXT));
        skin.add("muted", new Color(MUTED));
        skin.add("field-focused", new Color(FOCUSED_FIELD));
        skin.add("selection", new Color(SELECTION));
        skin.add("disabled", new Color(DISABLED));
    }

    private static void addDrawables(Skin skin, TextureRegionDrawable pixel) {
        addDrawable(skin, "panel", pixel.tint(PANEL));
        addDrawable(skin, "panel-alt", pixel.tint(PANEL_ALT));
        addDrawable(skin, "accent", pixel.tint(ACCENT));
        addDrawable(skin, "accent-over", pixel.tint(ACCENT_OVER));
        addDrawable(skin, "accent-down", pixel.tint(ACCENT_DOWN));
        addDrawable(skin, "pressed", pixel.tint(ACCENT_DOWN));
        addDrawable(skin, "field", pixel.tint(PANEL_ALT));
        addDrawable(skin, "field-focused", pixel.tint(FOCUSED_FIELD));
        addDrawable(skin, "field-disabled", pixel.tint(DISABLED));
        addDrawable(skin, "checkbox-off", pixel.tint(DISABLED));
        addDrawable(skin, "checkbox-on", pixel.tint(ACCENT));
        addDrawable(skin, "checkbox-over", pixel.tint(ACCENT_OVER));
        addDrawable(skin, "checkbox-off-disabled", pixel.tint(Color.valueOf("3a4354ff")));
        addDrawable(skin, "checkbox-on-disabled", pixel.tint(Color.valueOf("477f91ff")));
        addDrawable(skin, "button-disabled", pixel.tint(DISABLED));
        addDrawable(skin, "window", pixel.tint(Color.valueOf("354562ff")));
        addDrawable(skin, "scroll-bg", pixel.tint(Color.valueOf("202a3fff")));
        addDrawable(skin, "scroll-bar", pixel.tint(Color.valueOf("1a2233ff")));
        addDrawable(skin, "scroll-knob", pixel.tint(ACCENT));
        addDrawable(skin, "selection", pixel.tint(SELECTION));
        addDrawable(skin, "slider-bg", pixel.tint(Color.valueOf("202a3fff")));
        addDrawable(skin, "slider-knob", pixel.tint(ACCENT));
        addDrawable(skin, "progress-bg", pixel.tint(Color.valueOf("202a3fff")));
        addDrawable(skin, "progress-fill", pixel.tint(ACCENT));
    }

    private static void addDrawable(Skin skin, String name, Drawable drawable) {
        // Skin keys resources by their runtime class; tint() returns SpriteDrawable, so register
        // under the Drawable.class key to make getDrawable(name) resolve.
        skin.add(name, drawable, Drawable.class);
    }

    private static void addStyles(Skin skin, BitmapFont font, TextureRegionDrawable pixel) {
        LabelStyle label = new LabelStyle(font, TEXT);
        skin.add("label", label);
        skin.add("default", label);

        TextButtonStyle button = new TextButtonStyle();
        button.up = pixel.tint(ACCENT);
        button.down = pixel.tint(ACCENT_DOWN);
        button.over = pixel.tint(ACCENT_OVER);
        button.checked = pixel.tint(ACCENT_DOWN);
        button.disabled = skin.getDrawable("button-disabled");
        button.font = font;
        button.fontColor = Color.valueOf("10202aff");
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
        checkBox.fontColor = TEXT;
        checkBox.disabledFontColor = MUTED;
        skin.add("checkbox", checkBox);
        skin.add("default", checkBox);

        TextFieldStyle field = new TextFieldStyle();
        field.font = font;
        field.fontColor = TEXT;
        field.messageFont = font;
        field.messageFontColor = MUTED;
        field.background = skin.getDrawable("field");
        field.focusedBackground = skin.getDrawable("field-focused");
        field.disabledBackground = skin.getDrawable("field-disabled");
        field.cursor = skin.getDrawable("accent");
        field.selection = skin.getDrawable("selection");
        skin.add("textfield", field);
        skin.add("default", field);

        WindowStyle window = new WindowStyle();
        window.titleFont = font;
        window.titleFontColor = TEXT;
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
        list.fontColorUnselected = TEXT;
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
        selectBox.fontColor = TEXT;
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
