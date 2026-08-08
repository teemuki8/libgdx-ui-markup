package dev.gdx.markup.qualification;

import dev.gdx.markup.qualification.QualificationReport.EntryResult;
import dev.gdx.markup.qualification.QualificationReport.Verdict;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;

/**
 * Renders each corpus recreation with the real preview binary and measures its multi-signal
 * visual fidelity (geometry, color, detail, plus the coarse layout diagnostic) against the
 * fetched reference game UI. Every required component gates independently.
 */
public final class QualificationRunner implements AutoCloseable {
    private static final int RENDER_FRAMES = 10;
    private static final long RENDER_TIMEOUT_SECONDS = 60;

    /** Byte cap for a per-entry palette override; mirrors {@code DefaultSkin.MAX_PALETTE_BYTES}. */
    static final int MAX_PALETTE_BYTES = 8192;

    private final Path corpusDir;
    private final ReferenceImageStore store;
    private final Path previewDistribution;
    private final Path outputDir;

    /** Creates a runner over the corpus, preview distribution, and output dir. */
    public QualificationRunner(Path corpusDir, Path previewDistribution, Path outputDir) {
        this.corpusDir = corpusDir;
        this.store = new ReferenceImageStore();
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
        Optional<ReferenceImageStore.ReferenceImage> reference = reference(entry);
        Optional<Path> recreationPath = render(entry);
        if (reference.isEmpty() || recreationPath.isEmpty()) {
            Verdict skipped = reference.isPresent() ? Verdict.SKIPPED_RENDER
                    : Verdict.SKIPPED_REFERENCE;
            return new EntryResult(entry.id(), entry.license(), FidelityScore.ZERO,
                    entry.thresholds(), skipped, List.of(), false);
        }
        BufferedImage recreation;
        try {
            recreation = BoundedDecode.decode(recreationPath.orElseThrow());
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.DECODE,
                    "cannot decode recreation for " + entry.id(), failure);
        }
        ReferenceImageStore.ReferenceImage referenceImage = reference.orElseThrow();
        FidelityScore score = VisualFidelity.measure(referenceImage, recreation);
        List<List<Double>> negatives = negativeObservations(referenceImage, recreation);
        boolean stale = isStale(score, entry.thresholds(), negatives);
        List<FidelityComponent> failed = failedDimensions(score, entry.thresholds());
        Verdict verdict = failed.isEmpty() ? Verdict.PASS : Verdict.FAIL;
        return new EntryResult(entry.id(), entry.license(), score, entry.thresholds(), verdict,
                failed, stale);
    }

    /**
     * Returns whether any required component's committed threshold has drifted from its
     * current calibration-implied threshold (relative comparison against the entry's own
     * current deliberate negatives).
     */
    private static boolean isStale(FidelityScore score, FidelityThresholds thresholds,
            List<List<Double>> negatives) {
        for (int i = 0; i < FidelityComponent.REQUIRED.size(); i++) {
            FidelityComponent component = FidelityComponent.REQUIRED.get(i);
            if (QualificationPolicy.stale(thresholds.component(component),
                    score.component(component), negatives.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The authoritative failed-dimension list: any required component below its threshold
     * plus the {@link FidelityComponent#STRUCTURE_DENSITY} invariant (recreation structured
     * cells outside the reference's density band). Package-private so tests can prove a
     * report can never PASS an empty or screen-flooding mask.
     */
    static List<FidelityComponent> failedDimensions(FidelityScore score,
            FidelityThresholds thresholds) {
        List<FidelityComponent> failed =
                QualificationPolicy.failedComponents(score, thresholds);
        if (!QualificationPolicy.densityInBand(score.referenceCells(),
                score.recreationCells())) {
            failed = new ArrayList<>(failed);
            failed.add(FidelityComponent.STRUCTURE_DENSITY);
        }
        return failed;
    }

    /**
     * Measures every entry and its deliberate negatives, then rewrites the manifest thresholds
     * so each component sits inside its safe interval: below the measured positive by the
     * documented margin and above the maximum deliberate-negative score. When the ranges
     * overlap for any entry and component the calibration fails loudly instead of committing a
     * meaningless gate. Skipped entries keep their committed thresholds. Strict CI never runs
     * this task; the committed thresholds are the baselines the qualification test gates on.
     */
    public void calibrate() {
        CorpusManifest manifest = CorpusManifest.load(corpusDir.resolve("manifest.json"));
        List<CorpusEntry> updated = new ArrayList<>();
        for (CorpusEntry entry : manifest.entries()) {
            Optional<FidelityScore> positive = measure(entry);
            if (positive.isEmpty()) {
                updated.add(entry);
                System.out.println("calibration: " + entry.id() + " skipped, thresholds kept");
                continue;
            }
            FidelityScore score = positive.orElseThrow();
            BufferedImage recreation = decodedRecreation(entry);
            List<List<Double>> negativeObservations =
                    negativeObservations(reference(entry).orElseThrow(), recreation);
            logNegativeObservations(entry, negativeObservations);
            double geometry = calibrateComponent(entry, FidelityComponent.GEOMETRY,
                    score.geometry(), negativeObservations.get(0));
            double color = calibrateComponent(entry, FidelityComponent.COLOR,
                    score.color(), negativeObservations.get(1));
            double detail = calibrateComponent(entry, FidelityComponent.DETAIL,
                    score.detail(), negativeObservations.get(2));
            double coarse = QualificationPolicy.threshold(score.coarseLayout());
            updated.add(new CorpusEntry(entry.id(), entry.sourceUrl(), entry.referenceFile(),
                    entry.license(), entry.markupFile(),
                    new FidelityThresholds(geometry, color, detail, coarse),
                    entry.referenceWidth(), entry.referenceHeight(),
                    entry.sha256(), entry.bytes(), entry.mediaType()));
            System.out.println("calibration: " + entry.id() + " geometry="
                    + compact(score.geometry()) + "->" + compact(geometry) + " color="
                    + compact(score.color()) + "->" + compact(color) + " detail="
                    + compact(score.detail()) + "->" + compact(detail)
                    + " coarse=" + compact(score.coarseLayout()) + "->" + compact(coarse));
        }
        manifest.write(corpusDir.resolve("manifest.json"), updated);
    }

    /**
     * Applies the deterministic deliberate negatives to the decoded recreation and measures
     * each against the reference, returning the component-relevant observation lists. Each
     * transformation is a negative for the failure mode it exhibits: a vertical flip, a fixed
     * translation, and a uniform scale misplace layout (geometry); a hue rotation re-palettes
     * the screen (color); a box blur and a uniform scale destroy typography and fine structure
     * (detail). The palette-preserving flip/translation/hue scores on other components are not
     * calibration negatives for those components.
     */
    private static List<List<Double>> negativeObservations(
            ReferenceImageStore.ReferenceImage reference, BufferedImage recreationImage) {
        List<Double> geometryNegatives = new ArrayList<>();
        List<Double> colorNegatives = new ArrayList<>();
        List<Double> detailNegatives = new ArrayList<>();
        BufferedImage[] negatives = NegativeTransforms.all(recreationImage);
        String[] names = NegativeTransforms.names();
        for (int i = 0; i < negatives.length; i++) {
            FidelityScore negativeScore = VisualFidelity.measure(reference, negatives[i]);
            String name = names[i];
            if ("flip".equals(name) || "translate".equals(name) || "scale".equals(name)) {
                geometryNegatives.add(negativeScore.geometry());
            }
            if ("hue".equals(name)) {
                colorNegatives.add(negativeScore.color());
            }
            if ("blur".equals(name) || "scale".equals(name)) {
                detailNegatives.add(negativeScore.detail());
            }
        }
        return List.of(geometryNegatives, colorNegatives, detailNegatives);
    }

    /** Renders and decodes one entry's recreation at the bounded analysis resolution. */
    private BufferedImage decodedRecreation(CorpusEntry entry) {
        Optional<Path> recreation = render(entry);
        if (recreation.isEmpty()) {
            throw new IllegalStateException("cannot render " + entry.id() + " for calibration");
        }
        try {
            return BoundedDecode.decode(recreation.orElseThrow());
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.DECODE,
                    "cannot decode recreation for calibration of " + entry.id(), failure);
        }
    }

    /** Prints one entry's per-component negative observations for the calibration log. */
    private static void logNegativeObservations(CorpusEntry entry,
            List<List<Double>> observations) {
        System.out.println("calibration: " + entry.id() + " geometry negatives "
                + observations.get(0) + " color negatives " + observations.get(1)
                + " detail negatives " + observations.get(2));
    }

    private static double calibrateComponent(CorpusEntry entry, FidelityComponent component,
            double positive, List<Double> negatives) {
        OptionalDouble threshold = QualificationPolicy.calibrate(List.of(positive), negatives);
        if (threshold.isEmpty()) {
            throw new ReferenceException(ReferenceException.Kind.CALIBRATION,
                    "no safe threshold interval for " + entry.id() + " " + component
                            + ": positive " + compact(positive)
                            + " does not clear the deliberate negatives " + negatives
                            + "; no threshold can separate the ranges, so improve the "
                            + "recreation or re-examine the corpus");
        }
        return Math.round(threshold.orElseThrow() * 1000) / 1000.0;
    }

    /**
     * Resolves a manifest-relative path inside {@code root}, rejecting lexical escape and
     * symlink escape through real parent paths. Escape attempts fail loudly as typed
     * diagnostics; a missing file inside the root is a normal "absent" outcome for callers.
     */
    static Path resolveInside(Path root, String relative) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path candidate = normalizedRoot.resolve(relative).normalize();
        if (!candidate.startsWith(normalizedRoot)) {
            throw new ManifestException(ManifestException.Kind.OUTSIDE_ROOT,
                    "path escapes " + root + ": " + relative);
        }
        try {
            Path realRoot = realPathOf(normalizedRoot);
            if (!realPathOf(candidate).startsWith(realRoot)) {
                throw new ManifestException(ManifestException.Kind.SYMLINK_ESCAPE,
                        "path resolves outside " + root + " via symlink: " + relative);
            }
        } catch (IOException failure) {
            throw new ManifestException(ManifestException.Kind.IO,
                    "cannot resolve real path under " + root + ": " + relative, failure);
        }
        return candidate;
    }

    /**
     * Resolves the optional per-entry palette file ({@code <id>-palette.json}) inside the
     * corpus root and verifies it is a regular, contained file within the palette byte cap.
     * A missing palette is the normal absent outcome; a palette that escapes the corpus
     * (traversal or symlink) or that exceeds the byte cap fails as a typed
     * {@link ManifestException} instead of being handed to the preview. The cap mirrors
     * {@code DefaultSkin.MAX_PALETTE_BYTES}, which the preview enforces again.
     */
    static Optional<Path> containedPalette(Path corpusDir, String entryId) {
        Path palette = resolveInside(corpusDir, entryId + "-palette.json");
        if (!Files.isRegularFile(palette)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(palette)) {
            if (in.readNBytes(MAX_PALETTE_BYTES + 1).length > MAX_PALETTE_BYTES) {
                throw new ManifestException(ManifestException.Kind.PALETTE_TOO_LARGE,
                        "palette file exceeds the " + MAX_PALETTE_BYTES
                                + " byte cap: " + palette);
            }
        } catch (IOException failure) {
            throw new ManifestException(ManifestException.Kind.IO,
                    "cannot read palette file " + palette, failure);
        }
        return Optional.of(palette);
    }

    /**
     * Resolves symlinks for the deepest existing ancestor and re-appends the missing tail, so
     * containment can be verified for paths whose final file does not exist yet.
     */
    private static Path realPathOf(Path path) throws IOException {
        Path existing = path;
        while (!Files.exists(existing)) {
            Path parent = existing.getParent();
            if (parent == null) {
                throw new IOException("no existing ancestor of " + path);
            }
            existing = parent;
        }
        return existing.toRealPath().resolve(existing.relativize(path));
    }

    /** Resolves the entry's reference: a committed corpus file or a fetched remote image. */
    private Optional<ReferenceImageStore.ReferenceImage> reference(CorpusEntry entry) {
        if (entry.referenceFile() != null) {
            Path local = resolveInside(corpusDir, entry.referenceFile());
            if (!Files.isRegularFile(local)) {
                return Optional.empty();
            }
            try {
                return Optional.of(new ReferenceImageStore.ReferenceImage(
                        BoundedDecode.decode(local)));
            } catch (IOException failure) {
                throw new ReferenceException(ReferenceException.Kind.DECODE,
                        "cannot decode committed reference " + local, failure);
            }
        }
        return store.reference(entry);
    }

    /**
     * Fetches the reference and renders the recreation, returning the multi-signal score; empty
     * when the reference is explicitly absent or the preview process failed. Policy, identity,
     * cache, and decode failures raise {@link ReferenceException} so the qualification fails.
     */
    private Optional<FidelityScore> measure(CorpusEntry entry) {
        Optional<ReferenceImageStore.ReferenceImage> reference = reference(entry);
        if (reference.isEmpty()) {
            return Optional.empty();
        }
        Optional<Path> recreation = render(entry);
        if (recreation.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(VisualFidelity.measure(reference.get(), recreation.get()));
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.DECODE,
                    "cannot measure entry " + entry.id(), failure);
        }
    }

    private static String compact(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /** Renders one recreation through the preview binary; empty when the process fails. */
    private Optional<Path> render(CorpusEntry entry) {
        Path xml = resolveInside(corpusDir, entry.markupFile());
        Path css = corpusDir.resolve("shared.css");
        Path screenshot = resolveInside(outputDir, entry.id() + ".png");
        Path lib = previewDistribution.resolve("lib");
        if (!Files.isDirectory(lib)) {
            return Optional.empty();
        }
        try {
            String classpath = lib.toString() + File.separatorChar + "*";
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Optional<Path> palette = containedPalette(corpusDir, entry.id());
            List<String> command = new ArrayList<>(List.of(
                    java,
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp",
                    classpath));
            if (palette.isPresent()) {
                // The preview's default skin reads this system property for per-corpus palettes.
                command.add("-Dmarkup.skin.palette=" + palette.orElseThrow());
            }
            command.addAll(List.of(
                    "dev.gdx.markup.preview.PreviewApp",
                    "--ui", xml.toString(),
                    "--css", css.toString(),
                    "--frames", Integer.toString(RENDER_FRAMES),
                    "--screenshot", screenshot.toString(),
                    "--exit"));
            ProcessBuilder builder = new ProcessBuilder(command);
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
