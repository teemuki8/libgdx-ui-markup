package dev.gdx.markup.qualification;

import java.io.Serial;
import java.util.Objects;

/**
 * Typed diagnostic for the authenticated remote-reference pipeline. Follows the
 * {@code MarkupException.Kind} convention: callers branch on {@link #kind()}, never on message
 * text. Policy, identity, cache, decode, and transport failures raise this exception so the
 * qualification fails loudly instead of silently skipping; {@code Optional.empty} from
 * {@link ReferenceImageStore#reference} is reserved for references that are explicitly absent.
 */
public final class ReferenceException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    /** Stable failure taxonomy for reference fetching, authentication, and decoding. */
    public enum Kind {
        /** Target URL shape, host allowlist, port, or resolved address policy violation. */
        UNSAFE_TARGET,
        /** Payload or cache bytes do not match the declared digest, length, media type, or
         *  header dimensions. */
        IDENTITY_MISMATCH,
        /** The image header or pixel data cannot be decoded. */
        DECODE,
        /** The session cache file is forged, a symlink, or unreadable. */
        CACHE,
        /** Transport-level failure: connection, TLS, timeout, or interrupted fetch. */
        IO,
        /** The server responded with an unexpected status (not 200 and not 404/410). */
        UNEXPECTED_STATUS,
    }

    private final Kind kind;

    /** Creates a typed diagnostic with the given stable kind and message. */
    public ReferenceException(Kind kind, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** Creates a typed diagnostic wrapping an underlying failure. */
    public ReferenceException(Kind kind, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), Objects.requireNonNull(cause, "cause"));
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** Returns the stable failure kind. */
    public Kind kind() {
        return kind;
    }
}
