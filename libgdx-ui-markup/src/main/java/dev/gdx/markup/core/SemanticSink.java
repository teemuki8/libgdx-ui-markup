package dev.gdx.markup.core;

import com.badlogic.gdx.scenes.scene2d.Actor;

/**
 * String-based semantic metadata SPI. The core has no harness dependency; adapters
 * (for example the libgdx-ui-harness {@code Semantics} facade) implement this interface
 * so markup-declared {@code id}/{@code name}/{@code label}/{@code role}/{@code data-*}
 * become exact automation metadata by construction.
 */
public interface SemanticSink {
    /** Emits the canonical role string for an actor tag ({@code button}, {@code checkbox}, …). */
    void role(Actor actor, String role);

    /** Emits the accessible name derived from the {@code name} attribute. */
    void accessibleName(Actor actor, String name);

    /** Emits the stable test identifier derived from the {@code id} attribute. */
    void testId(Actor actor, String id);

    /** Emits the human label derived from the {@code label} attribute. */
    void label(Actor actor, String label);

    /** Emits one {@code data-*} semantic property. */
    void property(Actor actor, String key, String value);
}
