package dev.gdx.markup.qualification;

import java.util.Objects;

/**
 * Internal typed fatal for the runner's ownership contract: a child process could not be
 * confirmed dead within the bounded teardown window (destroy, wait, force, final wait), so
 * {@link QualificationRunner#run()} and {@link QualificationRunner#calibrate()} fail fatally
 * instead of reporting success or a skip while owned work may still be alive. The runner
 * retains the outstanding {@link Process} and drain handles as owned fields, and
 * {@link QualificationRunner#close()} retries force-destroy/interrupt with a bounded
 * confirmation, throwing rather than returning success while any owned work is still alive.
 */
final class UnkillableChildException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String entryId;

    UnkillableChildException(String entryId) {
        super("child process for " + entryId + " could not be confirmed dead within the "
                + "bounded teardown window; the runner retains ownership and retries on close");
        this.entryId = Objects.requireNonNull(entryId, "entryId");
    }

    /** The entry whose child could not be confirmed terminal. */
    String entryId() {
        return entryId;
    }
}
