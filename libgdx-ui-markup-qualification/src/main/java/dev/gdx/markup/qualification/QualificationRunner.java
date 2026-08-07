package dev.gdx.markup.qualification;

import dev.gdx.markup.qualification.QualificationReport.EntryResult;
import dev.gdx.markup.qualification.QualificationReport.Verdict;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Renders each corpus recreation with the real preview binary and measures its structural
 * region overlap against the fetched reference game UI.
 */
public final class QualificationRunner implements AutoCloseable {
    private static final int RENDER_FRAMES = 10;
    private static final long RENDER_TIMEOUT_SECONDS = 60;

    private final Path corpusDir;
    private final ReferenceImageStore store;
    private final Path previewDistribution;
    private final Path outputDir;

    /** Creates a runner over the corpus, image cache, preview distribution, and output dir. */
    public QualificationRunner(Path corpusDir, Path cacheDir, Path previewDistribution,
            Path outputDir) {
        this.corpusDir = corpusDir;
        this.store = new ReferenceImageStore(cacheDir);
        this.previewDistribution = previewDistribution;
        this.outputDir = outputDir;
        try {
            Files.createDirectories(outputDir);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException("cannot create output dir " + outputDir,
                    failure);
        }
    }

    /** Runs every corpus entry and writes the bounded JSON report next to the renderings. */
    public QualificationReport run() {
        CorpusManifest manifest = CorpusManifest.load(corpusDir.resolve("manifest.json"));
        List<EntryResult> results = new ArrayList<>();
        for (CorpusEntry entry : manifest.entries()) {
            results.add(qualify(entry));
        }
        QualificationReport report = new QualificationReport(results);
        report.writeJson(outputDir.resolve("report.json"));
        System.out.println(report.summary());
        return report;
    }

    private EntryResult qualify(CorpusEntry entry) {
        Optional<RegionSimilarity.Regions> regions = measure(entry);
        if (regions.isEmpty()) {
            Optional<Path> reference = store.reference(entry);
            Verdict skipped = reference.isPresent() ? Verdict.SKIPPED_RENDER
                    : Verdict.SKIPPED_REFERENCE;
            return new EntryResult(entry.id(), entry.license(), entry.threshold(), 0, 0, 0,
                    skipped);
        }
        RegionSimilarity.Regions measured = regions.orElseThrow();
        Verdict verdict = measured.dice() >= entry.threshold() ? Verdict.PASS : Verdict.FAIL;
        return new EntryResult(entry.id(), entry.license(), entry.threshold(), measured.dice(),
                measured.referenceCells(), measured.recreationCells(), verdict);
    }

    /**
     * Measures every entry and rewrites the manifest thresholds to the calibrated fraction of
     * each measured score (skipped entries keep their committed threshold). Run when the corpus
     * or a recreation changes; the qualification test then gates on the refreshed baselines.
     */
    public void calibrate() {
        CorpusManifest manifest = CorpusManifest.load(corpusDir.resolve("manifest.json"));
        List<CorpusEntry> updated = new ArrayList<>();
        for (CorpusEntry entry : manifest.entries()) {
            Optional<RegionSimilarity.Regions> regions = measure(entry);
            if (regions.isEmpty()) {
                updated.add(entry);
                System.out.println("calibration: " + entry.id()
                        + " skipped, threshold kept " + compact(entry.threshold()));
                continue;
            }
            double threshold = QualificationPolicy.threshold(regions.orElseThrow().dice());
            updated.add(new CorpusEntry(entry.id(), entry.sourceUrl(), entry.license(),
                    entry.markupFile(), threshold, entry.referenceWidth(),
                    entry.referenceHeight()));
            System.out.println("calibration: " + entry.id() + " dice="
                    + compact(regions.orElseThrow().dice()) + " threshold "
                    + compact(entry.threshold()) + " -> " + compact(threshold));
        }
        manifest.write(corpusDir.resolve("manifest.json"), updated);
    }

    /**
     * Fetches the reference and renders the recreation, returning the measured regions; empty
     * when the reference is unavailable or the preview process failed.
     */
    private Optional<RegionSimilarity.Regions> measure(CorpusEntry entry) {
        Optional<Path> reference = store.reference(entry);
        if (reference.isEmpty()) {
            return Optional.empty();
        }
        Optional<Path> recreation = render(entry);
        if (recreation.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(RegionSimilarity.measure(reference.get(), recreation.get()));
        } catch (IOException failure) {
            return Optional.empty();
        }
    }

    private static String compact(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /** Renders one recreation through the preview binary; empty when the process fails. */
    private Optional<Path> render(CorpusEntry entry) {
        Path xml = corpusDir.resolve(entry.markupFile());
        Path css = corpusDir.resolve("shared.css");
        Path screenshot = outputDir.resolve(entry.id() + ".png");
        Path lib = previewDistribution.resolve("lib");
        if (!Files.isDirectory(lib)) {
            return Optional.empty();
        }
        try {
            String classpath = lib.toString() + File.separatorChar + "*";
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder builder = new ProcessBuilder(
                    java,
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp",
                    classpath,
                    "dev.gdx.markup.preview.PreviewApp",
                    "--ui", xml.toString(),
                    "--css", css.toString(),
                    "--frames", Integer.toString(RENDER_FRAMES),
                    "--screenshot", screenshot.toString(),
                    "--exit");
            Process process = builder.start();
            drain(process.getInputStream());
            drain(process.getErrorStream());
            boolean finished = process.waitFor(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(screenshot)) {
                return Optional.empty();
            }
            return Optional.of(screenshot);
        } catch (IOException | InterruptedException failure) {
            return Optional.empty();
        }
    }

    /** Discards process output on a virtual thread so the stdio pipes never fill. */
    private static void drain(InputStream stream) {
        Thread.ofVirtual().name("qualification-drain").start(() -> {
            byte[] buffer = new byte[8192];
            try {
                while (stream.read(buffer) >= 0) {
                    // bounded discard
                }
            } catch (IOException ignored) {
                // process ended
            }
        });
    }

    @Override public void close() {
        store.close();
    }
}
