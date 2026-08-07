package dev.gdx.markup.qualification;

import java.util.Objects;

/**
 * One reference game UI and its markup recreation, from the corpus manifest. Exactly one of
 * {@code sourceUrl} (fetched at test time) or {@code referenceFile} (committed to the corpus,
 * fully owned) must be present.
 */
public record CorpusEntry(
        String id,
        String sourceUrl,
        String referenceFile,
        String license,
        String markupFile,
        double threshold,
        int referenceWidth,
        int referenceHeight) {

    /** Validates the bounded immutable shape. */
    public CorpusEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(license, "license");
        Objects.requireNonNull(markupFile, "markupFile");
        if ((sourceUrl == null) == (referenceFile == null)) {
            throw new IllegalArgumentException(
                    "exactly one of sourceUrl or referenceFile must be present");
        }
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold must be between 0 and 1");
        }
        if (referenceWidth < 1 || referenceHeight < 1) {
            throw new IllegalArgumentException("reference dimensions must be positive");
        }
    }
}
