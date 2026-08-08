package dev.gdx.markup.preview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Parent-side owner of one {@link PreviewTestChild} JVM. The parent launches the child with
 * the current test classpath and display, drains bounded stdout/stderr on daemon pump threads,
 * waits on a monotonic/bounded deadline, and owns the termination ladder
 * (terminate → bounded wait → force-kill → final wait) so no child (and no GL/LWJGL thread)
 * ever outlives a test. All waits are bounded {@code waitFor}/{@code join}/{@code wait} calls
 * — no sleeps.
 *
 * <p>Cleanup is interruption-resilient: every bounded wait helper ({@link #awaitProcess},
 * {@link #joinPumps}) records any interrupt it receives and retries the wait with the
 * monotonic remaining time until the child exits or the deadline elapses, reasserting the
 * interrupt once when it completes. An interrupted {@link #await()} therefore still runs the
 * full terminate → bounded wait → force-kill → final wait ladder and joins the pumps, then
 * reports {@link InterruptedException} — never a fake timeout — with the interrupt status
 * preserved.
 */
final class PreviewTestProcess implements AutoCloseable {
    private static final int MAX_CAPTURE_CHARS = 64 * 1024;
    private static final Duration TERMINATE_WAIT = Duration.ofSeconds(5);
    private static final Duration FORCE_KILL_WAIT = Duration.ofSeconds(5);
    private static final Duration PUMP_JOIN_WAIT = Duration.ofSeconds(5);

    /** Cached probe result: whether a child JVM can create an OpenGL window on this host. */
    private static final AtomicReference<Boolean> GL_AVAILABLE = new AtomicReference<>();

    private final Process process;
    private final Duration deadline;
    private final StringBuilder stdout = new StringBuilder();
    private final StringBuilder stderr = new StringBuilder();
    private final Thread stdoutPump;
    private final Thread stderrPump;

    private PreviewTestProcess(Process process, Duration deadline) {
        this.process = process;
        this.deadline = deadline;
        this.stdoutPump = startPump(process.getInputStream(), stdout);
        this.stderrPump = startPump(process.getErrorStream(), stderr);
    }

    /**
     * Launches one child scenario JVM on the current test classpath ({@code java.home},
     * {@code java.class.path}) and display environment.
     */
    static PreviewTestProcess launch(String scenario, Path ui, Path css, Path png,
            Duration deadline) throws IOException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(
                javaBin,
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                PreviewTestChild.class.getName(),
                scenario));
        if (ui != null) {
            command.add("--ui");
            command.add(ui.toString());
        }
        if (css != null) {
            command.add("--css");
            command.add(css.toString());
        }
        if (png != null) {
            command.add("--png");
            command.add(png.toString());
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        return new PreviewTestProcess(process, deadline);
    }

    /**
     * Whether a child JVM on this host can create an OpenGL window, probed once lazily in a
     * dedicated child JVM launched through {@link #launch} with the exact same configuration
     * as scenario children (so any platform JVM flags, e.g. macOS {@code -XstartOnFirstThread},
     * apply identically). GL-less hosts — e.g. Windows CI runners whose WGL backend cannot
     * provide OpenGL — return {@code false}; GL-scenario tests should gate on this with a
     * JUnit assumption so they skip (never fail) where no window can be created.
     */
    static boolean glAvailable() {
        Boolean cached = GL_AVAILABLE.get();
        if (cached != null) {
            return cached;
        }
        boolean available = probeGl();
        GL_AVAILABLE.compareAndSet(null, available);
        return GL_AVAILABLE.get();
    }

    /** Runs the child {@code gl-probe} scenario once and reads its result marker. */
    private static boolean probeGl() {
        try (PreviewTestProcess probe = launch("gl-probe", null, null, null,
                Duration.ofSeconds(60))) {
            int exit = probe.await();
            return exit == 0 && probe.stdout().contains("preview-child: gl-probe ok");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException launchFailure) {
            return false;
        }
    }

    /**
     * Waits for the child to exit on its own within the launch deadline and returns its exit
     * code. On timeout, applies the termination ladder and throws an actionable
     * AssertionError. If this thread is interrupted while waiting, the ladder and pump joins
     * still complete interruption-resiliently and {@link InterruptedException} is rethrown
     * with the interrupt status preserved.
     */
    int await() throws InterruptedException {
        try {
            if (process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
                joinPumps();
                return process.exitValue();
            }
        } catch (InterruptedException interrupted) {
            // The child may be stuck; complete the ladder and pump joins before reporting. The
            // wait helpers retry on repeated interrupts with their monotonic deadlines, so this
            // cleanup cannot be aborted; reassert the consumed interrupt for the caller.
            try {
                terminateAndJoin();
            } finally {
                Thread.currentThread().interrupt();
            }
            throw new InterruptedException("interrupted while awaiting preview child");
        }
        terminateAndJoin();
        throw new AssertionError("preview child did not exit within " + deadline
                + "; terminated (exit " + process.exitValue() + "); stderr: " + stderr);
    }

    /**
     * Bounded wait until the child's captured stdout contains {@code fragment} (observable
     * child start); the stdout pump signals this monitor as it appends.
     */
    boolean awaitStdoutContaining(String fragment, Duration timeout) throws InterruptedException {
        synchronized (stdout) {
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            while (!stdout.toString().contains(fragment)) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(stdout, remaining);
            }
            return true;
        }
    }

    /** Bounded stdout captured so far. */
    String stdout() {
        return stdout.toString();
    }

    /** Bounded stderr captured so far. */
    String stderr() {
        return stderr.toString();
    }

    /** Whether the child process is still running. */
    boolean isAlive() {
        return process.isAlive();
    }

    @Override public void close() {
        // Interruption-resilient and never silent: every wait retries through interrupts with
        // its monotonic deadline, and a child that survives the ladder raises an AssertionError
        // (added as suppressed when the test body already failed).
        if (process.isAlive()) {
            terminateLadder();
        }
        joinPumps();
    }

    private void terminateAndJoin() {
        terminateLadder();
        joinPumps();
    }

    /** Terminate → bounded wait → force-kill → final wait; throws if the child survives. */
    private void terminateLadder() {
        process.destroy();
        if (awaitProcess(TERMINATE_WAIT)) {
            return;
        }
        process.destroyForcibly();
        if (!awaitProcess(FORCE_KILL_WAIT)) {
            throw new AssertionError("preview child survived terminate/force-kill");
        }
    }

    /**
     * Interruption-resilient bounded wait for child exit: retries {@code waitFor} after any
     * interrupt with the monotonic remaining time, so repeated interrupts can never abort the
     * wait before its real deadline; reasserts a consumed interrupt once, when the wait
     * completes. Returns {@code true} iff the process exited within {@code wait} — the same
     * polarity as {@code Process.waitFor}, never a live-state report.
     */
    private boolean awaitProcess(Duration wait) {
        long deadlineNanos = System.nanoTime() + wait.toNanos();
        boolean interrupted = false;
        while (true) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                boolean exited = !process.isAlive();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return exited;
            }
            try {
                boolean exited = process.waitFor(remaining, TimeUnit.NANOSECONDS);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return exited;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
    }

    private void joinPumps() {
        for (Thread pump : new Thread[] {stdoutPump, stderrPump}) {
            joinPump(pump);
        }
    }

    /**
     * Interruption-resilient bounded join: retries the join after any interrupt with the
     * monotonic remaining time until the pump dies or the join deadline elapses, then
     * reasserts a consumed interrupt once.
     */
    private void joinPump(Thread pump) {
        long deadlineNanos = System.nanoTime() + PUMP_JOIN_WAIT.toNanos();
        boolean interrupted = false;
        while (true) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                TimeUnit.NANOSECONDS.timedJoin(pump, remaining);
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Daemon pump that drains the child stream, capping the captured text (bounded output)
     * and notifying the capture monitor so {@link #awaitStdoutContaining} can observe child
     * output.
     */
    private static Thread startPump(InputStream in, StringBuilder capture) {
        Thread pump = new Thread(() -> {
            byte[] buffer = new byte[4096];
            try {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    synchronized (capture) {
                        if (capture.length() < MAX_CAPTURE_CHARS) {
                            int room = MAX_CAPTURE_CHARS - capture.length();
                            capture.append(new String(buffer, 0, Math.min(read, room),
                                    StandardCharsets.UTF_8));
                        }
                        capture.notifyAll();
                    }
                }
            } catch (IOException ignored) {
                // Process exit closes the streams; capture what arrived before that.
            }
        }, "preview-child-pump");
        pump.setDaemon(true);
        pump.start();
        return pump;
    }
}
