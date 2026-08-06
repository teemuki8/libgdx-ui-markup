package dev.gdx.markup.core;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.List;
import com.badlogic.gdx.scenes.scene2d.ui.ProgressBar;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import java.util.Arrays;

/**
 * The vocabulary's built-in factories. Each factory creates the widget and applies only
 * tag-specific attributes; common attributes, cell constraints, CSS, and semantics are applied
 * by {@link MarkupBuilder} after the factory returns.
 */
final class BuiltinTagFactories {
    private BuiltinTagFactories() {
    }

    static void install(MarkupRegistry registry) {
        registry.register("table", (element, context) -> new Table(context.skin()));
        registry.register("stack", (element, context) -> new com.badlogic.gdx.scenes.scene2d.ui.Stack());
        registry.register("group", (element, context) -> new com.badlogic.gdx.scenes.scene2d.Group());
        registry.register("scrollpane", (element, context) -> new ScrollPane(null,
                context.resolveStyle(ScrollPane.ScrollPaneStyle.class)));
        registry.register("label", (element, context) -> new Label(text(element),
                context.resolveStyle(Label.LabelStyle.class)));
        registry.register("button", (element, context) -> new TextButton(text(element),
                context.resolveStyle(TextButton.TextButtonStyle.class)));
        registry.register("checkbox", (element, context) -> {
            CheckBox checkBox = new CheckBox(text(element),
                    context.resolveStyle(CheckBox.CheckBoxStyle.class));
            String checked = element.attr("checked");
            if (checked != null) {
                checkBox.setChecked(Boolean.parseBoolean(checked));
            }
            return checkBox;
        });
        registry.register("textfield", (element, context) -> {
            TextField field = new TextField(text(element),
                    context.resolveStyle(TextField.TextFieldStyle.class));
            String editable = element.attr("editable");
            if (editable != null) {
                field.setDisabled(!Boolean.parseBoolean(editable));
            }
            return field;
        });
        registry.register("selectbox", (element, context) -> {
            SelectBox<String> box = new SelectBox<>(
                    context.resolveStyle(SelectBox.SelectBoxStyle.class));
            box.setItems(items(element));
            return box;
        });
        registry.register("slider", (element, context) -> {
            float min = context.floatAttr("min", 0f);
            float max = context.floatAttr("max", 100f);
            float step = context.floatAttr("step", 1f);
            Slider slider = new Slider(min, max, step, false,
                    context.resolveStyle(Slider.SliderStyle.class));
            slider.setValue(context.floatAttr("value", min));
            return slider;
        });
        registry.register("progressbar", (element, context) -> {
            float min = context.floatAttr("min", 0f);
            float max = context.floatAttr("max", 100f);
            ProgressBar bar = new ProgressBar(min, max, 1f, false,
                    context.resolveStyle(ProgressBar.ProgressBarStyle.class));
            bar.setValue(context.floatAttr("value", min));
            return bar;
        });
        registry.register("image", (element, context) -> new Image(
                context.requireDrawable(element.attr("drawable"))));
        registry.register("window", (element, context) -> new Window(element.attr("title"),
                context.resolveStyle(Window.WindowStyle.class)));
        registry.register("list", (element, context) -> {
            List<String> list = new List<>(context.resolveStyle(List.ListStyle.class));
            list.setItems(items(element));
            return list;
        });
    }

    private static String text(Element element) {
        return element.text() == null ? "" : element.text();
    }

    private static String[] items(Element element) {
        return Arrays.stream(element.attr("items").split(","))
                .map(String::strip)
                .toArray(String[]::new);
    }
}
