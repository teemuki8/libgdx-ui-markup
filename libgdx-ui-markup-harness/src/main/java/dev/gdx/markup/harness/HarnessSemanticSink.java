package dev.gdx.markup.harness;

import com.badlogic.gdx.scenes.scene2d.Actor;
import dev.gdx.markup.core.SemanticSink;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.scene2d.Semantics;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adapter from the markup {@link SemanticSink} SPI to the harness {@link Semantics} facade:
 * markup-declared {@code id}/{@code name}/{@code label}/{@code data-*} become exact test
 * identifiers, accessible names, labels, and properties; canonical role strings map to the
 * harness {@link Role} enum, with an exact enum-member-name fallback. Unknown role strings are
 * skipped (testId/name still drive locators) rather than failing the build.
 */
public final class HarnessSemanticSink implements SemanticSink {
    private static final Logger LOG = Logger.getLogger(HarnessSemanticSink.class.getName());

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

    /** Wraps one live harness semantics facade (owned by a Scene2D session). */
    public HarnessSemanticSink(Semantics semantics) {
        this.semantics = Objects.requireNonNull(semantics, "semantics");
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
        semantics.setProperty(actor, key, value);
    }
}
