package dev.gdx.markup.qualification;

import java.util.Objects;

/**
 * Internal typed diagnostic for the runner's total-work contract: the run's one monotonic
 * deadline elapsed or its aggregate work budget was exhausted before an entry's work could
 * complete. Package-private control flow: the runner maps it to a bounded per-entry skipped
 * verdict ({@link QualificationReport.Verdict#SKIPPED_REFERENCE} when the reference step was
 * in flight, {@link QualificationReport.Verdict#SKIPPED_RENDER} otherwise) and stops starting
 * later work, while calibration lets it fail loudly instead of committing a partial manifest.
 */
final class DeadlineExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Which bound was exhausted. */
    enum Kind {
        /** The run's monotonic deadline elapsed. */
        TIME,
        /** The aggregate work budget (entries, decoded pixels, or scores) was spent. */
        WORK,
    }

    /** Which per-entry step was in flight when the bound was exhausted. */
    enum Step {
        REFERENCE,
        RENDER,
        SCORE,
    }

    private final Kind kind;
    private final Step step;

    DeadlineExceededException(Kind kind, Step step, String entryId, String detail) {
        super("qualification budget exhausted for " + entryId + ": " + detail);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.step = Objects.requireNonNull(step, "step");
    }

    /** The exhausted bound. */
    Kind kind() {
        return kind;
    }

    /** The per-entry step that was in flight. */
    Step step() {
        return step;
    }
}
