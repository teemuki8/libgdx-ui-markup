package dev.gdx.markup.runtime;

import com.badlogic.gdx.scenes.scene2d.Actor;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import java.util.function.Supplier;

/**
 * Supplies the authoritative domain value for one markup-declared entity property.
 *
 * <p>Implementations map a markup entity ({@code data-runtime-entity}) and its named property
 * ({@code data-runtime-property}, default {@code value}) to a live {@link Supplier} the agent
 * runtime evaluates on every capture frame. The resolver itself is invoked once per entity
 * during {@link MarkupRuntimeSource#registerAuthoritative registration} on the render thread;
 * the returned supplier is subsequently evaluated by the agent-runtime capture thread, so it
 * must be thread-safe or capture stable state.
 *
 * <p>Returning {@code null} for a markup-declared entity fails registration during preflight
 * with a located {@link dev.gdx.markup.core.MarkupException} and leaves the runtime unmodified:
 * authoritative registration never falls back to widget readback.
 */
@FunctionalInterface
public interface RuntimeValueResolver {

    /**
     * Resolves the authoritative value supplier for one entity property.
     *
     * @param entityId the markup {@code data-runtime-entity} identifier
     * @param propertyId the markup property name ({@code data-runtime-property}, default
     *     {@code value})
     * @param actor the built actor bound to the entity, for correlation or fallback decisions
     * @return a non-null supplier of the entity's authoritative value, or {@code null} when the
     *     caller cannot supply one (fails preflight)
     */
    Supplier<RuntimeValue> resolve(String entityId, String propertyId, Actor actor);
}
