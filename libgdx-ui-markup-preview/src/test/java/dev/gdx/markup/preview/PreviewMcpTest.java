package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Gdx;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Transactional runtime attachment tests for the preview's {@code --mcp} mode. Each scenario
 * runs in a dedicated child JVM ({@link PreviewTestChild}), so no GL/LWJGL thread or
 * {@code Gdx} global ever lives in this (parent) test JVM. The child asserts the
 * {@code attachRuntime} contract — a live owner on success, the last-good registration
 * preserved or reinstalled on failure, and no leaked candidate handles in runtime frames — and
 * the parent asserts the child's bounded output.
 */
final class PreviewMcpTest {
    @TempDir
    Path tempDir;

    @Test
    @Timeout(120)
    void candidateRuntimeFailureReinstallsOrPreservesLastGoodRegistration() throws Exception {
        Path ui = tempDir.resolve("mcp-attach.xml");
        Path css = tempDir.resolve("mcp-attach.css");
        Files.writeString(css, "/* parent placeholder */", StandardCharsets.UTF_8);
        try (PreviewTestProcess child = PreviewTestProcess.launch(
                "mcp-attach", ui, css, null, Duration.ofSeconds(60))) {
            int exit = child.await();
            assertEquals(0, exit, "child exit code; stderr: " + child.stderr());
            assertTrue(child.stdout().contains("preview-child: mcp-attach ok"),
                    "child ok line; stdout: " + child.stdout() + " stderr: " + child.stderr());
            // Two successful attaches (initial build + recovery) each report one entity.
            assertTrue(countOccurrences(child.stderr(), "\"entities\":1") >= 2,
                    "each committed attach reports its entity count; stderr: " + child.stderr());
        }
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    @Test
    @Timeout(120)
    void stageSwapFailureRestoresLastGoodRuntimeAndScene() throws Exception {
        Path ui = tempDir.resolve("mcp-swap-failure.xml");
        Path css = tempDir.resolve("mcp-swap-failure.css");
        Files.writeString(css, "/* parent placeholder */", StandardCharsets.UTF_8);
        try (PreviewTestProcess child = PreviewTestProcess.launch(
                "mcp-swap-failure", ui, css, null, Duration.ofSeconds(60))) {
            int exit = child.await();
            assertEquals(0, exit, "child exit code; stderr: " + child.stderr());
            assertTrue(child.stdout().contains("preview-child: mcp-swap-failure ok"),
                    "child ok line; stdout: " + child.stdout() + " stderr: " + child.stderr());
        }
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    @Test
    @Timeout(120)
    void retirementFailureEntersTerminalState() throws Exception {
        Path ui = tempDir.resolve("retire-failure.xml");
        Path css = tempDir.resolve("retire-failure.css");
        Files.writeString(css, "/* parent placeholder */", StandardCharsets.UTF_8);
        try (PreviewTestProcess child = PreviewTestProcess.launch(
                "retire-failure", ui, css, null, Duration.ofSeconds(60))) {
            int exit = child.await();
            assertEquals(0, exit, "child exit code; stderr: " + child.stderr());
            assertTrue(child.stdout().contains("preview-child: retire-failure ok"),
                    "child ok line; stdout: " + child.stdout() + " stderr: " + child.stderr());
            String stderr = child.stderr();
            assertTrue(stderr.contains("\"kind\":\"TERMINAL\""),
                    "the retirement failure publishes a typed TERMINAL status; stderr: " + stderr);
            assertTrue(stderr.contains("injected-retire-failure"),
                    "the TERMINAL status carries the primary retirement failure; stderr: " + stderr);
            assertTrue(stderr.contains("duplicate static entity"),
                    "the TERMINAL status carries the reinstatement cause (a partial-close-unsafe "
                            + "duplicate is not treated as proof of an intact old registration); "
                            + "stderr: " + stderr);
        }
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    @Test
    @Timeout(120)
    void restoreFailureEntersTerminalState() throws Exception {
        Path ui = tempDir.resolve("restore-failure.xml");
        Path css = tempDir.resolve("restore-failure.css");
        Files.writeString(css, "/* parent placeholder */", StandardCharsets.UTF_8);
        try (PreviewTestProcess child = PreviewTestProcess.launch(
                "restore-failure", ui, css, null, Duration.ofSeconds(60))) {
            int exit = child.await();
            assertEquals(0, exit, "child exit code; stderr: " + child.stderr());
            assertTrue(child.stdout().contains("preview-child: restore-failure ok"),
                    "child ok line; stdout: " + child.stdout() + " stderr: " + child.stderr());
            String stderr = child.stderr();
            assertTrue(stderr.contains("\"kind\":\"TERMINAL\""),
                    "the restore failure publishes a typed TERMINAL status; stderr: " + stderr);
            assertTrue(stderr.contains("injected-restore-failure"),
                    "the TERMINAL status carries the reinstatement cause; stderr: " + stderr);
        }
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    @Test
    @Timeout(120)
    void candidateCloseAndRestoreFailuresEnterTerminalState() throws Exception {
        Path ui = tempDir.resolve("mcp-cleanup-failure.xml");
        Path css = tempDir.resolve("mcp-cleanup-failure.css");
        Files.writeString(css, "/* parent placeholder */", StandardCharsets.UTF_8);
        try (PreviewTestProcess child = PreviewTestProcess.launch(
                "mcp-cleanup-failure", ui, css, null, Duration.ofSeconds(60))) {
            int exit = child.await();
            assertEquals(0, exit, "child exit code; stderr: " + child.stderr());
            assertTrue(child.stdout().contains("preview-child: mcp-cleanup-failure ok"),
                    "child ok line; stdout: " + child.stdout() + " stderr: " + child.stderr());
            String stderr = child.stderr();
            assertTrue(stderr.contains("\"kind\":\"TERMINAL\""),
                    "exceptional cleanup failures publish a typed TERMINAL status; stderr: "
                            + stderr);
            assertTrue(stderr.contains("injected-candidate-close-failure"),
                    "the TERMINAL status carries the candidate-close failure; stderr: " + stderr);
            assertTrue(stderr.contains("injected-restore-failure"),
                    "the TERMINAL status carries the reinstatement failure; stderr: " + stderr);
        }
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    private static int countOccurrences(String text, String fragment) {
        int count = 0;
        for (int index = text.indexOf(fragment); index >= 0;
                index = text.indexOf(fragment, index + fragment.length())) {
            count++;
        }
        return count;
    }
}
