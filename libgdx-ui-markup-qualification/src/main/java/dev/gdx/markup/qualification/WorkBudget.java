package dev.gdx.markup.qualification;

import dev.gdx.markup.qualification.DeadlineExceededException.Kind;
import dev.gdx.markup.qualification.DeadlineExceededException.Step;

/**
 * Aggregate work budget for one qualification run: explicit caps on the number of entries
 * started, the cumulative decoded pixels (references and recreations at the bounded analysis
 * resolution), and the cumulative scored comparisons. Every spend is overflow-safe and fails
 * as a typed {@link DeadlineExceededException} with {@link Kind#WORK} the moment a cap would
 * be exceeded, so a hostile or accidentally huge corpus cannot start unbounded native work,
 * decode unbounded pixels, or score unbounded comparisons.
 */
final class WorkBudget {
    private final int maxEntries;
    private final long maxDecodedPixels;
    private final int maxScores;
    private int entries;
    private long decodedPixels;
    private int scores;

    WorkBudget(int maxEntries, long maxDecodedPixels, int maxScores) {
        if (maxEntries <= 0 || maxDecodedPixels <= 0 || maxScores <= 0) {
            throw new IllegalArgumentException("work budget caps must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxDecodedPixels = maxDecodedPixels;
        this.maxScores = maxScores;
    }

    /** Copies the caps so a runner template can seed one fresh budget per run. */
    WorkBudget(WorkBudget template) {
        this(template.maxEntries, template.maxDecodedPixels, template.maxScores);
    }

    /** Whether any cap is already reached; the run stops starting new work when true. */
    boolean exhausted() {
        return entries >= maxEntries || decodedPixels >= maxDecodedPixels
                || scores >= maxScores;
    }

    /** Whether another entry may still be started under the entries cap. */
    boolean canAffordEntry() {
        return entries < maxEntries;
    }

    /** Reserves one entry; fails typed when the entry cap is already spent. */
    void spendEntry(String entryId) {
        if (entries >= maxEntries) {
            entries = maxEntries;
            throw new DeadlineExceededException(Kind.WORK, Step.RENDER, entryId,
                    "entry work budget exhausted");
        }
        entries++;
    }

    /** Counts one scored comparison; fails typed when the scoring cap is already spent. */
    void spendScore(String entryId) {
        if (scores >= maxScores) {
            scores = maxScores;
            throw new DeadlineExceededException(Kind.WORK, Step.SCORE, entryId,
                    "scoring budget exhausted");
        }
        scores++;
    }

    /**
     * Counts decoded pixels against the aggregate cap without overflow; fails typed the moment
     * the cap would be exceeded. A failed spend leaves the cap saturated so the run stops
     * starting later work on the next exhausted check.
     */
    void spendDecodedPixels(long pixels, String entryId, Step step) {
        if (pixels > maxDecodedPixels - decodedPixels) {
            decodedPixels = maxDecodedPixels;
            throw new DeadlineExceededException(Kind.WORK, step, entryId,
                    "decoded-pixel budget exhausted");
        }
        decodedPixels += pixels;
    }
}
