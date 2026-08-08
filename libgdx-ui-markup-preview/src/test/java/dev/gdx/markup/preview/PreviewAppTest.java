package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.markup.core.MarkupDocument;
import dev.gdx.markup.core.MarkupException;
import dev.gdx.markup.core.MarkupParser;
import dev.gdx.markup.core.style.CssDocument;
import dev.gdx.markup.core.style.CssParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GL-free tests for the exact parser calls the preview rebuild path makes: default-limit
 * parsers reading bounded files from disk. Oversized files must fail with a typed
 * {@code TOO_LARGE} diagnostic before any content is materialized, and exact-limit UTF-8 files
 * must parse, so the preview never allocates unbounded Strings from disk.
 */
final class PreviewAppTest {
    @TempDir
    Path tempDir;

    @Test
    void oversizedUiFileFailsTooLargeThroughPreviewParserCall() throws Exception {
        // The final byte starts a two-byte UTF-8 sequence: a decode-first implementation (like
        // the previous Files.readString) would surface an IOException here, not typed TOO_LARGE.
        byte[] over = new byte[MarkupParser.MAX_INPUT_BYTES + 1];
        Arrays.fill(over, (byte) 'x');
        over[over.length - 1] = (byte) 0xC3;
        Path ui = tempDir.resolve("ui.xml");
        Files.write(ui, over);
        MarkupException failure = assertThrows(MarkupException.class,
                () -> new MarkupParser().parse(ui));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("limit"));
    }

    @Test
    void oversizedCssFileFailsTooLargeThroughPreviewParserCall() throws Exception {
        byte[] over = new byte[CssParser.MAX_INPUT_BYTES + 1];
        Arrays.fill(over, (byte) 'x');
        over[over.length - 1] = (byte) 0xC3;
        Path css = tempDir.resolve("ui.css");
        Files.write(css, over);
        MarkupException failure = assertThrows(MarkupException.class,
                () -> new CssParser().parse(css));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("limit"));
    }

    @Test
    void exactLimitUtf8UiAndCssFilesParseThroughPreviewParserCalls() throws Exception {
        String xml = "<ui><!--" + "x".repeat(MarkupParser.MAX_INPUT_BYTES - 16) + "--></ui>";
        Path ui = tempDir.resolve("ui.xml");
        Files.write(ui, xml.getBytes(StandardCharsets.UTF_8));
        MarkupDocument document = new MarkupParser().parse(ui);
        assertEquals("ui", document.root().tag());

        String css = "/*" + "x".repeat(CssParser.MAX_INPUT_BYTES - 4) + "*/";
        Path cssFile = tempDir.resolve("ui.css");
        Files.write(cssFile, css.getBytes(StandardCharsets.UTF_8));
        CssDocument styles = new CssParser().parse(cssFile);
        assertTrue(styles.rules().isEmpty());
    }

    @Test
    void truncatedMultibyteUiFileFailsWithTypedDiagnosticThroughPreviewParserCall()
            throws Exception {
        byte[] truncated = new byte[] {'<', 'u', 'i', '/', '>', (byte) 0xC3};
        Path ui = tempDir.resolve("ui.xml");
        Files.write(ui, truncated);
        MarkupException failure = assertThrows(MarkupException.class,
                () -> new MarkupParser().parse(ui));
        assertEquals(MarkupException.Kind.MALFORMED_XML, failure.kind());
    }
}
