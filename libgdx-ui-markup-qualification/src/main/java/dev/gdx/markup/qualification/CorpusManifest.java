package dev.gdx.markup.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Loads the bounded corpus manifest ({@code manifest.json}) describing reference UIs. */
public final class CorpusManifest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<CorpusEntry> entries;

    private CorpusManifest(List<CorpusEntry> entries) {
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
                        node.path("sourceUrl").asText(),
                        node.path("license").asText(),
                        node.path("markupFile").asText(),
                        node.path("threshold").asDouble(),
                        node.path("referenceWidth").asInt(),
                        node.path("referenceHeight").asInt()));
            }
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("corpus manifest declares no entries");
            }
            return new CorpusManifest(parsed);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read corpus manifest " + manifestFile, failure);
        }
    }

    /** Returns the corpus entries in declaration order. */
    public List<CorpusEntry> entries() {
        return entries;
    }
}
