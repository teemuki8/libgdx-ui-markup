package dev.gdx.markup.qualification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Bounded per-entry qualification outcome and its JSON report.
 *
 * <p>Schema version 2 serializes every multi-signal score ({@code coarseLayout}, {@code
 * geometry}, {@code color}, {@code detail}), every committed threshold, the cell counts, the
 * verdict, and the exact failed dimensions, so a failing gate can name which visual dimension
 * regressed without any re-derivation.
 */
public final class QualificationReport {
    /** Report schema version; bumped when the serialized shape changes. */
    public static final int SCHEMA_VERSION = 2;

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

    /** One corpus entry outcome: score, thresholds, verdict, failed dimensions, staleness. */
    public record EntryResult(
            String id,
            String license,
            FidelityScore score,
            FidelityThresholds thresholds,
            Verdict verdict,
            List<FidelityComponent> failedDimensions,
            boolean stale) {

        /** Defensively copies the failed-dimension list so results stay immutable. */
        public EntryResult {
            failedDimensions = List.copyOf(failedDimensions);
        }

        /** Returns whether every required component met its threshold. */
        public boolean passed() {
            return verdict == Verdict.PASS && failedDimensions.isEmpty();
        }
    }

    private final List<EntryResult> results;

    /** Wraps the bounded result list, preserving corpus order. */
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

    /** Writes the bounded schema-v2 JSON report (one object node per entry). */
    public void writeJson(Path reportFile) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("schemaVersion", SCHEMA_VERSION);
            ArrayNode entries = root.putArray("entries");
            for (EntryResult result : results) {
                ObjectNode node = entries.addObject();
                node.put("id", result.id());
                node.put("verdict", result.verdict().name());
                node.put("stale", result.stale());
                FidelityScore score = result.score();
                node.put("coarseLayout", round6(score.coarseLayout()));
                node.put("geometry", round6(score.geometry()));
                node.put("color", round6(score.color()));
                node.put("detail", round6(score.detail()));
                node.put("referenceCells", score.referenceCells());
                node.put("recreationCells", score.recreationCells());
                ObjectNode thresholds = node.putObject("thresholds");
                thresholds.put("geometry", round6(result.thresholds().geometry()));
                thresholds.put("color", round6(result.thresholds().color()));
                thresholds.put("detail", round6(result.thresholds().detail()));
                result.thresholds().coarseBaseline().ifPresent(
                        coarse -> thresholds.put("coarseLayout", round6(coarse)));
                ArrayNode failed = node.putArray("failedDimensions");
                for (FidelityComponent component : result.failedDimensions()) {
                    failed.add(component.name());
                }
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
                    .append(result.verdict().name());
            if (result.verdict() == Verdict.PASS || result.verdict() == Verdict.FAIL) {
                FidelityScore score = result.score();
                out.append(" geometry=").append(compact(score.geometry()))
                        .append(" color=").append(compact(score.color()))
                        .append(" detail=").append(compact(score.detail()))
                        .append(" thresholds=")
                        .append(compact(result.thresholds().geometry())).append('/')
                        .append(compact(result.thresholds().color())).append('/')
                        .append(compact(result.thresholds().detail()))
                        .append(" cells=").append(score.referenceCells()).append('/')
                        .append(score.recreationCells());
                if (!result.failedDimensions().isEmpty()) {
                    out.append(" failed=").append(result.failedDimensions());
                }
            }
            out.append('\n');
        }
        out.append("qualification: ").append(scored()).append('/')
                .append(results.size()).append(" scored, ")
                .append(results.stream().filter(r -> r.verdict() == Verdict.FAIL).count())
                .append(" failed\n");
        return out.toString();
    }

    /** Rounds to six decimals so repeated writes are byte-identical. */
    private static double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private static String compact(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
