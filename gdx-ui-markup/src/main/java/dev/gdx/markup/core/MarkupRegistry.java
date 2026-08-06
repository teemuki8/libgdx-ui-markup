package dev.gdx.markup.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-instance tag-to-factory registry (the gdx-lml provider-registry lesson). Built-in
 * factories cover the whole vocabulary; {@link #register} adds custom widgets without touching
 * the core. Registration is global per registry instance and must happen before building.
 */
public final class MarkupRegistry {
    private final Map<String, TagFactory> factories;

    /** Creates an empty registry; pre-populated only via {@link #defaultRegistry()}. */
    public MarkupRegistry() {
        factories = new HashMap<>();
    }

    /** Registers a factory for one tag, replacing any previous registration. */
    public void register(String tag, TagFactory factory) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(factory, "factory");
        factories.put(tag, factory);
    }

    /** Returns the factory for one tag, or throws a typed unknown-tag failure. */
    public TagFactory require(String tag, String elementPath, int line, int column) {
        TagFactory factory = factories.get(tag);
        if (factory == null) {
            throw new MarkupException(MarkupException.Kind.UNKNOWN_TAG, elementPath, line, column,
                    "no factory registered for <" + tag + ">");
        }
        return factory;
    }

    /** Returns a registry with the built-in vocabulary factories. */
    public static MarkupRegistry defaultRegistry() {
        MarkupRegistry registry = new MarkupRegistry();
        BuiltinTagFactories.install(registry);
        return registry;
    }
}
