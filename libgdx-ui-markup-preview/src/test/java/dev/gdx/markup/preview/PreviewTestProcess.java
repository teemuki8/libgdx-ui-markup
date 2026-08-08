package dev.gdx.markup.preview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Parent-side owner of one {@link PreviewTestChild} JVM. The parent launches the child with
 * the current test classpath and display, drains bounded stdout/stderr on daemon pump threads,
 * waits on a monotonic/bounded deadline, and owns the termination ladder
 * (terminate → bounded wait → force-kill → final wait) so no child (and no GL/LWJGL thread)
 * ever outlives a test. All waits are bounded {@code waitFor}/{@code join} calls — no sleeps.
 */
final class PreviewTestProcess implements AutoCloseable {
    private static final int MAX_CAPTURE_CHARS = 64 * 1024;
    private static final Duration TERMINATE_WAIT = Duration.ofSeconds(5);
    private static final Duration FORCE_KILL_WAIT = Duration.ofSeconds(5);
    private static final Duration PUMP_JOIN_WAIT = Duration.ofSeconds(5);

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
     * Waits for the child to exit on its own within the launch deadline and returns its exit
     * code; on timeout, applies the termination ladder and throws an actionable AssertionError.
     */
    int await() throws InterruptedException {
        try {
            if (process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
                joinPumps();
                return process.exitValue();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            terminateLadder();
            joinPumps();
            throw interrupted;
        }
        terminateLadder();
        joinPumps();
        throw new AssertionError("preview child did not exit within " + deadline
                + "; terminated (exit " + process.exitValue() + "); stderr: " + stderr);
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
        if (process.isAlive()) {
            try {
                terminateLadder();
            } catch (AssertionError survivor) {
                // The test is already failing; the ladder made its best effort.
            }
        }
        joinPumps();
    }

    /** Terminate → bounded wait → force-kill → final wait; throws if the child survives. */
    private void terminateLadder() {
        process.destroy();
        boolean dead = awaitProcess(TERMINATE_WAIT);
        if (!dead) {
            process.destroyForcibly();
            dead = awaitProcess(FORCE_KILL_WAIT);
        }
        if (!dead || process.isAlive()) {
            throw new AssertionError("preview child survived terminate/force-kill");
        }
    }

    private boolean awaitProcess(Duration wait) {
        try {
            return process.waitFor(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return process.isAlive();
        }
    }

    private void joinPumps() {
        for (Thread pump : new Thread[] {stdoutPump, stderrPump}) {
            try {
                pump.join(PUMP_JOIN_WAIT.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Daemon pump that drains the child stream, capping the captured text (bounded output). */
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
