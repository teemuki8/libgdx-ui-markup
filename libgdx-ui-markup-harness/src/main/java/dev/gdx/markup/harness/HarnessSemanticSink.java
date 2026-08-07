package dev.gdx.markup.harness;

import com.badlogic.gdx.scenes.scene2d.Actor;
import dev.gdx.markup.core.SemanticSink;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.RuntimeBinding;
import dev.gdx.uiharness.scene2d.Semantics;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adapter from the markup {@link SemanticSink} SPI to the harness {@link Semantics} facade:
 * markup-declared {@code id}/{@code name}/{@code label}/{@code data-*} become exact test
 * identifiers, accessible names, labels, and properties; canonical role strings map to the
 * harness {@link Role} enum, with an exact enum-member-name fallback. Unknown role strings are
 * skipped (testId/name still drive locators) rather than failing the build.
 *
 * <p>Elements declaring {@code data-runtime-entity} (optionally {@code data-runtime-property},
 * default {@code value}) are additionally bound to their agent-runtime entity through
 * {@link Semantics#bind}, carrying the caller's frame-correlation token so
 * {@code ui_runtime_compare} can prove frame equality. Actor keys are weakly held, so actors
 * replaced by a hot reload are not retained by this sink.
 */
public final class HarnessSemanticSink implements SemanticSink {
    private static final Logger LOG = Logger.getLogger(HarnessSemanticSink.class.getName());

    private static final String ENTITY_KEY = "runtime-entity";
    private static final String PROPERTY_KEY = "runtime-property";
    private static final String DEFAULT_RUNTIME_PROPERTY = "value";

    /** Canonical markup roles to harness roles. */
    private static final Map<String, Role> ROLES = Map.of(
            "button", Role.BUTTON,
            "checkbox", Role.CHECKBOX,
            "textfield", Role.TEXT_FIELD,
            "selectbox", Role.SELECT,
            "slider", Role.SLIDER,
            "progressbar", Role.PROGRESS_BAR,
            "list", Role.LIST,
            "window", Role.WINDOW);

    private final Semantics semantics;
    private final String runtimeCorrelationToken;
    private final WeakHashMap<Actor, PendingBinding> pendingBindings = new WeakHashMap<>();

    /**
     * Wraps one live harness semantics facade (owned by a Scene2D session).
     *
     * <p>The {@code runtimeCorrelationToken} is a contract between this sink and the
     * application's frame-capture path: it must equal the {@code UiFrameCorrelation}
     * {@code correlationToken()} recorded for each rendered frame (on the render thread) under
     * which {@code ui_runtime_compare} proves frame equality. Every {@code data-runtime-entity}
     * binding carries this token, and the harness {@code AgentRuntimeObservationSource} resolves
     * bindings only against correlations recorded with the same token. A token that matches
     * nothing silently degrades {@code ui_runtime_compare} to {@code STALE}/{@code UNCORRELATED}
     * with no diagnostic naming the token, so choose one stable application-scoped value and
     * record every frame's correlation under it.
     *
     * <p>The preview uses {@code markup-preview-frame}; an application with its own frame
     * correlation must pass its own token here, not the preview's value. See
     * {@code docs/guides/embedding.md} for the full wiring recipe.
     *
     * @param runtimeCorrelationToken the frame-correlation token recorded against the session
     *     each rendered frame; must equal the {@code UiFrameCorrelation} correlation token
     *     recorded by the application's frame-capture path
     */
    public HarnessSemanticSink(Semantics semantics, String runtimeCorrelationToken) {
        this.semantics = Objects.requireNonNull(semantics, "semantics");
        this.runtimeCorrelationToken =
                Objects.requireNonNull(runtimeCorrelationToken, "runtimeCorrelationToken");
    }

    @Override public void role(Actor actor, String role) {
        Role mapped = ROLES.get(role);
        if (mapped == null) {
            try {
                mapped = Role.valueOf(role.toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException unknown) {
                LOG.log(Level.FINE, "skipping unknown harness role {0} for {1}",
                        new Object[] {role, actor.getName()});
                return;
            }
        }
        semantics.setRole(actor, mapped);
    }

    @Override public void accessibleName(Actor actor, String name) {
        semantics.setAccessibleName(actor, name);
    }

    @Override public void testId(Actor actor, String id) {
        semantics.setTestId(actor, id);
    }

    @Override public void label(Actor actor, String label) {
        semantics.setLabel(actor, label);
    }

    @Override public void property(Actor actor, String key, String value) {
        if (ENTITY_KEY.equals(key)) {
            pendingBindings.computeIfAbsent(actor, ignored -> new PendingBinding()).entityId = value;
            bindIfComplete(actor);
        } else if (PROPERTY_KEY.equals(key)) {
            pendingBindings.computeIfAbsent(actor, ignored -> new PendingBinding()).propertyId = value;
            bindIfComplete(actor);
        }
        semantics.setProperty(actor, key, value);
    }

    /**
     * Binds the actor once its entity is known; a later {@code data-runtime-property} replaces
     * the default {@code value} property. Binding order in the markup does not matter.
     */
    private void bindIfComplete(Actor actor) {
        PendingBinding pending = pendingBindings.get(actor);
        if (pending == null || pending.entityId == null) {
            return;
        }
        String propertyId = pending.propertyId != null
                ? pending.propertyId : DEFAULT_RUNTIME_PROPERTY;
        semantics.bind(actor, new RuntimeBinding(
                pending.entityId, propertyId, null, null, runtimeCorrelationToken));
    }

    /** Per-actor entity/property accumulation across one element's {@code data-*} attributes. */
    private static final class PendingBinding {
        String entityId;
        String propertyId;
    }
}
