package dev.gdx.markup.runtime;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ProgressBar;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import dev.gdx.markup.core.BuiltUi;
import dev.gdx.markup.core.Element;
import dev.gdx.markup.core.ElementPathTracker;
import dev.gdx.markup.core.MarkupDocument;
import dev.gdx.markup.core.MarkupException;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityRegistration;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.UiBinding;
import io.github.teemuki8.libgdx.agent.runtime.core.UiBindingRegistration;
import io.github.teemuki8.libgdx.agent.runtime.core.UiBindingValidity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Agent-runtime value source over markup-declared actors: every element with a
 * {@code data-runtime-entity} attribute registers an agent-runtime entity whose named property
 * (default {@code value}) reads the widget's live state, plus a {@link UiBinding} associating
 * the entity property with the actor's UI control id in the given session. Registered against
 * the published {@code agent-runtime-core} 1.0.0 API; the harness consumes the same runtime
 * through its own observation source when released.
 *
 * <p>Must be created on the render thread (it reads and registers live actors). Callers own the
 * {@link AgentRuntime} and this source's lifecycle: close the source before rebuilding the UI,
 * then register again for the new actor tree.
 */
public final class MarkupRuntimeSource implements AutoCloseable {
    /** Maximum registered entities per source. */
    public static final int MAX_ENTITIES = 256;

    private static final String ENTITY_ATTRIBUTE = "data-runtime-entity";
    private static final String TYPE_ATTRIBUTE = "data-runtime-type";
    private static final String PROPERTY_ATTRIBUTE = "data-runtime-property";
    private static final String NAME_ATTRIBUTE = "data-runtime-name";
    private static final String DEFAULT_PROPERTY = "value";

    private final List<EntityRegistration> entities = new ArrayList<>();
    private final List<UiBindingRegistration> bindings = new ArrayList<>();
    private final List<String> registered = new ArrayList<>();

    private MarkupRuntimeSource() {
    }

    /**
     * Registers every {@code data-runtime-entity} element of the document against the actors in
     * {@code ui}. The element's {@code id} must be present and resolve to a built actor.
     *
     * <p>Registration is transactional: the complete immutable document is validated up front
     * (limits, ids, actor correspondence, property ids, resolved display names) without touching
     * the runtime, then every entity and binding handle is acquired before the source is
     * returned. If any commit step fails, the handles acquired so far are closed in exact reverse
     * acquisition order and the original failure is rethrown with cleanup failures suppressed, so
     * a failed call leaves the runtime unmodified and an immediately corrected retry succeeds.
     */
    public static MarkupRuntimeSource register(AgentRuntime runtime, MarkupDocument document,
            BuiltUi ui, String uiSessionId) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(ui, "ui");
        IdentifierSupport.check(uiSessionId, "uiSessionId", "", 0, 0);
        return RegistrationPlan.preflight(document, ui.root()).commit(runtime, uiSessionId);
    }

    /** Returns the entity ids registered by this source, in document order. */
    public List<String> registeredEntities() {
        return List.copyOf(registered);
    }

    /** Closes every entity and binding registration. */
    @Override public void close() {
        for (UiBindingRegistration binding : bindings) {
            binding.close();
        }
        bindings.clear();
        for (EntityRegistration entity : entities) {
            entity.close();
        }
        entities.clear();
        registered.clear();
    }

    /**
     * Immutable preflight result. Walking the complete document produces a copied list of fully
     * validated {@link PlannedRegistration} values without mutating the runtime; {@link #commit}
     * then acquires every handle transactionally.
     */
    private static final class RegistrationPlan {
        private final List<PlannedRegistration> registrations;

        private RegistrationPlan(List<PlannedRegistration> registrations) {
            this.registrations = registrations;
        }

        /** Walks the immutable document with the shared core path tracker and validates everything
         * that does not require the runtime. */
        static RegistrationPlan preflight(MarkupDocument document, Group root) {
            List<PlannedRegistration> planned = new ArrayList<>();
            ElementPathTracker tracker = new ElementPathTracker();
            preflightWalk(tracker, root, document.root(), planned);
            return new RegistrationPlan(List.copyOf(planned));
        }

        private static void preflightWalk(ElementPathTracker tracker, Group root,
                Element element, List<PlannedRegistration> planned) {
            String path = tracker.enter(element.tag());
            try {
                String entityId = element.attr(ENTITY_ATTRIBUTE);
                if (entityId != null) {
                    if (planned.size() >= MAX_ENTITIES) {
                        throw new MarkupException(MarkupException.Kind.TOO_LARGE, path,
                                element.line(), element.column(),
                                "more than " + MAX_ENTITIES + " runtime entities declared");
                    }
                    planned.add(planElement(root, element, entityId, path));
                }
                for (Element child : element.children()) {
                    preflightWalk(tracker, root, child, planned);
                }
            } finally {
                tracker.exit();
            }
        }

        private static PlannedRegistration planElement(Group root, Element element,
                String entityId, String path) {
            String id = element.id();
            if (id == null) {
                throw new MarkupException(MarkupException.Kind.INVALID_VALUE, path,
                        element.line(), element.column(),
                        ENTITY_ATTRIBUTE + " requires an id on <" + element.tag() + ">");
            }
            Actor actor = resolveActor(root, id);
            if (actor == null) {
                throw new MarkupException(MarkupException.Kind.INVALID_VALUE, path,
                        element.line(), element.column(),
                        "no built actor with id \"" + id + "\" for " + ENTITY_ATTRIBUTE);
            }
            String property = element.attr(PROPERTY_ATTRIBUTE);
            if (property == null || property.isBlank()) {
                property = DEFAULT_PROPERTY;
            }
            String type = element.attr(TYPE_ATTRIBUTE);
            if (type == null || type.isBlank()) {
                type = "widget";
            }
            String declaredName = element.attr(NAME_ATTRIBUTE);
            String displayName = declaredName != null ? declaredName
                    : element.name() != null ? element.name()
                    : element.label() != null ? element.label()
                    : element.text() != null ? element.text() : entityId;
            IdentifierSupport.check(entityId, ENTITY_ATTRIBUTE, path, element.line(),
                    element.column());
            IdentifierSupport.check(type, TYPE_ATTRIBUTE, path, element.line(),
                    element.column());
            IdentifierSupport.check(property, PROPERTY_ATTRIBUTE, path, element.line(),
                    element.column());
            return new PlannedRegistration(entityId, type, property, actor, displayName);
        }

        /**
         * Acquires every entity and binding handle in document order. On failure, closes the
         * handles acquired so far in exact reverse acquisition order, attaches any cleanup
         * failure with {@link Throwable#addSuppressed}, and rethrows the original failure.
         */
        MarkupRuntimeSource commit(AgentRuntime runtime, String uiSessionId) {
            List<EntityRegistration> entities = new ArrayList<>(registrations.size());
            List<UiBindingRegistration> bindings = new ArrayList<>(registrations.size());
            List<String> ids = new ArrayList<>(registrations.size());
            java.util.ArrayDeque<Runnable> acquired = new java.util.ArrayDeque<>();
            try {
                for (PlannedRegistration planned : registrations) {
                    EntityRegistration entity = runtime.entities().register(
                            EntityId.of(planned.entityId), EntityType.of(planned.type),
                            () -> planned.displayName,
                            inspector -> inspector.property(planned.property,
                                    () -> valueOf(planned.actor)));
                    entities.add(entity);
                    acquired.push(entity::close);
                    UiBindingRegistration binding = runtime.uiCorrelations().register(
                            new UiBinding("markup:" + planned.entityId + ":" + planned.property,
                                    EntityId.of(planned.entityId),
                                    Optional.of(planned.property), uiSessionId,
                                    planned.actor.getName(), UiBindingValidity.always()));
                    bindings.add(binding);
                    ids.add(planned.entityId);
                    acquired.push(binding::close);
                }
            } catch (RuntimeException failure) {
                while (!acquired.isEmpty()) {
                    try {
                        acquired.pop().run();
                    } catch (RuntimeException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
            MarkupRuntimeSource source = new MarkupRuntimeSource();
            source.entities.addAll(entities);
            source.bindings.addAll(bindings);
            source.registered.addAll(ids);
            return source;
        }

        private static Actor resolveActor(Group root, String id) {
            if (id.equals(root.getName())) {
                return root;
            }
            return root.findActor(id);
        }
    }

    /** One fully validated registration; immutable after preflight. */
    private record PlannedRegistration(String entityId, String type, String property,
            Actor actor, String displayName) {
    }

    /** Reads the widget's live state as a typed runtime value; generic actors report their name. */
    static RuntimeValue valueOf(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor instanceof TextField field) {
            return RuntimeValues.string(field.getText());
        }
        if (actor instanceof CheckBox checkBox) {
            return RuntimeValues.bool(checkBox.isChecked());
        }
        if (actor instanceof TextButton button) {
            return RuntimeValues.string(button.getText().toString());
        }
        if (actor instanceof Button button) {
            return RuntimeValues.bool(button.isChecked());
        }
        if (actor instanceof Slider slider) {
            return RuntimeValues.decimal(slider.getValue());
        }
        if (actor instanceof ProgressBar bar) {
            return RuntimeValues.decimal(bar.getValue());
        }
        if (actor instanceof SelectBox<?> box) {
            Object selected = box.getSelected();
            return RuntimeValues.string(selected == null ? "" : selected.toString());
        }
        if (actor instanceof com.badlogic.gdx.scenes.scene2d.ui.List<?> list) {
            Object selected = list.getSelected();
            return RuntimeValues.string(selected == null ? "" : selected.toString());
        }
        if (actor instanceof Label label) {
            return RuntimeValues.string(label.getText().toString());
        }
        return RuntimeValues.string(actor.getName());
    }

    /** Bounded identifier validation mirroring the agent-runtime constraints. */
    private static final class IdentifierSupport {
        private IdentifierSupport() {
        }

        private static void check(String value, String attribute, String path, int line,
                int column) {
            Objects.requireNonNull(value, attribute);
            if (value.isBlank() || value.length() > 256
                    || !value.matches("[A-Za-z0-9_-]+")) {
                throw new MarkupException(MarkupException.Kind.INVALID_VALUE, path, line, column,
                        "invalid value for " + attribute + ": \"" + value + "\"");
            }
        }
    }
}
