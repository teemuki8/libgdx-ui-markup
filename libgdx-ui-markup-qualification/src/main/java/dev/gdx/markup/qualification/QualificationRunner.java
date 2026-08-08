package dev.gdx.markup.qualification;

import dev.gdx.markup.qualification.DeadlineExceededException.Step;
import dev.gdx.markup.qualification.QualificationReport.EntryResult;
import dev.gdx.markup.qualification.QualificationReport.Verdict;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;

/**
 * Renders each corpus recreation with the real preview binary and measures its multi-signal
 * visual fidelity (geometry, color, detail, plus the coarse layout diagnostic) against the
 * fetched reference game UI. Every required component gates independently.
 *
 * <p>One run is bounded by a single monotonic total-work contract: an absolute deadline on the
 * injected clock plus an aggregate work budget (entries, decoded pixels, scored comparisons).
 * The deadline is computed once at run start and threaded into the reference fetch, the render
 * process wait, and the scoring steps as the only remaining-time source, so cumulative work
 * across entries cannot exceed the total budget. A per-entry budget failure surfaces as the
 * bounded {@link Verdict#SKIPPED_REFERENCE} or {@link Verdict#SKIPPED_RENDER} report verdict
 * and stops the run from starting later work; calibration fails loudly on the same internal
 * {@link DeadlineExceededException} instead of committing a partial manifest.
 *
 * <p>Every render child is owned: its stdout/stderr drains are started and joined by the
 * runner, and an unfinished child is terminated destroy {@code ->} bounded wait {@code ->}
 * force {@code ->} final bounded wait, with the interrupt flag restored on interruption so a
 * cancelled run leaves no orphan process and no unjoined drain.
 */
public final class QualificationRunner implements AutoCloseable {
    static final int RENDER_FRAMES = 10;
    /** Ceiling for one render process wait; the run's total deadline is the primary bound. */
    static final Duration RENDER_PROCESS_CAP = Duration.ofSeconds(60);
    /** Grace period granted to a child after {@link Process#destroy()} before force-destroy. */
    static final Duration TERMINATION_GRACE = Duration.ofSeconds(5);
    /** Final bounded wait after {@link Process#destroyForcibly()} before giving up on a child. */
    static final Duration TERMINATION_FORCE_WAIT = Duration.ofSeconds(5);
    /** Bounded real-time window for joining the stdout/stderr drain threads. */
    static final Duration DRAIN_JOIN_WAIT = Duration.ofSeconds(5);
    /** One monotonic total budget for a whole run by default. */
    static final Duration DEFAULT_RUN_BUDGET = Duration.ofMinutes(30);
    /** Default aggregate work budget: 128 entries, 512 analysis frames of pixels, 1024 scores. */
    static final WorkBudget DEFAULT_WORK_BUDGET = new WorkBudget(128,
            512L * BoundedDecode.MAX_ANALYSIS_DIMENSION * BoundedDecode.MAX_ANALYSIS_DIMENSION,
            1024);

    /** Byte cap for a per-entry palette override; mirrors {@code DefaultSkin.MAX_PALETTE_BYTES}. */
    static final int MAX_PALETTE_BYTES = 8192;

    private final Path corpusDir;
    private final ReferenceImageStore store;
    private final Path previewDistribution;
    private final Path outputDir;
    private final ReferenceImageStore.Clock clock;
    private final ProcessLauncher launcher;
    private final Duration totalBudget;
    private final WorkBudget workLimits;

    /** Creates a runner over the corpus, preview distribution, and output dir. */
    public QualificationRunner(Path corpusDir, Path previewDistribution, Path outputDir) {
        this(corpusDir, previewDistribution, outputDir, new ReferenceImageStore(),
                System::nanoTime, ProcessBuilder::start, DEFAULT_RUN_BUDGET,
                DEFAULT_WORK_BUDGET);
    }

    /**
     * Package-private seam constructor for deterministic total-work tests: an injected store
     * (sharing the runner's clock), monotonic clock, process launcher, total budget, and work
     * caps, so a test can prove the deadline and teardown contract without real children.
     */
    QualificationRunner(Path corpusDir, Path previewDistribution, Path outputDir,
            ReferenceImageStore store, ReferenceImageStore.Clock clock,
            ProcessLauncher launcher, Duration totalBudget, WorkBudget workLimits) {
        this.corpusDir = corpusDir;
        this.store = store;
        this.previewDistribution = previewDistribution;
        this.outputDir = outputDir;
        this.clock = clock;
        this.launcher = launcher;
        this.totalBudget = totalBudget;
        this.workLimits = workLimits;
        try {
            Files.createDirectories(outputDir);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException("cannot create output dir " + outputDir,
                    failure);
        }
    }

    /**
     * Runs every corpus entry and writes the bounded JSON report next to the renderings. The
     * run's one monotonic deadline and aggregate work budget stop starting later work once
     * exhausted; the report then contains exactly the entries that were attempted.
     */
    public QualificationReport run() {
        CorpusManifest manifest = CorpusManifest.load(corpusDir.resolve("manifest.json"));
        RunBudget run = new RunBudget(clock.nanoTime() + totalBudget.toNanos(),
                new WorkBudget(workLimits));
        List<EntryResult> results = new ArrayList<>();
        for (CorpusEntry entry : manifest.entries()) {
            if (Thread.currentThread().isInterrupted() || run.work.exhausted()) {
                break;
            }
            if (run.deadlineNanos - clock.nanoTime() <= 0) {
                break;
            }
            results.add(qualify(entry, run));
        }
        QualificationReport report = new QualificationReport(results);
        report.writeJson(outputDir.resolve("report.json"));
        System.out.println(report.summary());
        return report;
    }

    private EntryResult qualify(CorpusEntry entry, RunBudget run) {
        try {
            run.work.spendEntry(entry.id());
            Optional<ReferenceImageStore.ReferenceImage> reference = reference(entry, run);
            Optional<Path> recreationPath = render(entry, run);
            if (reference.isEmpty() || recreationPath.isEmpty()) {
                Verdict skipped = reference.isPresent() ? Verdict.SKIPPED_RENDER
                        : Verdict.SKIPPED_REFERENCE;
                return new EntryResult(entry.id(), entry.license(), FidelityScore.ZERO,
                        entry.thresholds(), skipped, List.of(), false);
            }
            BufferedImage recreation;
            try {
                recreation = decodeRecreation(entry, recreationPath.orElseThrow(), run);
            } catch (IOException failure) {
                throw new ReferenceException(ReferenceException.Kind.DECODE,
                        "cannot decode recreation for " + entry.id(), failure);
            }
            ReferenceImageStore.ReferenceImage referenceImage = reference.orElseThrow();
            requireRemaining(run, Step.SCORE, entry.id());
            run.work.spendScore(entry.id());
            FidelityScore score = VisualFidelity.measure(referenceImage, recreation);
            List<List<Double>> negatives = negativeObservations(referenceImage, recreation,
                    run.work, entry.id());
            boolean stale = isStale(score, entry.thresholds(), negatives);
            List<FidelityComponent> failed = failedDimensions(score, entry.thresholds());
            Verdict verdict = failed.isEmpty() ? Verdict.PASS : Verdict.FAIL;
            return new EntryResult(entry.id(), entry.license(), score, entry.thresholds(),
                    verdict, failed, stale);
        } catch (DeadlineExceededException exhausted) {
            Verdict skipped = exhausted.step() == Step.REFERENCE
                    ? Verdict.SKIPPED_REFERENCE : Verdict.SKIPPED_RENDER;
            return new EntryResult(entry.id(), entry.license(), FidelityScore.ZERO,
                    entry.thresholds(), skipped, List.of(), false);
        }
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
            if (QualificationPolicy.stale(component, thresholds.component(component),
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
     * The same total-work contract as {@link #run()} applies: when the monotonic deadline or
     * the work budget is exhausted the calibration fails loudly and commits nothing, because a
     * partial threshold rewrite would be a meaningless gate.
     */
    public void calibrate() {
        CorpusManifest manifest = CorpusManifest.load(corpusDir.resolve("manifest.json"));
        RunBudget run = new RunBudget(clock.nanoTime() + totalBudget.toNanos(),
                new WorkBudget(workLimits));
        List<CorpusEntry> updated = new ArrayList<>();
        for (CorpusEntry entry : manifest.entries()) {
            if (Thread.currentThread().isInterrupted() || run.work.exhausted()) {
                throw new DeadlineExceededException(DeadlineExceededException.Kind.TIME,
                        Step.RENDER, entry.id(), "calibration cancelled or budget exhausted; "
                                + "no partial manifest is committed");
            }
            Optional<FidelityScore> positive = measure(entry, run);
            if (positive.isEmpty()) {
                updated.add(entry);
                System.out.println("calibration: " + entry.id() + " skipped, thresholds kept");
                continue;
            }
            FidelityScore score = positive.orElseThrow();
            BufferedImage recreation = decodedRecreation(entry, run);
            List<List<Double>> negativeObservations = negativeObservations(
                    reference(entry, run).orElseThrow(), recreation, run.work, entry.id());
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
            ReferenceImageStore.ReferenceImage reference, BufferedImage recreationImage,
            WorkBudget budget, String entryId) {
        List<Double> geometryNegatives = new ArrayList<>();
        List<Double> colorNegatives = new ArrayList<>();
        List<Double> detailNegatives = new ArrayList<>();
        BufferedImage[] negatives = NegativeTransforms.all(recreationImage);
        String[] names = NegativeTransforms.names();
        for (int i = 0; i < negatives.length; i++) {
            budget.spendScore(entryId);
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
    private BufferedImage decodedRecreation(CorpusEntry entry, RunBudget run) {
        Optional<Path> recreation = render(entry, run);
        if (recreation.isEmpty()) {
            throw new IllegalStateException("cannot render " + entry.id() + " for calibration");
        }
        try {
            return decodeRecreation(entry, recreation.orElseThrow(), run);
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
        OptionalDouble threshold = QualificationPolicy.calibrate(component, List.of(positive),
                negatives);
        if (threshold.isEmpty()) {
            double floor = QualificationPolicy.floor(component);
            if (positive < floor) {
                throw new ReferenceException(ReferenceException.Kind.CALIBRATION,
                        "cannot calibrate " + entry.id() + " " + component + ": positive "
                                + compact(positive) + " is below the absolute "
                                + compact(floor) + " floor; a sub-floor recreation cannot be "
                                + "committed as a passing baseline, so improve the recreation "
                                + "above the floor");
            }
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

    /**
     * Resolves the entry's reference: a committed corpus file or a fetched remote image. The
     * committed decode and the remote fetch both draw from the run's remaining budget, so a
     * deadline that arrives before the reference can be obtained surfaces as a bounded
     * {@link Verdict#SKIPPED_REFERENCE}.
     */
    private Optional<ReferenceImageStore.ReferenceImage> reference(CorpusEntry entry,
            RunBudget run) {
        if (entry.referenceFile() != null) {
            Path local = resolveInside(corpusDir, entry.referenceFile());
            if (!Files.isRegularFile(local)) {
                return Optional.empty();
            }
            try {
                BufferedImage decoded = BoundedDecode.decode(local);
                run.work.spendDecodedPixels(
                        (long) decoded.getWidth() * decoded.getHeight(),
                        entry.id(), Step.REFERENCE);
                return Optional.of(new ReferenceImageStore.ReferenceImage(decoded));
            } catch (IOException failure) {
                throw new ReferenceException(ReferenceException.Kind.DECODE,
                        "cannot decode committed reference " + local, failure);
            }
        }
        long remaining = requireRemaining(run, Step.REFERENCE, entry.id());
        return store.reference(entry, remaining);
    }

    /**
     * Fetches the reference and renders the recreation, returning the multi-signal score; empty
     * when the reference is explicitly absent or the preview process failed. Policy, identity,
     * cache, and decode failures raise {@link ReferenceException} so the qualification fails.
     */
    private Optional<FidelityScore> measure(CorpusEntry entry, RunBudget run) {
        Optional<ReferenceImageStore.ReferenceImage> reference = reference(entry, run);
        if (reference.isEmpty()) {
            return Optional.empty();
        }
        Optional<Path> recreation = render(entry, run);
        if (recreation.isEmpty()) {
            return Optional.empty();
        }
        BufferedImage recreationImage;
        try {
            recreationImage = decodeRecreation(entry, recreation.orElseThrow(), run);
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.DECODE,
                    "cannot measure entry " + entry.id(), failure);
        }
        requireRemaining(run, Step.SCORE, entry.id());
        run.work.spendScore(entry.id());
        return Optional.of(VisualFidelity.measure(reference.get(), recreationImage));
    }

    /**
     * Decodes a render output at the bounded analysis resolution, counting its pixels against
     * the run's aggregate work budget.
     */
    private BufferedImage decodeRecreation(CorpusEntry entry, Path recreation, RunBudget run)
            throws IOException {
        BufferedImage image = BoundedDecode.decode(recreation);
        run.work.spendDecodedPixels((long) image.getWidth() * image.getHeight(),
                entry.id(), Step.RENDER);
        return image;
    }

    /** Returns the run's remaining monotonic budget, failing typed when it is already spent. */
    private long requireRemaining(RunBudget run, Step step, String entryId) {
        long remaining = run.deadlineNanos - clock.nanoTime();
        if (remaining <= 0) {
            throw new DeadlineExceededException(DeadlineExceededException.Kind.TIME, step,
                    entryId, "run deadline exhausted");
        }
        return remaining;
    }

    private static String compact(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /**
     * Renders one recreation through the preview binary; empty when the process fails. The
     * process wait is bounded by the run's remaining budget (never a fresh per-render
     * timeout), and an unfinished child is terminated destroy {@code ->} bounded wait
     * {@code ->} force {@code ->} final bounded wait before the entry fails, so no child is
     * ever left running and no drain is left unjoined.
     */
    private Optional<Path> render(CorpusEntry entry, RunBudget run) {
        Path xml = resolveInside(corpusDir, entry.markupFile());
        Path css = corpusDir.resolve("shared.css");
        Path screenshot = resolveInside(outputDir, entry.id() + ".png");
        Path lib = previewDistribution.resolve("lib");
        if (!Files.isDirectory(lib)) {
            return Optional.empty();
        }
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
        Process process = null;
        List<Thread> drains = List.of();
        try {
            long waitNanos = Math.min(requireRemaining(run, Step.RENDER, entry.id()),
                    RENDER_PROCESS_CAP.toNanos());
            process = launcher.start(new ProcessBuilder(command));
            drains = startDrains(process);
            boolean finished = process.waitFor(waitNanos, TimeUnit.NANOSECONDS);
            if (!finished) {
                terminate(process);
                throw new DeadlineExceededException(DeadlineExceededException.Kind.TIME,
                        Step.RENDER, entry.id(), "render did not finish within the run deadline");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(screenshot)) {
                return Optional.empty();
            }
            return Optional.of(screenshot);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) {
                forceKill(process);
            }
            return Optional.empty();
        } catch (IOException failure) {
            return Optional.empty();
        } finally {
            if (process != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
            }
            joinDrains(drains);
        }
    }

    /**
     * Terminates a child that outlived its remaining budget: destroy, bounded wait, force,
     * final bounded wait. Every phase is capped so teardown itself is bounded, and an
     * interrupt during teardown restores the flag and still force-destroys the child.
     */
    private static void terminate(Process process) {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        if (await(process, TERMINATION_GRACE)) {
            return;
        }
        process.destroyForcibly();
        await(process, TERMINATION_FORCE_WAIT);
    }

    /** Force-destroys a child and waits a final bounded window for it to die. */
    private static void forceKill(Process process) {
        process.destroyForcibly();
        await(process, TERMINATION_FORCE_WAIT);
    }

    /** Bounded, interrupt-preserving process wait used only for teardown phases. */
    private static boolean await(Process process, Duration timeout) {
        try {
            return process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Starts one discard thread per stdio pipe and returns their handles for bounded joining. */
    private static List<Thread> startDrains(Process process) {
        return List.of(
                drainThread(process.getInputStream()),
                drainThread(process.getErrorStream()));
    }

    private static Thread drainThread(InputStream stream) {
        return Thread.ofVirtual().name("qualification-drain").start(() -> {
            byte[] buffer = new byte[8192];
            try {
                while (stream.read(buffer) >= 0) {
                    // bounded discard
                }
            } catch (IOException ignored) {
                // process ended or the runner closed this pipe end
            }
        });
    }

    /** Closes one runner-owned pipe end so a stuck child cannot keep a drain alive forever. */
    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // already closed by the child's exit
        }
    }

    /** Joins every drain within a bounded real-time window; an interrupt restores the flag. */
    private static void joinDrains(List<Thread> drains) {
        long deadline = System.nanoTime() + DRAIN_JOIN_WAIT.toNanos();
        for (Thread drain : drains) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            try {
                drain.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * One run's total-work contract: the absolute monotonic deadline computed once at run
     * start plus the mutable aggregate work budget threaded through every entry.
     */
    private static final class RunBudget {
        final long deadlineNanos;
        final WorkBudget work;

        RunBudget(long deadlineNanos, WorkBudget work) {
            this.deadlineNanos = deadlineNanos;
            this.work = work;
        }
    }

    /** Process-spawn seam so deterministic tests never touch the OS process table. */
    @FunctionalInterface
    interface ProcessLauncher {
        Process start(ProcessBuilder builder) throws IOException;
    }

    @Override public void close() {
        store.close();
    }
}
