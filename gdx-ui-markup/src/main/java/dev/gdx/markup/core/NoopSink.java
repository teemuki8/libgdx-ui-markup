package dev.gdx.markup.core;

import com.badlogic.gdx.scenes.scene2d.Actor;

/**
 * Default sink for harness-less applications: keeps {@link Actor#setName} as the
 * {@code findActor}-able diagnostic name and drops every other semantic call. Even with no
 * harness, markup-built actors remain addressable by their {@code id}.
 */
public final class NoopSink implements SemanticSink {
    @Override public void role(Actor actor, String role) {
        // no harness; roles are structural in Scene2D and need no recording
    }

    @Override public void accessibleName(Actor actor, String name) {
        // no harness; the actor name already carries the id
    }

    @Override public void testId(Actor actor, String id) {
        actor.setName(id);
    }

    @Override public void label(Actor actor, String label) {
        // no harness; labels are derived from widget text at snapshot time
    }

    @Override public void property(Actor actor, String key, String value) {
        // no harness; data-* properties are consumed by adapters
    }
}
