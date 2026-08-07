package dev.gdx.markup.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Loads and rewrites the bounded corpus manifest ({@code manifest.json}). */
public final class CorpusManifest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String comment;
    private final List<CorpusEntry> entries;

    private CorpusManifest(String comment, List<CorpusEntry> entries) {
        this.comment = comment;
        this.entries = List.copyOf(entries);
    }

    /** Parses one manifest file; the corpus must contain at least one entry. */
    public static CorpusManifest load(Path manifestFile) {
        try {
            JsonNode root = JSON.readTree(manifestFile.toFile());
            List<CorpusEntry> parsed = new ArrayList<>();
            for (JsonNode node : root.path("entries")) {
                parsed.add(new CorpusEntry(
                        node.path("id").asText(),
                        nullable(node, "sourceUrl"),
                        nullable(node, "referenceFile"),
                        node.path("license").asText(),
                        node.path("markupFile").asText(),
                        node.path("threshold").asDouble(),
                        node.path("referenceWidth").asInt(),
                        node.path("referenceHeight").asInt()));
            }
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("corpus manifest declares no entries");
            }
            return new CorpusManifest(root.path("comment").asText(null), parsed);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read corpus manifest " + manifestFile, failure);
        }
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
            throw new UncheckedIOException("cannot write corpus manifest " + manifestFile,
                    failure);
        }
    }

    /** Returns the corpus entries in declaration order. */
    public List<CorpusEntry> entries() {
        return entries;
    }

    private static String nullable(JsonNode node, String field) {
        return node.path(field).isNull() ? null : node.path(field).asText(null);
    }
}
