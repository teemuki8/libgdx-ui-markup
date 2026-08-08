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

    @Test
    @Timeout(120)
    void terminalCleanupCloseIsBestEffortAggregatingAndIdempotent() throws Exception {
        Path ui = tempDir.resolve("mcp-close-failure.xml");
        Path css = tempDir.resolve("mcp-close-failure.css");
        Files.writeString(css, "/* parent placeholder */", StandardCharsets.UTF_8);
        try (PreviewTestProcess child = PreviewTestProcess.launch(
                "mcp-close-failure", ui, css, null, Duration.ofSeconds(60))) {
            int exit = child.await();
            assertEquals(0, exit, "child exit code; stderr: " + child.stderr());
            assertTrue(child.stdout().contains("preview-child: mcp-close-failure ok"),
                    "child ok line; stdout: " + child.stdout() + " stderr: " + child.stderr());
            String stderr = child.stderr();
            assertTrue(stderr.contains("\"kind\":\"TERMINAL\""),
                    "the terminal state publishes a typed TERMINAL status; stderr: " + stderr);
            assertTrue(stderr.contains("failed to close runtime registration"),
                    "the TERMINAL status carries the runtime-owner close failure; stderr: "
                            + stderr);
            assertTrue(stderr.contains("failed to close MCP server"),
                    "the TERMINAL status carries the server close failure; stderr: " + stderr);
            assertTrue(stderr.contains("injected-runtime-owner-close-failure"),
                    "the TERMINAL status carries the actual injected runtime-owner cause text, "
                            + "not only the generic wrapper; stderr: " + stderr);
            assertTrue(stderr.contains("injected-server-close-failure"),
                    "the TERMINAL status carries the actual injected server cause text, not "
                            + "only the generic wrapper; stderr: " + stderr);
        }
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    @Test
    @Timeout(120)
    void terminalCauseChainIsNestedCycleSafeAndBounded() throws Exception {
        Path ui = tempDir.resolve("mcp-cause-chain.xml");
        Path css = tempDir.resolve("mcp-cause-chain.css");
        Files.writeString(css, "/* parent placeholder */", StandardCharsets.UTF_8);
        try (PreviewTestProcess child = PreviewTestProcess.launch(
                "mcp-cause-chain", ui, css, null, Duration.ofSeconds(60))) {
            int exit = child.await();
            assertEquals(0, exit, "child exit code; stderr: " + child.stderr());
            assertTrue(child.stdout().contains("preview-child: mcp-cause-chain ok"),
                    "child ok line; stdout: " + child.stdout() + " stderr: " + child.stderr());
            String stderr = child.stderr();
            assertTrue(stderr.contains("\"kind\":\"TERMINAL\""),
                    "the terminal state publishes a typed TERMINAL status; stderr: " + stderr);
            assertTrue(stderr.contains("injected-root-cause"),
                    "the TERMINAL status carries the root cause text; stderr: " + stderr);
            assertTrue(stderr.contains("injected-mid-cause"),
                    "the TERMINAL status carries the nested mid cause text; stderr: " + stderr);
            assertTrue(stderr.contains("injected-deepest-cause"),
                    "the TERMINAL status carries the deepest nested cause text through the "
                            + "wrapper chain (getCause() traversal, not only suppressed); stderr: "
                            + stderr);
            assertEquals(1, countOccurrences(stderr, "injected-deepest-cause"),
                    "the cyclic cause chain terminates and prints each cause exactly once "
                            + "(cycle-safe, bounded); stderr: " + stderr);
            assertTrue(stderr.length() < 8192,
                    "the terminal output stays bounded; stderr length: " + stderr.length());
        }
        assertNull(Gdx.app, "the parent test JVM never creates a GL backend");
    }

    @Test
    @Timeout(120)
    void stagedConstructorCleanupClosesEveryAcquiredResource() throws Exception {
        Path ui = tempDir.resolve("mcp-init-failure.xml");
        Path css = tempDir.resolve("mcp-init-failure.css");
        Files.writeString(css, "/* parent placeholder */", StandardCharsets.UTF_8);
        try (PreviewTestProcess child = PreviewTestProcess.launch(
                "mcp-init-failure", ui, css, null, Duration.ofSeconds(60))) {
            int exit = child.await();
            assertEquals(0, exit, "child exit code; stderr: " + child.stderr());
            assertTrue(child.stdout().contains("preview-child: mcp-init-failure ok"),
                    "child ok line; stdout: " + child.stdout() + " stderr: " + child.stderr());
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
