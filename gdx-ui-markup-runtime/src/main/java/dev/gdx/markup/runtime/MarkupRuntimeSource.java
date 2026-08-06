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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     */
    public static MarkupRuntimeSource register(AgentRuntime runtime, MarkupDocument document,
            BuiltUi ui, String uiSessionId) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(ui, "ui");
        IdentifierSupport.check(uiSessionId, "uiSessionId");
        MarkupRuntimeSource source = new MarkupRuntimeSource();
        Walk walk = new Walk();
        walk.walk(source, runtime, ui.root(), document.root(), uiSessionId);
        return source;
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

    private void add(EntityRegistration entity, UiBindingRegistration binding, String entityId) {
        entities.add(entity);
        bindings.add(binding);
        registered.add(entityId);
    }

    /** One document walk; owns element counting, paths, and actor lookup. */
    private static final class Walk {
        private final Map<String, Integer> sameTagSiblings = new HashMap<>();
        private final java.util.ArrayDeque<String> pathStack = new java.util.ArrayDeque<>();

        private void walk(MarkupRuntimeSource source, AgentRuntime runtime, Group root,
                Element element, String uiSessionId) {
            String path = pathOf(element.tag());
            pathStack.push(path);
            try {
                String entityId = element.attr(ENTITY_ATTRIBUTE);
                if (entityId != null) {
                    register(source, runtime, root, element, entityId, path, uiSessionId);
                }
                for (Element child : element.children()) {
                    walk(source, runtime, root, child, uiSessionId);
                }
            } finally {
                pathStack.pop();
            }
        }

        private void register(MarkupRuntimeSource source, AgentRuntime runtime, Group root,
                Element element, String entityId, String path, String uiSessionId) {
            if (source.registered.size() >= MAX_ENTITIES) {
                throw new MarkupException(MarkupException.Kind.TOO_LARGE, path, element.line(),
                        element.column(),
                        "more than " + MAX_ENTITIES + " runtime entities declared");
            }
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
            String finalDisplayName = declaredName != null ? declaredName
                    : element.name() != null ? element.name()
                    : element.label() != null ? element.label()
                    : element.text() != null ? element.text() : entityId;
            IdentifierSupport.check(entityId, ENTITY_ATTRIBUTE);
            IdentifierSupport.check(type, TYPE_ATTRIBUTE);
            IdentifierSupport.check(property, PROPERTY_ATTRIBUTE);
            String finalProperty = property;
            EntityRegistration entity = runtime.entities().register(
                    EntityId.of(entityId), EntityType.of(type), () -> finalDisplayName,
                    inspector -> inspector.property(finalProperty, () -> valueOf(actor)));
            UiBindingRegistration binding = runtime.uiCorrelations().register(new UiBinding(
                    "markup:" + entityId + ":" + property, EntityId.of(entityId),
                    Optional.of(property), uiSessionId, actor.getName(),
                    UiBindingValidity.always()));
            source.add(entity, binding, entityId);
        }

        private static Actor resolveActor(Group root, String id) {
            if (id.equals(root.getName())) {
                return root;
            }
            return root.findActor(id);
        }

        private String pathOf(String tag) {
            Integer count = sameTagSiblings.merge(tag, 1, Integer::sum) - 1;
            String segment = count == 0 ? tag : tag + "[" + count + "]";
            if (pathStack.isEmpty()) {
                return segment;
            }
            return pathStack.peek() + "/" + segment;
        }
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

        private static void check(String value, String attribute) {
            Objects.requireNonNull(value, attribute);
            if (value.isBlank() || value.length() > 256
                    || !value.matches("[A-Za-z0-9_-]+")) {
                throw new MarkupException(MarkupException.Kind.INVALID_VALUE, "", 0, 0,
                        "invalid value for " + attribute + ": \"" + value + "\"");
            }
        }
    }
}
