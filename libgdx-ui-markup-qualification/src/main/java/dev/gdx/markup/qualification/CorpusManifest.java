package dev.gdx.markup.qualification;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Loads and rewrites the bounded corpus manifest ({@code manifest.json}).
 *
 * <p>Loading is strict: the file bytes, entry count, per-string lengths, and aggregate string
 * work are capped, every field must belong to the bounded schema with the expected JSON type,
 * and entries are validated by {@link CorpusEntry} before any file is touched.
 */
public final class CorpusManifest {
    /** Maximum manifest file size in bytes, including surrounding whitespace. */
    public static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    /** Maximum number of corpus entries. */
    public static final int MAX_ENTRIES = 64;
    /** Maximum length of any single string field. */
    public static final int MAX_STRING_LENGTH = 4096;
    /** Maximum length of an entry id. */
    public static final int MAX_ID_LENGTH = 64;
    /** Maximum aggregate string work across all entries and the optional comment. */
    public static final long MAX_AGGREGATE_WORK = 64 * 1024;
    /** Maximum reference width or height in pixels; bounds decode and manifest declarations. */
    public static final int MAX_REFERENCE_DIMENSION = 16384;
    /** Maximum reference pixel count (width x height); bounds decode and manifest declarations. */
    public static final long MAX_REFERENCE_PIXELS = 16384L * 16384;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> MANIFEST_FIELDS = Set.of("comment", "entries");
    private static final Set<String> ENTRY_FIELDS = Set.of(
            "id", "sourceUrl", "referenceFile", "license", "markupFile",
            "threshold", "referenceWidth", "referenceHeight",
            "sha256", "bytes", "mediaType");

    private final String comment;
    private final List<CorpusEntry> entries;

    private CorpusManifest(String comment, List<CorpusEntry> entries) {
        this.comment = comment;
        this.entries = List.copyOf(entries);
    }

    /** Parses one manifest file; the corpus must contain at least one entry. */
    public static CorpusManifest load(Path manifestFile) {
        try (InputStream in = Files.newInputStream(manifestFile)) {
            return load(in, manifestFile);
        } catch (IOException failure) {
            throw new ManifestException(ManifestException.Kind.IO,
                    "cannot read corpus manifest " + manifestFile, failure);
        }
    }

    /**
     * Parses a manifest from a stream through the same bounded read as {@link #load(Path)};
     * package-private so tests can simulate input that grows past the byte cap mid-read.
     */
    static CorpusManifest load(InputStream in, Path manifestFile) {
        byte[] bytes = readBounded(in, manifestFile);
        JsonNode root;
        try {
            root = JSON.readValue(bytes, JsonNode.class);
        } catch (IOException failure) {
            throw new ManifestException(ManifestException.Kind.INVALID_JSON,
                    "cannot parse corpus manifest " + manifestFile, failure);
        }
        if (!root.isObject()) {
            throw new ManifestException(ManifestException.Kind.WRONG_TYPE,
                    "corpus manifest root must be a JSON object");
        }
        rejectUnknownFields(root, MANIFEST_FIELDS, "manifest");
        String comment = root.hasNonNull("comment")
                ? textField(root, "comment", "manifest")
                : null;
        if (comment != null && comment.length() > MAX_STRING_LENGTH) {
            throw new ManifestException(ManifestException.Kind.STRING_TOO_LONG,
                    "comment exceeds " + MAX_STRING_LENGTH + " characters");
        }
        JsonNode entriesNode = root.get("entries");
        if (entriesNode == null) {
            throw new ManifestException(ManifestException.Kind.MISSING_FIELD,
                    "manifest is missing required field 'entries'");
        }
        if (!entriesNode.isArray()) {
            throw new ManifestException(ManifestException.Kind.WRONG_TYPE,
                    "manifest field 'entries' must be an array");
        }
        if (entriesNode.size() > MAX_ENTRIES) {
            throw new ManifestException(ManifestException.Kind.TOO_MANY_ENTRIES,
                    "manifest declares " + entriesNode.size() + " entries, cap is " + MAX_ENTRIES);
        }
        if (entriesNode.isEmpty()) {
            throw new ManifestException(ManifestException.Kind.MISSING_FIELD,
                    "corpus manifest declares no entries");
        }
        List<CorpusEntry> parsed = new ArrayList<>(entriesNode.size());
        long aggregateWork = stringLength(comment);
        for (JsonNode node : entriesNode) {
            if (!node.isObject()) {
                throw new ManifestException(ManifestException.Kind.WRONG_TYPE,
                        "each manifest entry must be a JSON object");
            }
            rejectUnknownFields(node, ENTRY_FIELDS, "entry");
            String id = textField(node, "id", "entry");
            String sourceUrl = optionalTextField(node, "sourceUrl");
            String referenceFile = optionalTextField(node, "referenceFile");
            String license = textField(node, "license", "entry");
            String markupFile = textField(node, "markupFile", "entry");
            double threshold = doubleField(node, "threshold");
            int referenceWidth = intField(node, "referenceWidth");
            int referenceHeight = intField(node, "referenceHeight");
            String sha256 = optionalTextField(node, "sha256");
            String mediaType = optionalTextField(node, "mediaType");
            Long byteCount = optionalLongField(node, "bytes");
            if (sourceUrl != null) {
                CorpusEntry.validateSourceUrl(sourceUrl);
            } else if (sha256 != null || mediaType != null || byteCount != null) {
                throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                        "local entry must not declare remote identity fields "
                                + "(sha256, bytes, mediaType)");
            }
            aggregateWork += id.length() + stringLength(sourceUrl) + stringLength(referenceFile)
                    + license.length() + markupFile.length() + stringLength(sha256)
                    + stringLength(mediaType);
            if (aggregateWork > MAX_AGGREGATE_WORK) {
                throw new ManifestException(ManifestException.Kind.WORK_LIMIT,
                        "aggregate string work across entries exceeds " + MAX_AGGREGATE_WORK);
            }
            parsed.add(new CorpusEntry(id, sourceUrl, referenceFile, license, markupFile,
                    threshold, referenceWidth, referenceHeight, sha256,
                    byteCount == null ? 0 : byteCount, mediaType));
        }
        return new CorpusManifest(comment, parsed);
    }

    /** Rewrites the manifest with the supplied entries, preserving the corpus comment. */
    public void write(Path manifestFile, List<CorpusEntry> updatedEntries) {
        try {
            ObjectNode root = JSON.createObjectNode();
            if (comment != null) {
                root.put("comment", comment);
            }
            ArrayNode array = root.putArray("entries");
            for (CorpusEntry entry : updatedEntries) {
                ObjectNode node = array.addObject();
                node.put("id", entry.id());
                if (entry.sourceUrl() != null) {
                    node.put("sourceUrl", entry.sourceUrl());
                    node.put("sha256", entry.sha256());
                    node.put("bytes", entry.bytes());
                    node.put("mediaType", entry.mediaType());
                } else {
                    node.put("referenceFile", entry.referenceFile());
                }
                node.put("license", entry.license());
                node.put("markupFile", entry.markupFile());
                node.put("threshold", Math.round(entry.threshold() * 1000) / 1000.0);
                node.put("referenceWidth", entry.referenceWidth());
                node.put("referenceHeight", entry.referenceHeight());
            }
            Files.writeString(manifestFile, JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root));
        } catch (IOException failure) {
            throw new ManifestException(ManifestException.Kind.IO,
                    "cannot write corpus manifest " + manifestFile, failure);
        }
    }

    /** Returns the corpus entries in declaration order. */
    public List<CorpusEntry> entries() {
        return entries;
    }

    /**
     * Reads at most {@code MAX_MANIFEST_BYTES + 1} bytes with overflow-safe growth and rejects
     * at limit + 1, before any larger buffer, string, or parse tree is materialized.
     */
    private static byte[] readBounded(InputStream in, Path manifestFile) {
        try {
            int capacity = Math.min(MAX_MANIFEST_BYTES + 1, 8192);
            byte[] bytes = new byte[capacity];
            int total = 0;
            while (true) {
                if (total == bytes.length) {
                    if (total == MAX_MANIFEST_BYTES + 1) {
                        throw new ManifestException(ManifestException.Kind.TOO_LARGE,
                                "corpus manifest exceeds " + MAX_MANIFEST_BYTES
                                        + " bytes: " + manifestFile);
                    }
                    bytes = Arrays.copyOf(bytes,
                            Math.min(MAX_MANIFEST_BYTES + 1, bytes.length * 2));
                }
                int read = in.read(bytes, total, bytes.length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            return Arrays.copyOf(bytes, total);
        } catch (IOException failure) {
            throw new ManifestException(ManifestException.Kind.IO,
                    "cannot read corpus manifest " + manifestFile, failure);
        }
    }

    private static void rejectUnknownFields(JsonNode node, Set<String> allowed, String scope) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new ManifestException(ManifestException.Kind.UNKNOWN_FIELD,
                        scope + " declares unknown field '" + name + "'");
            }
        }
    }

    private static String textField(JsonNode node, String field, String scope) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new ManifestException(ManifestException.Kind.MISSING_FIELD,
                    scope + " is missing required field '" + field + "'");
        }
        if (!value.isTextual()) {
            throw new ManifestException(ManifestException.Kind.WRONG_TYPE,
                    scope + " field '" + field + "' must be a string");
        }
        return value.textValue();
    }

    private static String optionalTextField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ManifestException(ManifestException.Kind.WRONG_TYPE,
                    "entry field '" + field + "' must be a string");
        }
        return value.textValue();
    }

    private static double doubleField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new ManifestException(ManifestException.Kind.MISSING_FIELD,
                    "entry is missing required field '" + field + "'");
        }
        if (!value.isNumber()) {
            throw new ManifestException(ManifestException.Kind.WRONG_TYPE,
                    "entry field '" + field + "' must be a number");
        }
        return value.doubleValue();
    }

    private static int intField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new ManifestException(ManifestException.Kind.MISSING_FIELD,
                    "entry is missing required field '" + field + "'");
        }
        if (!value.isIntegralNumber()) {
            throw new ManifestException(ManifestException.Kind.WRONG_TYPE,
                    "entry field '" + field + "' must be an integer");
        }
        // canConvertToInt uses arbitrary precision (BigInteger/BigDecimal), so values that
        // would silently truncate on narrowing are rejected before any lossy conversion.
        if (!value.canConvertToInt()) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "entry field '" + field + "' must fit an int");
        }
        return value.intValue();
    }

    private static int stringLength(String value) {
        return value == null ? 0 : value.length();
    }

    /**
     * Reads an optional integral JSON field; {@code null} when absent or explicit JSON null.
     * Arbitrary-precision values that would truncate on narrowing to long are rejected.
     */
    private static Long optionalLongField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new ManifestException(ManifestException.Kind.WRONG_TYPE,
                    "entry field '" + field + "' must be an integer");
        }
        if (!value.canConvertToLong()) {
            throw new ManifestException(ManifestException.Kind.INVALID_VALUE,
                    "entry field '" + field + "' must fit a long");
        }
        return value.longValue();
    }
}
