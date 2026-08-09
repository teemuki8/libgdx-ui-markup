package dev.gdx.markup.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Launches and owns one preview distribution subprocess with {@code --mcp}, mirroring the
 * harness's {@code ReferenceProcess}. The MCP channel is the process's stdio; stderr is pumped
 * for the {@code markup-status} line.
 */
final class PreviewProcess implements AutoCloseable {
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(30);

    private final Process process;
    private final CompletableFuture<String> stderrTail = new CompletableFuture<>();
    private final StringBuilder captured = new StringBuilder();
    private final Thread errorPump;
    private boolean closed;

    private PreviewProcess(Process process) {
        this.process = process;
        errorPump = Thread.ofVirtual().name("preview-process-stderr").start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (captured) {
                        captured.append(line).append('\n');
                    }
                    if (line.startsWith("markup-status:")) {
                        stderrTail.complete(peek());
                    }
                }
            } catch (IOException ignored) {
                // stream closed with the process
            }
            stderrTail.complete(peek());
        });
    }

    /** Returns everything captured on stderr so far (status and runtime registration lines). */
    String capturedStderr() {
        return peek();
    }

    private String peek() {
        synchronized (captured) {
            return captured.toString();
        }
    }

    static PreviewProcess launch() throws Exception {
        String distribution = System.getProperty("markup.preview.distribution");
        String samples = System.getProperty("markup.samples.dir");
        if (distribution == null || distribution.isBlank() || samples == null || samples.isBlank()) {
            throw new IllegalStateException(
                    "Gradle did not provide markup.preview.distribution / markup.samples.dir");
        }
        Path dist = Path.of(distribution);
        Path lib = dist.resolve("lib");
        assertTrue(Files.isDirectory(lib), "preview distribution is built: " + lib);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(
                java,
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                lib.resolve("*").toString(),
                "dev.gdx.markup.preview.PreviewApp",
                "--ui", Path.of(samples, "signin.xml").toString(),
                "--css", Path.of(samples, "signin.gdxcss").toString(),
                "--mcp"));
        Process process = new ProcessBuilder(command).start();
        return new PreviewProcess(process);
    }

    InputStream mcpInput() {
        return process.getInputStream();
    }

    OutputStream mcpOutput() {
        return process.getOutputStream();
    }

    /** Asserts the preview printed a successful status line on stderr. */
    void awaitOkStatus(Duration timeout) throws Exception {
        String tail = stderrTail.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        assertTrue(tail.contains("markup-status: {\"nodes\":10,\"ok\":true}") || tail.contains(
                        "\"ok\":true"),
                "stderr carries a successful markup-status, got: " + tail);
    }

    void awaitCleanExit() throws Exception {
        int exit = process.onExit().get(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).exitValue();
        assertEquals(0, exit, "preview exited cleanly after MCP stdin closed");
        errorPump.join(EXIT_TIMEOUT);
    }

    @Override public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            process.getOutputStream().close();
            if (process.isAlive() && !process.waitFor(EXIT_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while stopping the preview process", interrupted);
        } finally {
            try {
                errorPump.join(EXIT_TIMEOUT);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
