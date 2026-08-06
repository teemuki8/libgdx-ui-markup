package dev.gdx.markup.core;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import java.util.List;
import java.util.Objects;

/**
 * One completed build: the root group (a root {@code table} becomes the root directly) plus
 * every built actor in document order (parents before children), for stage attachment and
 * harness session wiring.
 */
public record BuiltUi(Group root, List<Actor> actors) {
    /** Validates the immutable shape. */
    public BuiltUi {
        Objects.requireNonNull(root, "root");
        actors = List.copyOf(Objects.requireNonNull(actors, "actors"));
    }
}
