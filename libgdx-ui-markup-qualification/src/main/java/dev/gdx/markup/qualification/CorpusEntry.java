package dev.gdx.markup.qualification;

import java.util.Objects;

/** One reference game UI and its markup recreation, from the corpus manifest. */
public record CorpusEntry(
        String id,
        String sourceUrl,
        String license,
        String markupFile,
        double threshold,
        int referenceWidth,
        int referenceHeight) {

    /** Validates the bounded immutable shape. */
    public CorpusEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceUrl, "sourceUrl");
        Objects.requireNonNull(license, "license");
        Objects.requireNonNull(markupFile, "markupFile");
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
