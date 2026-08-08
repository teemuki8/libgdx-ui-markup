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

    private static int countOccurrences(String text, String fragment) {
        int count = 0;
        for (int index = text.indexOf(fragment); index >= 0;
                index = text.indexOf(fragment, index + fragment.length())) {
            count++;
        }
        return count;
    }
}
