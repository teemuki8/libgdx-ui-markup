package dev.gdx.markup.qualification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Bounded per-entry qualification outcome and its JSON report. */
public final class QualificationReport {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Stable verdict taxonomy: PASS/FAIL gate CI; skips are environment gaps, not failures. */
    public enum Verdict {
        PASS,
        FAIL,
        /** Reference image could not be fetched or decoded (offline, moved URL). */
        SKIPPED_REFERENCE,
        /** The preview process could not render the recreation. */
        SKIPPED_RENDER,
    }

    /** One corpus entry outcome. */
    public record EntryResult(
            String id,
            String license,
            double threshold,
            double dice,
            int referenceCells,
            int recreationCells,
            Verdict verdict) {
    }

    private final List<EntryResult> results;

    /** Wraps the bounded result list. */
    public QualificationReport(List<EntryResult> results) {
        this.results = List.copyOf(results);
    }

    /** Returns the outcomes in corpus order. */
    public List<EntryResult> results() {
        return results;
    }

    /** Returns how many entries were actually measured (fetched and rendered). */
    public long scored() {
        return results.stream()
                .filter(result -> result.verdict() == Verdict.PASS
                        || result.verdict() == Verdict.FAIL)
                .count();
    }

    /** Writes the bounded JSON report (one array node per entry). */
    public void writeJson(Path reportFile) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("schemaVersion", 1);
            ArrayNode entries = root.putArray("entries");
            for (EntryResult result : results) {
                ObjectNode node = entries.addObject();
                node.put("id", result.id());
                node.put("verdict", result.verdict().name());
                node.put("dice", result.dice());
                node.put("threshold", result.threshold());
                node.put("referenceCells", result.referenceCells());
                node.put("recreationCells", result.recreationCells());
                node.put("license", result.license());
            }
            Files.createDirectories(reportFile.getParent());
            Files.writeString(reportFile, JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root));
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot write qualification report " + reportFile,
                    failure);
        }
    }

    /** Bounded human-readable summary, one bounded line per entry. */
    public String summary() {
        StringBuilder out = new StringBuilder();
        for (EntryResult result : results) {
            out.append("qualification: ").append(result.id()).append(' ')
                    .append(result.verdict().name()).append(" dice=")
                    .append(compact(result.dice())).append(" threshold=")
                    .append(compact(result.threshold()));
            if (result.verdict() == Verdict.PASS || result.verdict() == Verdict.FAIL) {
                out.append(" cells=").append(result.referenceCells()).append('/')
                        .append(result.recreationCells());
            }
            out.append('\n');
        }
        out.append("qualification: ").append(scored()).append('/')
                .append(results.size()).append(" scored, ")
                .append(results.stream().filter(r -> r.verdict() == Verdict.FAIL).count())
                .append(" failed\n");
        return out.toString();
    }

    private static String compact(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
