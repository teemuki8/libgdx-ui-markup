package dev.gdx.markup.qualification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gdx.markup.qualification.QualificationReport.EntryResult;
import dev.gdx.markup.qualification.QualificationReport.Verdict;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Total-work contract of the qualification runner: one monotonic absolute deadline spans the
 * whole run and is cumulative across entries, an unfinished child is terminated
 * destroy {@code ->} bounded wait {@code ->} force {@code ->} final bounded wait without
 * leaving an orphan, interruption restores the flag and joins the stdio drains, and the
 * aggregate work budget stops the run when exhausted. All children are scripted fakes driven
 * by an injected monotonic clock, so no real process or sleep is involved.
 */
final class RunnerDeadlineTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final Duration HOUR = Duration.ofHours(1);

    @TempDir Path tempDir;

    // ---------------------------------------------------------------- fixtures

    private record Fixture(Path corpusDir, Path outputDir, Path previewDist) {
    }

    /** Writes a two-entry committed-reference corpus and returns its directories. */
    private Fixture corpus() throws IOException {
        Path corpusDir = Files.createDirectories(tempDir.resolve("corpus"));
        Path referenceDir = Files.createDirectories(corpusDir.resolve("reference"));
        Path outputDir = Files.createDirectories(tempDir.resolve("output"));
        Path previewRoot = Files.createDirectories(tempDir.resolve("preview"));
        Files.createDirectories(previewRoot.resolve("lib"));
        ObjectNode root = JSON.createObjectNode();
        root.put("comment", "runner deadline test corpus");
        ArrayNode entries = root.putArray("entries");
        for (int i = 0; i < 2; i++) {
            String id = "entry" + i;
            ObjectNode node = entries.addObject();
            node.put("id", id);
            node.put("referenceFile", "reference/" + id + ".png");
            node.put("license", "MIT");
            node.put("markupFile", id + ".xml");
            ObjectNode thresholds = node.putObject("thresholds");
            thresholds.put("geometry", 0.2);
            thresholds.put("color", 0.2);
            thresholds.put("detail", 0.2);
            thresholds.put("coarseLayout", 0.2);
            node.put("referenceWidth", 8);
            node.put("referenceHeight", 8);
            writeTinyPng(referenceDir.resolve(id + ".png"));
        }
        Files.writeString(corpusDir.resolve("manifest.json"), JSON.writeValueAsString(root));
        return new Fixture(corpusDir, outputDir, previewRoot);
    }

    /** Writes a deterministic 8x8 PNG so reference and recreation decode identically. */
    private static void writeTinyPng(Path file) throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                image.setRGB(x, y, (x + y) % 2 == 0 ? 0x2a2a2aff : 0xd0d0d0ff);
            }
        }
        ImageIO.write(image, "png", file.toFile());
    }

    /**
     * Launcher that writes the requested screenshot (a valid tiny PNG) and hands out the
     * scripted children in order, recording how many children were actually spawned.
     */
    private static final class ScriptedLauncher implements QualificationRunner.ProcessLauncher {
        private final ArrayDeque<ScriptedProcess> children;
        int starts;

        ScriptedLauncher(List<ScriptedProcess> children) {
            this.children = new ArrayDeque<>(children);
        }

        @Override
        public Process start(ProcessBuilder builder) throws IOException {
            starts++;
            List<String> command = builder.command();
            int screenshot = command.indexOf("--screenshot");
            writeTinyPng(Path.of(command.get(screenshot + 1)));
            ScriptedProcess child = children.pollFirst();
            if (child == null) {
                throw new AssertionError("unexpected child spawn");
            }
            return child;
        }
    }

    private QualificationRunner runner(Fixture fixture, ReferenceImageStore.Clock clock,
            ScriptedLauncher launcher, Duration totalBudget, WorkBudget budget) {
        return new QualificationRunner(fixture.corpusDir(), fixture.previewDist(),
                fixture.outputDir(), new ReferenceImageStore(), clock, launcher, totalBudget,
                budget);
    }

    // ---------------------------------------------------------------- scripted child

    /**
     * Deterministic process double on an injected monotonic clock. In {@code SCRIPTED} mode
     * every {@code waitFor} advances the fake clock by the process's simulated runtime
     * (capped by the allowed wait) and returns whether the process died in time, so deadline
     * tests need no concurrency. In {@code BLOCKING} mode the wait parks until the thread is
     * interrupted or the child is force-killed, which is what the interrupt test needs.
     */
    static final class ScriptedProcess extends Process {
        enum Behavior { EXITS_AFTER, EXITS_ON_DESTROY, EXITS_ON_FORCE, NEVER_EXITS }

        private final MutableClock clock;
        private final boolean blocking;
        private final Behavior behavior;
        private final long runtimeNanos;
        private final ControllableInputStream out = new ControllableInputStream();
        private final ControllableInputStream err = new ControllableInputStream();
        private final CountDownLatch parked = new CountDownLatch(1);
        private boolean destroyed;
        private boolean forciblyDestroyed;
        private boolean exited;
        private long spawnedAt = -1;

        private ScriptedProcess(MutableClock clock, boolean blocking, Behavior behavior,
                long runtimeNanos) {
            this.clock = clock;
            this.blocking = blocking;
            this.behavior = behavior;
            this.runtimeNanos = runtimeNanos;
        }

        static ScriptedProcess scripted(MutableClock clock, Behavior behavior,
                long runtimeNanos) {
            return new ScriptedProcess(clock, false, behavior, runtimeNanos);
        }

        static ScriptedProcess blocking(MutableClock clock) {
            return new ScriptedProcess(clock, true, Behavior.EXITS_AFTER, Long.MAX_VALUE / 2);
        }

        private long currentDeadAt() {
            if (behavior == Behavior.NEVER_EXITS) {
                return Long.MAX_VALUE;
            }
            if (spawnedAt < 0) {
                spawnedAt = clock.nanoTime();
            }
            long base = spawnedAt + runtimeNanos;
            if (behavior == Behavior.EXITS_ON_DESTROY && destroyed) {
                return clock.nanoTime();
            }
            if (behavior == Behavior.EXITS_ON_FORCE && forciblyDestroyed) {
                return clock.nanoTime();
            }
            return base;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (blocking) {
                parked.countDown();
                synchronized (this) {
                    while (!exited && !forciblyDestroyed) {
                        wait();
                    }
                    exited = true;
                    out.eof();
                    err.eof();
                    return true;
                }
            }
            long allowed = unit.toNanos(timeout);
            long now = clock.nanoTime();
            long deadAt = currentDeadAt();
            long elapsed = Math.min(allowed, Math.max(0, deadAt - now));
            clock.advanceBy(elapsed);
            boolean finished = deadAt <= now + elapsed;
            if (finished) {
                exited = true;
                out.eof();
                err.eof();
            }
            return finished;
        }

        @Override public int waitFor() {
            throw new UnsupportedOperationException("the runner uses waitFor(timeout, unit)");
        }

        @Override public int exitValue() {
            return exited ? 0 : -1;
        }

        @Override public void destroy() {
            destroyed = true;
        }

        @Override public Process destroyForcibly() {
            forciblyDestroyed = true;
            return this;
        }

        @Override public boolean isAlive() {
            return !exited;
        }

        @Override public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override public InputStream getInputStream() {
            return out;
        }

        @Override public InputStream getErrorStream() {
            return err;
        }

        boolean destroyed() {
            return destroyed;
        }

        boolean forciblyDestroyed() {
            return forciblyDestroyed;
        }

        boolean exited() {
            return exited;
        }

        boolean streamsClosed() {
            return out.closed() && err.closed();
        }

        /** Number of drain threads still blocked reading this child; zero = drains joined. */
        int activeDrainReads() {
            return out.readers() + err.readers();
        }

        /** Blocking mode only: completes when the first waitFor starts parking. */
        void awaitParked() throws InterruptedException {
            assertTrue(parked.await(10, TimeUnit.SECONDS), "child waitFor never parked");
        }
    }

    /** Monotonic clock a test can advance; safe to share with scripted children. */
    static class MutableClock implements ReferenceImageStore.Clock {
        private long now;

        @Override public synchronized long nanoTime() {
            return now;
        }

        synchronized void advanceBy(long nanos) {
            now += nanos;
        }
    }

    /**
     * Clock that reports normal time for the first {@code normalCalls} reads and then jumps
     * far past any deadline, so a test can prove a specific step re-checks the run deadline.
     * The runner's per-entry call sequence is documented in each test that uses it; the jump
     * fires on the next {@code nanoTime()} after the positive score, i.e. inside the
     * deliberate-negative loop that must re-check the deadline before every transform/score.
     */
    static final class CallCountingClock extends MutableClock {
        private final int normalCalls;
        private int calls;

        CallCountingClock(int normalCalls) {
            this.normalCalls = normalCalls;
        }

        @Override public synchronized long nanoTime() {
            calls++;
            long base = super.nanoTime();
            return calls > normalCalls ? base + Long.MAX_VALUE / 4 : base;
        }
    }

    /** Pipe end that blocks until the process exits or the runner closes it. */
    static final class ControllableInputStream extends InputStream {
        private boolean closed;
        private boolean eof;
        private int readers;

        @Override
        public synchronized int read() {
            readers++;
            try {
                while (!closed && !eof) {
                    try {
                        wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
                return -1;
            } finally {
                readers--;
            }
        }

        synchronized void eof() {
            eof = true;
            notifyAll();
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }

        synchronized boolean closed() {
            return closed;
        }

        /** Readers currently blocked in read(); zero means every drain has terminated. */
        synchronized int readers() {
            return readers;
        }
    }

    // ---------------------------------------------------------------- cumulative deadline

    @Test
    @Timeout(30)
    void cumulativeDeadlineSpansEntriesAndStopsTheRun() throws Exception {
        Fixture fixture = corpus();
        MutableClock clock = new MutableClock();
        ScriptedProcess first = ScriptedProcess.scripted(clock,
                ScriptedProcess.Behavior.EXITS_AFTER, 30 * SECOND);
        ScriptedProcess second = ScriptedProcess.scripted(clock,
                ScriptedProcess.Behavior.EXITS_ON_DESTROY, 30 * SECOND);
        ScriptedLauncher launcher = new ScriptedLauncher(List.of(first, second));
        try (QualificationRunner runner = runner(fixture, clock, launcher,
                Duration.ofSeconds(50), new WorkBudget(8, 1_000_000, 100))) {
            QualificationReport report = runner.run();
            assertEquals(2, report.results().size(),
                    "both entries must be attempted before the total deadline is spent");
            assertEquals(Verdict.PASS, report.results().get(0).verdict(),
                    "the first entry fits inside the total budget");
            assertEquals(Verdict.SKIPPED_RENDER, report.results().get(1).verdict(),
                    "the second entry only gets the remaining budget and must be skipped");
            assertTrue(second.destroyed(),
                    "the over-budget child must be terminated gracefully first");
            assertFalse(second.forciblyDestroyed(),
                    "a graceful destroy must not require force");
            assertTrue(second.exited(), "the gracefully destroyed child must actually exit");
        }
    }

    @Test
    @Timeout(30)
    void forcedTerminationRunsDestroyThenForceWhenGraceIsIgnored() throws Exception {
        Fixture fixture = corpus();
        MutableClock clock = new MutableClock();
        ScriptedProcess child = ScriptedProcess.scripted(clock,
                ScriptedProcess.Behavior.EXITS_ON_FORCE, 30 * SECOND);
        ScriptedLauncher launcher = new ScriptedLauncher(List.of(child));
        try (QualificationRunner runner = runner(fixture, clock, launcher,
                Duration.ofSeconds(10), new WorkBudget(8, 1_000_000, 100))) {
            QualificationReport report = runner.run();
            assertEquals(1, report.results().size());
            assertEquals(Verdict.SKIPPED_RENDER, report.results().get(0).verdict());
            assertTrue(child.destroyed(), "destroy must be attempted before force");
            assertTrue(child.forciblyDestroyed(), "an uncooperative child must be force-killed");
            assertTrue(child.exited(), "the force-killed child must actually exit");
        }
    }

    @Test
    @Timeout(30)
    void nonExitingChildFailsClosedAndStopsLaterWork() throws Exception {
        Fixture fixture = corpus();
        MutableClock clock = new MutableClock();
        ScriptedProcess unkillable = ScriptedProcess.scripted(clock,
                ScriptedProcess.Behavior.NEVER_EXITS, 30 * SECOND);
        ScriptedProcess later = ScriptedProcess.scripted(clock,
                ScriptedProcess.Behavior.EXITS_AFTER, SECOND);
        ScriptedLauncher launcher = new ScriptedLauncher(List.of(unkillable, later));
        try (QualificationRunner runner = runner(fixture, clock, launcher,
                Duration.ofSeconds(10), new WorkBudget(8, 1_000_000, 100))) {
            long started = System.nanoTime();
            QualificationReport report = runner.run();
            long elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
            assertTrue(elapsed < 10,
                    "teardown of an unkillable child must stay bounded (took " + elapsed + "s)");
            assertEquals(1, report.results().size(),
                    "an unkillable child must fail the run closed: no later entry may start");
            assertEquals(Verdict.SKIPPED_RENDER, report.results().get(0).verdict());
            assertEquals(1, launcher.starts,
                    "the runner must stop starting later work once a child cannot be killed");
            assertTrue(unkillable.destroyed() && unkillable.forciblyDestroyed(),
                    "the full destroy -> wait -> force -> final wait sequence must run");
            assertFalse(unkillable.exited(),
                    "the child is genuinely unkillable and the runner must not pretend otherwise");
            assertTrue(unkillable.streamsClosed(),
                    "the runner must close its pipe ends so the drains can join");
            assertEquals(0, unkillable.activeDrainReads(),
                    "no drain may remain blocked after the bounded teardown window");
        }
    }

    // ---------------------------------------------------------------- interrupt

    @Test
    @Timeout(30)
    void interruptRestoresFlagTerminatesChildAndJoinsDrains() throws Exception {
        Fixture fixture = corpus();
        MutableClock clock = new MutableClock();
        ScriptedProcess child = ScriptedProcess.blocking(clock);
        ScriptedLauncher launcher = new ScriptedLauncher(List.of(child));
        AtomicReference<Thread> worker = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "runner-deadline-test");
            worker.set(thread);
            return thread;
        });
        try (QualificationRunner runner = runner(fixture, clock, launcher, HOUR,
                new WorkBudget(8, 1_000_000, 100))) {
            AtomicReference<Boolean> flagAfterRun = new AtomicReference<>();
            Future<QualificationReport> future = executor.submit(() -> {
                QualificationReport report = runner.run();
                flagAfterRun.set(Thread.currentThread().isInterrupted());
                return report;
            });
            try {
                child.awaitParked();
                worker.get().interrupt();
                QualificationReport report = future.get(10, TimeUnit.SECONDS);
                assertEquals(1, report.results().size(),
                        "no later entry may start after the interrupt");
                assertEquals(Verdict.SKIPPED_RENDER, report.results().get(0).verdict());
                assertEquals(Boolean.TRUE, flagAfterRun.get(),
                        "the interrupt flag must be restored for the caller");
                assertTrue(child.forciblyDestroyed(), "the interrupted child must be killed");
                assertTrue(child.exited(), "the killed child must report exited");
                assertTrue(child.streamsClosed(),
                        "both stdio drains must have been closed");
                assertEquals(0, child.activeDrainReads(),
                        "both stdio drains must be confirmed joined even on the interrupt path");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    // ---------------------------------------------------------------- work budget

    @Test
    @Timeout(30)
    void entryBudgetStopsStartingLaterWork() throws Exception {
        Fixture fixture = corpus();
        MutableClock clock = new MutableClock();
        ScriptedProcess first = ScriptedProcess.scripted(clock,
                ScriptedProcess.Behavior.EXITS_AFTER, SECOND);
        ScriptedProcess second = ScriptedProcess.scripted(clock,
                ScriptedProcess.Behavior.EXITS_AFTER, SECOND);
        ScriptedLauncher launcher = new ScriptedLauncher(List.of(first, second));
        try (QualificationRunner runner = runner(fixture, clock, launcher, HOUR,
                new WorkBudget(1, 1_000_000, 100))) {
            QualificationReport report = runner.run();
            assertEquals(1, report.results().size(),
                    "the entry cap must stop the run before the second entry");
            assertEquals(Verdict.PASS, report.results().get(0).verdict());
            assertEquals(1, launcher.starts,
                    "no child may be spawned for work the budget cannot afford");
        }
    }

    @Test
    @Timeout(30)
    void pixelBudgetExhaustionFailsTypedAndStopsTheRun() throws Exception {
        Fixture fixture = corpus();
        MutableClock clock = new MutableClock();
        ScriptedProcess first = ScriptedProcess.scripted(clock,
                ScriptedProcess.Behavior.EXITS_AFTER, SECOND);
        ScriptedProcess second = ScriptedProcess.scripted(clock,
                ScriptedProcess.Behavior.EXITS_AFTER, SECOND);
        ScriptedLauncher launcher = new ScriptedLauncher(List.of(first, second));
        try (QualificationRunner runner = runner(fixture, clock, launcher, HOUR,
                new WorkBudget(8, 150, 100))) {
            // Each decode is 64 pixels (8x8); 150 allows entry0 (64+64) and exhausts during
            // entry1's reference decode (64 > 150-128).
            QualificationReport report = runner.run();
            assertEquals(2, report.results().size());
            assertEquals(Verdict.PASS, report.results().get(0).verdict());
            assertEquals(Verdict.SKIPPED_REFERENCE, report.results().get(1).verdict(),
                    "a reference that cannot be decoded within the pixel budget is skipped");
            assertEquals(1, launcher.starts,
                    "the exhausted run must not start the second entry's render");
        }
    }

    // ---------------------------------------------------------------- calibrate

    @Test
    @Timeout(30)
    void calibrateFailsLoudlyOnDeadlineExhaustionAndCommitsNothing() throws Exception {
        Fixture fixture = corpus();
        MutableClock clock = new MutableClock();
        ScriptedProcess child = ScriptedProcess.scripted(clock,
                ScriptedProcess.Behavior.EXITS_AFTER, 30 * SECOND);
        ScriptedLauncher launcher = new ScriptedLauncher(List.of(child));
        Path manifest = fixture.corpusDir().resolve("manifest.json");
        byte[] before = Files.readAllBytes(manifest);
        try (QualificationRunner runner = runner(fixture, clock, launcher, Duration.ZERO,
                new WorkBudget(8, 1_000_000, 100))) {
            DeadlineExceededException failure = assertThrows(
                    DeadlineExceededException.class, runner::calibrate);
            assertEquals(DeadlineExceededException.Kind.TIME, failure.kind());
            assertTrue(failure.getMessage().contains("deadline"),
                    "the diagnostic must name the exhausted bound: " + failure.getMessage());
            assertEquals(0, launcher.starts,
                    "no child may be spawned when the deadline is already spent");
            assertArrayEquals(before, Files.readAllBytes(manifest),
                    "calibration must never commit a partial manifest");
        }
    }

    @Test
    @Timeout(30)
    void zeroDeadlineRunReturnsABoundedEmptyReport() throws Exception {
        Fixture fixture = corpus();
        MutableClock clock = new MutableClock();
        ScriptedLauncher launcher = new ScriptedLauncher(List.of());
        try (QualificationRunner runner = runner(fixture, clock, launcher, Duration.ZERO,
                new WorkBudget(8, 1_000_000, 100))) {
            QualificationReport report = runner.run();
            assertNotNull(report);
            assertEquals(0, report.results().size(),
                    "no entry may start once the total deadline is spent");
            assertEquals(0, launcher.starts);
            assertEquals(0, report.scored());
        }
    }

    // ------------------------------------------------------- negative-scoring deadline

    /**
     * The deliberate-negative loop must re-check the run deadline before every transform and
     * score. Clock calls in the runner for one entry: 1 run deadline, 2 loop check, 3 render
     * remaining, 4-5 scripted child waitFor, 6 drain-join remaining, 7 positive-score
     * remaining; with {@code normalCalls = 7} the next read (the first negative's remaining
     * check) jumps past the deadline, so the entry that rendered and scored successfully must
     * still be skipped instead of PASSing with unmeasured negatives.
     */
    @Test
    @Timeout(30)
    void deadlineExpiryDuringNegativesSkipsTheEntry() throws Exception {
        Fixture fixture = corpus();
        CallCountingClock clock = new CallCountingClock(7);
        ScriptedProcess child = ScriptedProcess.scripted(clock,
                ScriptedProcess.Behavior.EXITS_AFTER, 30 * SECOND);
        ScriptedLauncher launcher = new ScriptedLauncher(List.of(child));
        try (QualificationRunner runner = runner(fixture, clock, launcher,
                Duration.ofSeconds(50), new WorkBudget(8, 1_000_000, 100))) {
            QualificationReport report = runner.run();
            assertEquals(1, report.results().size());
            assertEquals(Verdict.SKIPPED_RENDER, report.results().get(0).verdict(),
                    "a deadline that expires inside the negative scoring must skip the entry");
            assertTrue(child.exited(),
                    "the render itself succeeded; only the post-render scoring was cut short");
        }
    }

    /**
     * Calibration must not commit a manifest after the deadline expired mid-run. Clock calls
     * for one calibrate entry: 1 run deadline, 2-4 first render (remaining + waitFor pair),
     * 5 drain-join remaining, 6 positive-score remaining, 7-9 second render (remaining +
     * waitFor pair), 10 drain-join remaining; with {@code normalCalls = 10} the deadline
     * fires inside the deliberate negatives, so calibrate fails loudly and the manifest is
     * untouched.
     */
    @Test
    @Timeout(30)
    void calibrateDeadlineDuringNegativesCommitsNothing() throws Exception {
        Fixture fixture = corpus();
        CallCountingClock clock = new CallCountingClock(10);
        ScriptedProcess first = ScriptedProcess.scripted(clock,
                ScriptedProcess.Behavior.EXITS_AFTER, SECOND);
        ScriptedProcess second = ScriptedProcess.scripted(clock,
                ScriptedProcess.Behavior.EXITS_AFTER, SECOND);
        ScriptedLauncher launcher = new ScriptedLauncher(List.of(first, second));
        Path manifest = fixture.corpusDir().resolve("manifest.json");
        byte[] before = Files.readAllBytes(manifest);
        try (QualificationRunner runner = runner(fixture, clock, launcher,
                Duration.ofSeconds(10), new WorkBudget(8, 1_000_000, 100))) {
            DeadlineExceededException failure = assertThrows(
                    DeadlineExceededException.class, runner::calibrate);
            assertEquals(DeadlineExceededException.Kind.TIME, failure.kind());
            assertEquals(2, launcher.starts,
                    "both calibration renders complete before the negatives");
            assertArrayEquals(before, Files.readAllBytes(manifest),
                    "calibration must never commit a manifest after the deadline expired");
        }
    }
}
