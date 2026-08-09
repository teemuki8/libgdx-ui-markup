package dev.gdx.markup.preview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /** Cached probe outcome: whether a child JVM can create an OpenGL window on this host. */
    private static final AtomicReference<Boolean> GL_AVAILABLE = new AtomicReference<>();

    /** Cached probe failure; rethrown by every gated test so a probe regression FAILS (never
     * silently skips) the GL scenarios. */
    private static final AtomicReference<AssertionError> GL_PROBE_FAILURE = new AtomicReference<>();

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
     * Extra JVM flags the child requires on the current platform before any other option.
     * macOS forbids GLFW/AppKit window creation unless the main thread is the process's first
     * thread, so every child (including the {@code gl-probe}) must run with
     * {@code -XstartOnFirstThread} there; other platforms need nothing extra. The same
     * platform-flag contract is pinned by the production launcher
     * ({@code PreviewProcessLauncher.platformJvmFlags}).
     */
    static List<String> childJvmFlags(String osName) {
        if (osName != null && osName.toLowerCase(Locale.ROOT).contains("mac")) {
            return List.of("-XstartOnFirstThread");
        }
        return List.of();
    }

    /**
     * Assembles the child JVM command: {@code java <platform-flags> <jvmFlags> -cp
     * <classpath> <mainClass> <programArgs...>}. Platform flags (macOS
     * {@code -XstartOnFirstThread} only) are inserted before {@code -cp} and the main class,
     * in the deterministic position the JVM requires. Package-visible for deterministic
     * command-order tests.
     */
    static List<String> command(String javaBin, List<String> jvmFlags, String classpath,
            String mainClass, List<String> programArgs, String osName) {
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.addAll(childJvmFlags(osName));
        command.addAll(jvmFlags);
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass);
        command.addAll(programArgs);
        return command;
    }

    /**
     * Launches one child scenario JVM on the current test classpath ({@code java.home},
     * {@code java.class.path}) and display environment, with the platform JVM flags (e.g.
     * macOS {@code -XstartOnFirstThread}) before the classpath and the main class.
     */
    static PreviewTestProcess launch(String scenario, Path ui, Path css, Path png,
            Duration deadline) throws IOException {
        return launch(scenario, ui, css, png, null, deadline);
    }

    static PreviewTestProcess launch(String scenario, Path ui, Path css, Path png, Path skin,
            Duration deadline) throws IOException {
        List<String> programArgs = new ArrayList<>();
        programArgs.add(scenario);
        if (ui != null) {
            programArgs.add("--ui");
            programArgs.add(ui.toString());
        }
        if (css != null) {
            programArgs.add("--css");
            programArgs.add(css.toString());
        }
        if (png != null) {
            programArgs.add("--png");
            programArgs.add(png.toString());
        }
        if (skin != null) {
            programArgs.add("--skin");
            programArgs.add(skin.toString());
        }
        List<String> command = command(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                List.of("--enable-native-access=ALL-UNNAMED"),
                System.getProperty("java.class.path"),
                PreviewTestChild.class.getName(),
                programArgs,
                System.getProperty("os.name"));
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        return new PreviewTestProcess(process, deadline);
    }

    /**
     * Whether a child JVM on this host can create an OpenGL window, probed once lazily in a
     * dedicated child JVM launched through {@link #launch} with the exact same configuration
     * as scenario children (so any platform JVM flags, e.g. macOS {@code -XstartOnFirstThread},
     * apply identically). Returns {@code false} ONLY for the exact hosted-Windows
     * no-OpenGL-driver signature; every unexpected probe outcome (non-zero exit, missing or
     * lookalike marker, unavailable-on-a-non-Windows-host, launch failure, interruption) is
     * rethrown as an {@link AssertionError} so the gated tests FAIL — a regression must never
     * silently skip GL coverage.
     */
    static boolean glAvailable() {
        AssertionError failed = GL_PROBE_FAILURE.get();
        if (failed != null) {
            throw failed;
        }
        Boolean cached = GL_AVAILABLE.get();
        if (cached != null) {
            return cached;
        }
        boolean available;
        try {
            available = runGlProbe();
        } catch (AssertionError failure) {
            GL_PROBE_FAILURE.compareAndSet(null, failure);
            throw failure;
        }
        GL_AVAILABLE.compareAndSet(null, available);
        return GL_AVAILABLE.get();
    }

    /** Runs the child {@code gl-probe} scenario once and classifies its outcome. */
    private static boolean runGlProbe() {
        try (PreviewTestProcess probe = launch("gl-probe", null, null, null,
                Duration.ofSeconds(60))) {
            int exit;
            try {
                exit = probe.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while running the GL probe child",
                        interrupted);
            }
            return classifyGlProbe(exit, probe.stdout(), probe.stderr(), isWindows());
        } catch (IOException launchFailure) {
            throw new AssertionError("failed to launch the GL probe child: "
                    + launchFailure.getMessage(), launchFailure);
        }
    }

    /** Pure classification of a completed {@code gl-probe} child (unit-tested). Green: the
     * ok marker with exit 0 means the host can create a GL window; the exact structured
     * Windows no-OpenGL-driver marker with exit 0 on a Windows host, or the exact structured
     * macOS headless NSGL pixel-format marker with exit 0 on a macOS host, means the gated
     * tests skip. Red (throws, never skips): any non-zero exit, a missing marker, an
     * unavailable marker on the wrong host, or an unavailable marker that is not the exact
     * structured one. */
    static boolean classifyGlProbe(int exitCode, String stdout, String stderr, boolean windows) {
        if (exitCode != 0) {
            // The child prints JDK/LWJGL warnings first and its own 'preview-child: failure'
            // diagnosis last, so surface the stderr tail (where the GLFW error lives) plus
            // the captured length; a head-only view hid the real cause in hosted CI.
            throw new AssertionError("GL probe child exited " + exitCode
                    + " (expected 0); stderr (" + stderr.length() + " chars) tail: "
                    + boundedTail(stderr));
        }
        if (stdout.contains(PreviewTestChild.GL_PROBE_OK)) {
            return true;
        }
        if (stdout.contains(PreviewTestChild.GL_PROBE_WINDOWS_UNAVAILABLE)) {
            if (!windows) {
                throw new AssertionError("GL probe reported the Windows no-OpenGL-driver"
                        + " marker on a non-Windows host; stdout: " + bounded(stdout));
            }
            return false;
        }
        if (stdout.contains(PreviewTestChild.GL_PROBE_MACOS_UNAVAILABLE)) {
            if (!isMac()) {
                throw new AssertionError("GL probe reported the macOS headless pixel-format"
                        + " marker on a non-macOS host; stdout: " + bounded(stdout));
            }
            return false;
        }
        throw new AssertionError("GL probe child produced no recognized marker; stdout: "
                + bounded(stdout) + " stderr tail: " + boundedTail(stderr));
    }

    /** Whether this host is macOS. */
    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("mac");
    }

    /** Whether this host is Windows. */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    /** Bounds a captured stream for an assertion message (head view). */
    private static String bounded(String text) {
        return text.length() <= 400 ? text : text.substring(0, 400) + "…";
    }

    /** Bounds a captured stream to its tail — where the child's own failure line lands. */
    private static String boundedTail(String text) {
        return text.length() <= 800 ? text : "…" + text.substring(text.length() - 800);
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
