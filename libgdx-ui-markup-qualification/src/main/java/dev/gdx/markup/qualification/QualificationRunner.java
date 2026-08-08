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
import java.util.Iterator;
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
 * <p>The runner explicitly owns every child and stdio drain it spawns, retaining the handles
 * as fields until they are confirmed terminal. An unfinished child is terminated destroy
 * {@code ->} bounded wait {@code ->} force {@code ->} final bounded wait inside one shared
 * teardown window; a child that still refuses to die makes the run fail fatally with an
 * internal {@link UnkillableChildException} (no report is written, no later work starts) and
 * the handle stays owned so {@link #close()} retries force-destroy with a bounded
 * confirmation. The interrupt flag is restored on interruption, drains are cancelled and
 * joined even when the calling thread is itself interrupted, and {@link #close()} never
 * returns success while any owned child or drain is still alive.
 */
public final class QualificationRunner implements AutoCloseable {
    static final int RENDER_FRAMES = 10;
    /** Ceiling for one render process wait; the run's total deadline is the primary bound. */
    static final Duration RENDER_PROCESS_CAP = Duration.ofSeconds(60);
    /** Grace period granted to a child after {@link Process#destroy()} before force-destroy. */
    static final Duration TERMINATION_GRACE = Duration.ofSeconds(5);
    /** Final bounded wait after {@link Process#destroyForcibly()} before giving up on a child. */
    static final Duration TERMINATION_FORCE_WAIT = Duration.ofSeconds(5);
    /**
     * One bounded teardown window covering the destroy grace, the force wait, and the drain
     * joins, so terminating an unfinished child is itself bounded and the drain joins draw
     * from the same window instead of a fresh deadline.
     */
    static final Duration TERMINATION_BUDGET = Duration.ofSeconds(15);
    /** Bounded confirmation window used by {@link #close()} to retry retained children/drains. */
    static final Duration CLOSE_CONFIRMATION = Duration.ofSeconds(5);
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
    /** Guards the owned-handle lists and the lifecycle state; close() may run after run(). */
    private final Object ownershipLock = new Object();
    /** Children spawned but not yet confirmed terminal; close() retries force-destroy. */
    private final List<Process> ownedProcesses = new ArrayList<>();
    /** Stdio drains spawned but not yet confirmed joined; close() cancels and re-joins. */
    private final List<Thread> ownedDrains = new ArrayList<>();
    /** Set once close() begins; no new work may spawn or register afterwards. */
    private boolean closing;
    /** Number of run/calibrate invocations currently executing; guards against overlap. */
    private int activeOperations;

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
        acquireOperation();
        try {
            return runLocked();
        } finally {
            releaseOperation();
        }
    }

    private QualificationReport runLocked() {
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
                    run, entry.id());
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
        acquireOperation();
        try {
            calibrateLocked();
        } finally {
            releaseOperation();
        }
    }

    private void calibrateLocked() {
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
                    reference(entry, run).orElseThrow(), recreation, run, entry.id());
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
        // A calibration that reached the write with the deadline spent must not commit the
        // partial threshold rewrite; fail loudly instead.
        requireRemaining(run, Step.SCORE, "manifest");
        manifest.write(corpusDir.resolve("manifest.json"), updated);
    }

    /**
     * Applies the deterministic deliberate negatives to the decoded recreation one at a time
     * and measures each against the reference, returning the component-relevant observation
     * lists. Each transformation is a negative for the failure mode it exhibits: a vertical
     * flip, a fixed translation, and a uniform scale misplace layout (geometry); a hue
     * rotation re-palettes the screen (color); a box blur and a uniform scale destroy
     * typography and fine structure (detail). The palette-preserving flip/translation/hue
     * scores on other components are not calibration negatives for those components.
     *
     * <p>Every transform and every score re-checks the run's remaining monotonic budget, so
     * neither this run nor a calibration can allocate frames or measure after expiry.
     */
    private List<List<Double>> negativeObservations(
            ReferenceImageStore.ReferenceImage reference, BufferedImage recreationImage,
            RunBudget run, String entryId) {
        List<Double> geometryNegatives = new ArrayList<>();
        List<Double> colorNegatives = new ArrayList<>();
        List<Double> detailNegatives = new ArrayList<>();
        for (String name : NegativeTransforms.names()) {
            requireRemaining(run, Step.SCORE, entryId);
            BufferedImage negative = applyNegative(name, recreationImage);
            requireRemaining(run, Step.SCORE, entryId);
            run.work.spendScore(entryId);
            FidelityScore negativeScore = VisualFidelity.measure(reference, negative);
            if ("flip".equals(name) || "translate".equals(name) || "scale".equals(name)) {
                geometryNegatives.add(negativeScore.geometry());
            }
            if ("hue".equals(name) || "color-swap".equals(name)) {
                colorNegatives.add(negativeScore.color());
            }
            if ("blur".equals(name) || "scale".equals(name)) {
                detailNegatives.add(negativeScore.detail());
            }
        }
        return List.of(geometryNegatives, colorNegatives, detailNegatives);
    }

    /** Applies one named deliberate negative to the recreation. */
    private static BufferedImage applyNegative(String name, BufferedImage source) {
        switch (name) {
            case "flip":
                return NegativeTransforms.flip(source);
            case "translate":
                return NegativeTransforms.translate(source);
            case "hue":
                return NegativeTransforms.hueRotate(source);
            case "blur":
                return NegativeTransforms.blur(source);
            case "scale":
                return NegativeTransforms.scale(source);
            default:
                throw new IllegalArgumentException("unknown deliberate negative " + name);
        }
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
     * {@code ->} force {@code ->} final bounded wait inside one shared teardown window. The
     * child is retained until it is confirmed terminal: a child still alive after the final
     * wait makes the run fail closed so no later work is started, and the drains are joined
     * against the same window (never a fresh deadline) and cancelled if they refuse to end.
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
        // Happy path: drains join inside the run's own monotonic deadline. When teardown is
        // needed (deadline hit or interrupt) a fresh bounded teardown window covers the
        // destroy/force waits AND the drain joins, so teardown is bounded as a whole.
        long drainDeadline = run.deadlineNanos;
        Throwable inFlight = null;
        InterruptAccumulator teardownInterrupts = new InterruptAccumulator();
        try {
            try {
                // Refuse to spawn when the run budget is already spent, then spawn and
                // register as one lifecycle transaction under the ownership lock: close()
                // either sees the child and drains before it confirms, or the spawn is
                // rejected (the lock is held across launcher.start, which is quick).
                long waitNanos = Math.min(requireRemaining(run, Step.RENDER, entry.id()),
                        RENDER_PROCESS_CAP.toNanos());
                synchronized (ownershipLock) {
                    if (closing) {
                        throw new IllegalStateException(
                                "qualification runner is closing; no new child may be spawned");
                    }
                    process = launcher.start(new ProcessBuilder(command));
                    ownedProcesses.add(process);
                    drains = startDrains(process);
                    ownedDrains.addAll(drains);
                }
                boolean finished = process.waitFor(waitNanos, TimeUnit.NANOSECONDS);
                if (!finished) {
                    drainDeadline = teardownDeadline();
                    if (!terminate(process, drainDeadline, teardownInterrupts)) {
                        // The child is still alive: fail fatally, retain the handle for close().
                        throw new UnkillableChildException(entry.id());
                    }
                    unregisterProcess(process);
                    throw new DeadlineExceededException(DeadlineExceededException.Kind.TIME,
                            Step.RENDER, entry.id(),
                            "render did not finish within the run deadline");
                }
                unregisterProcess(process);
                if (process.exitValue() != 0 || !Files.isRegularFile(screenshot)) {
                    return Optional.empty();
                }
                return Optional.of(screenshot);
            } catch (IOException failure) {
                inFlight = failure;
                return Optional.empty();
            } catch (InterruptedException interrupted) {
                // Hold the interrupt aside while the bounded teardown confirms the child (a
                // timed process wait on an already-interrupted thread throws immediately),
                // then restore it afterwards so the run loop stops starting later work. It is
                // also recorded as the in-flight cause so an unjoined drain fatal suppresses
                // it instead of losing the reason for the skip.
                try {
                    if (process != null) {
                        drainDeadline = teardownDeadline();
                        if (!forceKill(process, drainDeadline, teardownInterrupts)) {
                            inFlight = new UnkillableChildException(entry.id());
                        } else {
                            unregisterProcess(process);
                            inFlight = interrupted;
                        }
                    } else {
                        inFlight = interrupted;
                    }
                    return Optional.empty();
                } finally {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (RuntimeException | Error failure) {
            // Record the in-flight fatal (deadline, unkillable child, closing) so the drain
            // confirmation below suppresses it instead of replacing it.
            inFlight = failure;
            throw failure;
        } finally {
            if (process != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
            }
            List<Thread> liveDrains = joinDrains(drains, drainDeadline);
            if (!liveDrains.isEmpty()) {
                // A drain could not be confirmed terminal within the shared deadline: the
                // render must not report success or a skip while owned work is alive. Retain
                // the drains for close() and fail fatally, suppressing any in-flight failure.
                retainLiveDrains(drains, liveDrains);
                UnkillableChildException drainFatal =
                        new UnkillableChildException(entry.id() + " (unjoined stdio drain)");
                if (inFlight != null) {
                    drainFatal.addSuppressed(inFlight);
                }
                throw drainFatal;
            }
            unregisterDrains(drains);
            // Restore any interrupt accumulated during the teardown waits exactly once, after
            // the drain joins, so the run loop observes the cancellation.
            if (teardownInterrupts.reassert) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Starts a fresh bounded teardown window once the run deadline is already spent. */
    private long teardownDeadline() {
        return clock.nanoTime() + TERMINATION_BUDGET.toNanos();
    }

    /**
     * Terminates a child inside the teardown window: destroy, bounded wait, force, final
     * bounded wait, each phase capped and drawing from the same window. Returns whether the
     * child was confirmed dead; a child still alive after the final wait must make the run
     * fail fatally instead of being dropped. A fresh interrupt during a teardown wait is
     * accumulated (not reasserted) so the remaining confirmation waits can still wait.
     */
    private boolean terminate(Process process, long teardownDeadline,
            InterruptAccumulator interrupts) {
        if (!process.isAlive()) {
            return true;
        }
        process.destroy();
        if (await(process, Math.min(TERMINATION_GRACE.toNanos(), remaining(teardownDeadline)),
                interrupts)) {
            return true;
        }
        process.destroyForcibly();
        return await(process, Math.min(TERMINATION_FORCE_WAIT.toNanos(),
                remaining(teardownDeadline)), interrupts);
    }

    /** Force-destroys a child and waits the remaining teardown window for it to die. */
    private boolean forceKill(Process process, long teardownDeadline,
            InterruptAccumulator interrupts) {
        process.destroyForcibly();
        return await(process, remaining(teardownDeadline), interrupts);
    }

    /** Remaining monotonic budget of a deadline, floored at zero for a bounded wait. */
    private long remaining(long deadlineNanos) {
        return Math.max(0, deadlineNanos - clock.nanoTime());
    }

    /**
     * Bounded, interrupt-accumulating process wait used for teardown phases: an interrupt
     * aborts this wait but is held aside (restored by the caller once after the whole
     * teardown) so the remaining confirmation waits can actually wait.
     */
    private static boolean await(Process process, long timeoutNanos,
            InterruptAccumulator interrupts) {
        try {
            return process.waitFor(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            interrupts.reassert = true;
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

    /**
     * Joins every drain within the given monotonic deadline and returns the drains that could
     * not be confirmed terminal, so the caller retains them for {@link #close()} to cancel
     * and re-join — a drain is never silently dropped while alive. The deadline is read once
     * at entry and the real join time is accounted against the remaining budget, so the whole
     * join is bounded as one. A drain still alive at the deadline is cancelled (interrupted)
     * but kept in the result. The pipe ends were already closed, so a well-behaved drain
     * unblocks at EOF. Works even when the calling thread is itself interrupted: the
     * interrupt is cleared for the duration of the join and restored afterwards — including
     * an interrupt that arrives while a join is blocking — and a drain cancelled by a
     * mid-join interrupt is kept joined so its termination is confirmed.
     */
    List<Thread> joinDrains(List<Thread> drains, long deadlineNanos) {
        boolean interrupted = Thread.interrupted();
        List<Thread> live = new ArrayList<>();
        try {
            long remaining = deadlineNanos - clock.nanoTime();
            for (Thread drain : drains) {
                while (drain.isAlive() && remaining > 0) {
                    long sliceMillis = remaining / 1_000_000;
                    if (sliceMillis < 1) {
                        // Less than a millisecond of budget: waiting would exceed the
                        // deadline, so the drain stays unconfirmed.
                        break;
                    }
                    try {
                        drain.join(Math.min(sliceMillis, 10));
                    } catch (InterruptedException interruptedDuringJoin) {
                        // A fresh interrupt arrived while joining: remember it so the flag is
                        // restored, cancel this drain, and keep joining so it can die and be
                        // confirmed within the remaining budget.
                        interrupted = true;
                        drain.interrupt();
                    }
                    remaining -= TimeUnit.MILLISECONDS.toNanos(Math.min(sliceMillis, 10));
                }
                if (drain.isAlive()) {
                    drain.interrupt();
                    live.add(drain);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return live;
    }

    /**
     * Reserves one run/calibrate invocation, rejecting a call on a runner that is closing or
     * that still owns unfinished work from a previous invocation (or already has one running),
     * so no invocation can perform or report work while an earlier child or drain is alive.
     * The reservation is released in the caller's finally.
     */
    private void acquireOperation() {
        synchronized (ownershipLock) {
            if (closing) {
                throw new IllegalStateException("qualification runner is closing/closed");
            }
            if (!ownedProcesses.isEmpty() || !ownedDrains.isEmpty() || activeOperations > 0) {
                throw new IllegalStateException("qualification runner still owns unfinished "
                        + "child work from a previous invocation; close the runner first");
            }
            activeOperations++;
        }
    }

    private void releaseOperation() {
        synchronized (ownershipLock) {
            activeOperations--;
        }
    }

    /** Drops a child that was confirmed terminal. */
    private void unregisterProcess(Process process) {
        synchronized (ownershipLock) {
            ownedProcesses.remove(process);
        }
    }

    /** Drops drains that were confirmed joined. */
    private void unregisterDrains(List<Thread> drains) {
        synchronized (ownershipLock) {
            ownedDrains.removeAll(drains);
        }
    }

    /**
     * Confirmed drains drop out of ownership; drains that are still alive stay owned for
     * close() to cancel and re-join. A no-op once the runner is closing: close() owns the
     * confirmation at that point, and a late render must not remove or re-add handles that
     * close() is processing.
     */
    private void retainLiveDrains(List<Thread> drains, List<Thread> live) {
        synchronized (ownershipLock) {
            if (closing) {
                return;
            }
            for (Thread drain : drains) {
                if (!live.contains(drain)) {
                    ownedDrains.remove(drain);
                }
            }
            ownedDrains.addAll(live);
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

    /**
     * Closes the runner, first confirming that every owned child and drain is terminal within
     * a single bounded real-time confirmation deadline. A retained child is force-destroyed
     * again and waited on; a retained drain is cancelled and re-joined, each drawing only the
     * remaining confirmation time. Handles that are confirmed are dropped from ownership;
     * handles still alive at the deadline are RETAINED and the close fails with a typed
     * {@link ReferenceException} (suppressed per-failure causes), so a repeated close retries
     * and keeps failing until every owned handle is confirmed — {@code close()} never returns
     * success while owned work is alive. The caller's interrupt is held aside for the
     * duration and restored afterwards, so the bounded confirmation waits can actually wait.
     * Idempotent: once every owned handle is confirmed, later closes only close the store.
     */
    @Override public void close() {
        boolean reassert = Thread.interrupted();
        InterruptAccumulator interrupts = new InterruptAccumulator();
        List<Throwable> failures = new ArrayList<>();
        try {
            // One absolute monotonic confirmation deadline for the whole close: every process
            // wait and drain join draws only the remaining time, so close is bounded as a
            // whole instead of per handle.
            long closeDeadline = System.nanoTime() + CLOSE_CONFIRMATION.toNanos();
            synchronized (ownershipLock) {
                closing = true;
                Iterator<Process> processes = ownedProcesses.iterator();
                while (processes.hasNext()) {
                    Process process = processes.next();
                    if (!process.isAlive()) {
                        processes.remove();
                        continue;
                    }
                    process.destroyForcibly();
                    boolean confirmed;
                    try {
                        confirmed = process.waitFor(Math.max(0,
                                closeDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    } catch (InterruptedException interruptedDuringClose) {
                        // Hold the fresh interrupt aside (restored once below) so the
                        // remaining confirmations can still wait.
                        interrupts.reassert = true;
                        confirmed = false;
                    }
                    if (confirmed) {
                        processes.remove();
                    } else {
                        // Retain the still-live child so a later close retries it.
                        failures.add(new IOException("child " + process
                                + " still alive after the close confirmation deadline"));
                    }
                }
                Iterator<Thread> drainIt = ownedDrains.iterator();
                while (drainIt.hasNext()) {
                    Thread drain = drainIt.next();
                    if (joinDrain(drain, closeDeadline, interrupts)) {
                        drainIt.remove();
                    } else {
                        // Retain the still-live drain so a later close retries it.
                        failures.add(new IOException("drain " + drain.getName()
                                + " still alive after the close confirmation deadline"));
                    }
                }
            }
            try {
                store.close();
            } catch (RuntimeException | Error failure) {
                failures.add(failure);
            }
        } finally {
            if (reassert || interrupts.reassert) {
                Thread.currentThread().interrupt();
            }
        }
        if (!failures.isEmpty()) {
            ReferenceException combined = new ReferenceException(ReferenceException.Kind.IO,
                    "qualification runner close could not confirm all owned work terminated ("
                            + failures.size() + " failure(s))");
            for (Throwable failure : failures) {
                combined.addSuppressed(failure);
            }
            throw combined;
        }
    }

    /**
     * Accumulates interrupt state across a teardown or close confirmation phase, so a fresh
     * interrupt aborts only the current wait instead of cascading through the rest: the flag
     * is held aside for the phase and restored exactly once at the end.
     */
    private static final class InterruptAccumulator {
        boolean reassert;
    }

    /**
     * Cancels and joins one owned drain within the close deadline; false when still alive or
     * when the caller was interrupted mid-join (the interrupt is accumulated, not reasserted,
     * so the remaining confirmations can still wait). Never waits past the deadline.
     */
    private static boolean joinDrain(Thread drain, long closeDeadline,
            InterruptAccumulator interrupts) {
        drain.interrupt();
        while (drain.isAlive()) {
            long remaining = closeDeadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            long sliceMillis = remaining / 1_000_000;
            if (sliceMillis < 1) {
                // Less than a millisecond of budget: waiting would exceed the deadline.
                return false;
            }
            try {
                drain.join(Math.min(sliceMillis, 10));
            } catch (InterruptedException interruptedDuringClose) {
                interrupts.reassert = true;
                return false;
            }
        }
        return true;
    }
}
