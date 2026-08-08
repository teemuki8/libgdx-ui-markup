package dev.gdx.markup.qualification;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One reference game UI and its markup recreation, from the corpus manifest. Exactly one of
 * {@code sourceUrl} (fetched at test time) or {@code referenceFile} (committed to the corpus,
 * fully owned) must be present.
 *
 * <p>Remote entries additionally declare their exact identity: the lowercase 64-hex SHA-256 of
 * the reference bytes, the exact positive byte length, and the allowlisted image media type.
 * The {@link ReferenceImageStore} refuses any download or cache hit that does not match all
 * four declared attributes (digest, length, media type, dimensions).
 *
 * <p>All string fields are length-capped, the id is a lowercase slug, and path fields are
 * normalized relative paths so corpus reads can never escape the corpus root. URL shape (https,
 * host, no user info or fragment) is enforced at the manifest boundary by
 * {@link #validateSourceUrl(String)} and again by the store for every fetch and redirect
 * target.
 */
public record CorpusEntry(
        String id,
        String sourceUrl,
        String referenceFile,
        String license,
        String markupFile,
        FidelityThresholds thresholds,
        int referenceWidth,
        int referenceHeight,
        String sha256,
        long bytes,
        String mediaType) {

    /** Slug shape: lowercase ASCII letters and digits, hyphen-separated words. */
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    /** Lowercase 64-hex SHA-256 shape. */
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

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
            validateRemoteIdentity(sha256, bytes, mediaType);
        } else {
            validateString("referenceFile", referenceFile, CorpusManifest.MAX_STRING_LENGTH);
            validatePath("referenceFile", referenceFile);
            if (sha256 != null || mediaType != null || bytes != 0) {
                throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                        "local entry must not declare remote identity fields "
                                + "(sha256, bytes, mediaType)");
            }
        }
        if (thresholds == null) {
            throw new ManifestException(ManifestException.Kind.MISSING_FIELD,
                    "entry is missing required field 'thresholds'");
        }
        if (referenceWidth < 1 || referenceHeight < 1) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "reference dimensions must be positive");
        }
        if (referenceWidth > CorpusManifest.MAX_REFERENCE_DIMENSION
                || referenceHeight > CorpusManifest.MAX_REFERENCE_DIMENSION) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "reference dimensions exceed the "
                            + CorpusManifest.MAX_REFERENCE_DIMENSION + " pixel cap");
        }
        if ((long) referenceWidth * referenceHeight > CorpusManifest.MAX_REFERENCE_PIXELS) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "reference pixel count exceeds the "
                            + CorpusManifest.MAX_REFERENCE_PIXELS + " pixel cap");
        }
    }

    private static void validateRemoteIdentity(String sha256, long bytes, String mediaType) {
        if (sha256 == null) {
            throw new ManifestException(ManifestException.Kind.MISSING_FIELD,
                    "remote entry is missing required field 'sha256'");
        }
        if (!SHA256.matcher(sha256).matches()) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "sha256 must be 64 lowercase hexadecimal characters");
        }
        if (bytes < 1) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "remote entry bytes must be a positive byte count");
        }
        if (bytes > ReferenceImageStore.MAX_BYTES) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "remote entry bytes exceeds the " + ReferenceImageStore.MAX_BYTES
                            + " byte reference cap");
        }
        if (mediaType == null) {
            throw new ManifestException(ManifestException.Kind.MISSING_FIELD,
                    "remote entry is missing required field 'mediaType'");
        }
        if (!ReferenceImageStore.ALLOWED_MEDIA_TYPES.contains(mediaType)) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "mediaType '" + mediaType + "' is not in the allowlist "
                            + ReferenceImageStore.ALLOWED_MEDIA_TYPES);
        }
    }

    /**
     * Rejects ambiguous or non-HTTPS URL shapes at the manifest boundary. The store re-applies
     * the same rules (plus host allowlist and resolved-address policy) to the initial URL and
     * to every redirect target.
     */
    static void validateSourceUrl(String sourceUrl) {
        URI uri;
        try {
            uri = URI.create(sourceUrl);
        } catch (IllegalArgumentException failure) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "sourceUrl is not a valid URI: " + sourceUrl, failure);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "sourceUrl must use the https scheme: " + sourceUrl);
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "sourceUrl must declare a host: " + sourceUrl);
        }
        if (uri.getUserInfo() != null) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "sourceUrl must not contain user info: " + sourceUrl);
        }
        if (uri.getFragment() != null) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "sourceUrl must not contain a fragment: " + sourceUrl);
        }
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "sourceUrl must use the default https port 443: " + sourceUrl);
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
