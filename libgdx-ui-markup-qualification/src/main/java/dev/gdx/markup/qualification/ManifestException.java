package dev.gdx.markup.qualification;

import java.io.Serial;
import java.util.Objects;

/**
 * Typed diagnostic for corpus manifest parsing and corpus-contained path resolution. Follows the
 * {@code MarkupException.Kind} convention: callers branch on {@link #kind()}, never on message
 * text.
 */
public final class ManifestException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    /** Stable failure taxonomy for manifest bounds, schema, and path containment. */
    public enum Kind {
        /** Manifest file exceeds the fixed byte cap. */
        TOO_LARGE,
        /** Entry count exceeds the fixed cap. */
        TOO_MANY_ENTRIES,
        /** One string field exceeds the per-string length cap. */
        STRING_TOO_LONG,
        /** Aggregate string work across entries exceeds the fixed cap. */
        WORK_LIMIT,
        /** A field outside the bounded schema appears. */
        UNKNOWN_FIELD,
        /** A required field is absent. */
        MISSING_FIELD,
        /** A present field has an unexpected JSON type. */
        WRONG_TYPE,
        /** A value is outside its semantic range. */
        INVALID_VALUE,
        /** The document is not strict JSON. */
        INVALID_JSON,
        /** Filesystem access to the manifest failed. */
        IO,
        /** Entry id is not a lowercase slug. */
        INVALID_ID,
        /** A path field is absolute instead of corpus-relative. */
        ABSOLUTE_PATH,
        /** A path field is not a normalized relative path. */
        INVALID_PATH,
        /** A resolved path lexically escapes its root. */
        OUTSIDE_ROOT,
        /** A resolved path's real location escapes its root through a symlink. */
        SYMLINK_ESCAPE,
        /** A per-entry palette file exceeds the fixed byte cap. */
        PALETTE_TOO_LARGE,
    }

    private final Kind kind;

    /** Creates a typed diagnostic with the given stable kind and message. */
    public ManifestException(Kind kind, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** Creates a typed diagnostic wrapping an underlying failure. */
    public ManifestException(Kind kind, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), Objects.requireNonNull(cause, "cause"));
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** Returns the stable failure kind. */
    public Kind kind() {
        return kind;
    }
}
