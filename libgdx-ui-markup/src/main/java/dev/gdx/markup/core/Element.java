package dev.gdx.markup.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable parsed element. Semantics are extracted at parse time so downstream layers never
 * re-read raw attribute maps: {@code id} drives the test identifier, {@code name} the accessible
 * name, {@code label} the human label, and {@code text} the visible widget text.
 */
public record Element(
        String tag,
        String id,
        String name,
        String label,
        String text,
        Map<String, String> attrs,
        List<String> classes,
        List<Element> children,
        int line,
        int column) {
    /** Validates the immutable shape. */
    public Element {
        attrs = Map.copyOf(Objects.requireNonNull(attrs, "attrs"));
        classes = List.copyOf(Objects.requireNonNull(classes, "classes"));
        children = List.copyOf(Objects.requireNonNull(children, "children"));
        Objects.requireNonNull(tag, "tag");
    }

    /** Returns the value of one raw attribute, or {@code null} when absent. */
    public String attr(String name) {
        return attrs.get(name);
    }

    /** Returns whether the element carries the named class. */
    public boolean hasClass(String className) {
        return classes.contains(className);
    }

    /** Returns whether the element is a leaf (has no element children). */
    public boolean isLeaf() {
        return children.isEmpty();
    }
}
