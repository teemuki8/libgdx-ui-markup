package dev.gdx.markup.qualification;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One reference game UI and its markup recreation, from the corpus manifest. Exactly one of
 * {@code sourceUrl} (fetched at test time) or {@code referenceFile} (committed to the corpus,
 * fully owned) must be present.
 *
 * <p>All string fields are length-capped, the id is a lowercase slug, and path fields are
 * normalized relative paths so corpus reads can never escape the corpus root.
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

    /** Slug shape: lowercase ASCII letters and digits, hyphen-separated words. */
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    /** Validates the bounded immutable shape. */
    public CorpusEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(license, "license");
        Objects.requireNonNull(markupFile, "markupFile");
        if ((sourceUrl == null) == (referenceFile == null)) {
            throw new ManifestException(ManifestException.Kind.MISSING_FIELD,
                    "exactly one of sourceUrl or referenceFile must be present");
        }
        validateString("id", id, CorpusManifest.MAX_ID_LENGTH);
        if (!SLUG.matcher(id).matches()) {
            throw new ManifestException(ManifestException.Kind.INVALID_ID,
                    "id must be a lowercase slug of letters, digits, and hyphens: " + id);
        }
        validateString("license", license, CorpusManifest.MAX_STRING_LENGTH);
        validateString("markupFile", markupFile, CorpusManifest.MAX_STRING_LENGTH);
        validatePath("markupFile", markupFile);
        if (sourceUrl != null) {
            validateString("sourceUrl", sourceUrl, CorpusManifest.MAX_STRING_LENGTH);
        }
        if (referenceFile != null) {
            validateString("referenceFile", referenceFile, CorpusManifest.MAX_STRING_LENGTH);
            validatePath("referenceFile", referenceFile);
        }
        if (threshold < 0 || threshold > 1) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "threshold must be between 0 and 1");
        }
        if (referenceWidth < 1 || referenceHeight < 1) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "reference dimensions must be positive");
        }
    }

    private static void validateString(String field, String value, int maxLength) {
        if (value.length() > maxLength) {
            throw new ManifestException(ManifestException.Kind.STRING_TOO_LONG,
                    field + " exceeds " + maxLength + " characters");
        }
    }

    private static void validatePath(String field, String value) {
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException failure) {
            throw new ManifestException(ManifestException.Kind.INVALID_PATH,
                    field + " is not a valid path: " + value, failure);
        }
        if (path.isAbsolute()) {
            throw new ManifestException(ManifestException.Kind.ABSOLUTE_PATH,
                    field + " must be relative to the corpus root: " + value);
        }
        if (value.isEmpty() || value.indexOf('\\') >= 0) {
            throw new ManifestException(ManifestException.Kind.INVALID_PATH,
                    field + " must be non-empty and use '/' separators: " + value);
        }
        for (Path element : path) {
            String name = element.toString();
            if (name.equals("..") || name.equals(".")) {
                throw new ManifestException(ManifestException.Kind.INVALID_PATH,
                        field + " must not contain '.' or '..' segments: " + value);
            }
        }
        if (!value.equals(path.normalize().toString())) {
            throw new ManifestException(ManifestException.Kind.INVALID_PATH,
                    field + " must be a normalized relative path: " + value);
        }
    }
}
