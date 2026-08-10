package dev.gdx.markup.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable GL-free XML node before component expansion and concrete-dialect validation. */
record RawElement(
        String tag,
        Map<String, RawAttribute> attrs,
        String text,
        List<RawElement> children,
        MarkupSourceLocation origin,
        List<ComponentTraceFrame> componentTrace) {
    RawElement {
        tag = Objects.requireNonNull(tag, "tag");
        attrs = Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
        text = Objects.requireNonNull(text, "text");
        children = List.copyOf(children);
        origin = Objects.requireNonNull(origin, "origin");
        componentTrace = List.copyOf(componentTrace);
    }
}
