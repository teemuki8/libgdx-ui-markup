package dev.gdx.markup.core;

import java.util.Objects;

/** One raw XML attribute with its containing element's source location. */
record RawAttribute(String value, MarkupSourceLocation origin) {
    RawAttribute {
        value = Objects.requireNonNull(value, "value");
        origin = Objects.requireNonNull(origin, "origin");
    }
}
