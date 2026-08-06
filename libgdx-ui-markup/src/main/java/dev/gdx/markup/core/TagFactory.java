package dev.gdx.markup.core;

import com.badlogic.gdx.scenes.scene2d.Actor;

/**
 * Creates one widget and applies its tag-specific attributes. Custom widgets register a factory
 * on a {@link MarkupRegistry} and gain the full common-attribute, CSS, cell, and semantic
 * pipeline without touching the core. Factories must not touch the Stage or run input.
 */
@FunctionalInterface
public interface TagFactory {
    /** Creates the actor for one element; tag-specific attributes are applied here. */
    Actor create(Element element, BuildContext context);
}
