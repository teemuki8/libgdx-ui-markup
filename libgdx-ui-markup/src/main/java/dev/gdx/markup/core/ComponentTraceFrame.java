package dev.gdx.markup.core;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/** One bounded component-expansion frame retained in a markup diagnostic. */
public record ComponentTraceFrame(String component, MarkupSourceLocation invocation)
        implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    /** Validates the immutable trace frame. */
    public ComponentTraceFrame {
        component = Objects.requireNonNull(component, "component");
        if (component.isBlank()) {
            throw new IllegalArgumentException("component must not be blank");
        }
        invocation = Objects.requireNonNull(invocation, "invocation");
    }
}
