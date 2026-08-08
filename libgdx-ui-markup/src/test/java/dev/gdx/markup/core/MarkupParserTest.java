package dev.gdx.markup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MarkupParserTest {
    private final MarkupParser parser = new MarkupParser();

    @TempDir
    Path tempDir;

    @Test
    void parsesValidDocumentIntoTree() {
        MarkupDocument document = parser.parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ui>
                  <table id="panel" class="panel wide">
                    <row/>
                    <button id="save" class="primary" text="Save" name="Save" data-role="action"
                        disabled="false" align="center center"/>
                  </table>
                </ui>
                """);
        Element root = document.root();
        assertEquals("ui", root.tag());
        assertEquals(1, root.children().size());

        Element table = root.children().get(0);
        assertEquals("table", table.tag());
        assertEquals("panel", table.id());
        assertEquals(List.of("panel", "wide"), table.classes());
        assertEquals(2, table.children().size());

        Element row = table.children().get(0);
        assertEquals("row", row.tag());
        assertTrue(row.isLeaf());
        assertNull(row.text());

        Element button = table.children().get(1);
        assertEquals("button", button.tag());
        assertEquals("save", button.id());
        assertEquals("Save", button.name());
        assertEquals("Save", button.text());
        assertEquals(List.of("primary"), button.classes());
        assertEquals("action", button.attr("data-role"));
        assertEquals("center center", button.attr("align"));
        assertEquals("false", button.attr("disabled"));
    }

    @Test
    void capturesTextFromContentPreferringContentOverAttribute() {
        MarkupDocument fromContent = parser.parse(
                "<ui><label>Hello world</label></ui>");
        assertEquals("Hello world", fromContent.root().children().get(0).text());

        MarkupDocument fromAttribute = parser.parse(
                "<ui><label text=\"From attr\"/></ui>");
        assertEquals("From attr", fromAttribute.root().children().get(0).text());

        MarkupDocument both = parser.parse(
                "<ui><label text=\"Attr\">Content</label></ui>");
        assertEquals("Content", both.root().children().get(0).text());
    }

    @Test
    void unknownTagFailsWithKindPathAndLocation() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse("""
                <ui>
                  <table>
                    <bogus/>
                  </table>
                </ui>
                """));
        assertEquals(MarkupException.Kind.UNKNOWN_TAG, failure.kind());
        assertEquals("ui/table/bogus", failure.elementPath());
        assertEquals(3, failure.line());
        // JDK SAX reports the column just past the scanned start tag; assert presence, not exact.
        assertTrue(failure.column() >= 5);
    }

    @Test
    void unknownAttributeFailsOnSpecificTag() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "<ui><row bogus=\"1\"/></ui>"));
        assertEquals(MarkupException.Kind.UNKNOWN_ATTRIBUTE, failure.kind());
        assertEquals("ui/row", failure.elementPath());
    }

    @Test
    void duplicateIdFailsAcrossTheWholeDocument() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse("""
                <ui>
                  <table id="panel"/>
                  <table id="panel"/>
                </ui>
                """));
        assertEquals(MarkupException.Kind.DUPLICATE_ID, failure.kind());
        assertTrue(failure.getMessage().contains("panel"));
        assertEquals("ui/table[1]", failure.elementPath());
    }

    @Test
    void missingRequiredAttributeFails() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "<ui><selectbox/></ui>"));
        assertEquals(MarkupException.Kind.MISSING_ATTRIBUTE, failure.kind());
        assertTrue(failure.getMessage().contains("items"));

        MarkupException window = assertThrows(MarkupException.class, () -> parser.parse(
                "<ui><window/></ui>"));
        assertEquals(MarkupException.Kind.MISSING_ATTRIBUTE, window.kind());
        assertTrue(window.getMessage().contains("title"));
    }

    @Test
    void invalidBooleanAttributeValueFails() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "<ui><button disabled=\"maybe\"/></ui>"));
        assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
        assertTrue(failure.getMessage().contains("disabled"));
    }

    @Test
    void invalidAlignTokenFails() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "<ui><button align=\"diagonal\"/></ui>"));
        assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
    }

    @Test
    void invalidAxisValueFails() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "<ui><button expand=\"z\"/></ui>"));
        assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
    }

    @Test
    void invalidPaddingFails() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "<ui><button pad=\"1,2\"/></ui>"));
        assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
    }

    @Test
    void doctypeDeclarationIsRejected() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "<!DOCTYPE ui [<!ENTITY x \"boom\">]><ui/>"));
        assertEquals(MarkupException.Kind.MALFORMED_XML, failure.kind());
    }

    @Test
    void externalEntityReferenceIsRejected() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "<!DOCTYPE ui SYSTEM \"http://example.invalid/evil.dtd\"><ui>&xxe;</ui>"));
        assertEquals(MarkupException.Kind.MALFORMED_XML, failure.kind());
    }

    @Test
    void oversizedInputIsRejected() {
        String xml = "<ui>" + " ".repeat(MarkupParser.MAX_INPUT_BYTES) + "</ui>";
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(xml));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("limit"));
    }

    @Test
    void tooManyElementsAreRejected() {
        StringBuilder xml = new StringBuilder("<ui>");
        for (int index = 0; index < MarkupParser.MAX_ELEMENTS + 1; index++) {
            xml.append("<row/>");
        }
        xml.append("</ui>");
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                xml.toString()));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("element"));
    }

    @Test
    void excessiveDepthIsRejected() {
        StringBuilder xml = new StringBuilder();
        for (int index = 0; index < MarkupParser.MAX_DEPTH + 2; index++) {
            xml.append("<table>");
        }
        xml.append("<row/>");
        for (int index = 0; index < MarkupParser.MAX_DEPTH + 2; index++) {
            xml.append("</table>");
        }
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                xml.toString()));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("nesting"));
    }

    @Test
    void oversizedAttributeIsRejected() {
        String huge = "x".repeat(MarkupParser.MAX_ATTRIBUTE_VALUE + 1);
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "<ui><button text=\"" + huge + "\"/></ui>"));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("attribute"));
    }

    @Test
    void oversizedTextContentIsRejected() {
        String huge = "x".repeat(MarkupParser.MAX_TEXT + 1);
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "<ui><label>" + huge + "</label></ui>"));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("text"));
    }

    @Test
    void mixedContentIsRejected() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "<ui><table>stray text<row/></table></ui>"));
        assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
        assertTrue(failure.getMessage().contains("mixed"));
    }

    @Test
    void rootMustBeUiOrTable() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "<button/>"));
        assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
        assertTrue(failure.getMessage().contains("root"));
    }

    @Test
    void tableRootIsAccepted() {
        MarkupDocument document = parser.parse("<table id=\"root\"><row/></table>");
        assertEquals("table", document.root().tag());
        assertEquals("root", document.root().id());
    }

    @Test
    void dataAttributesRequireValidSuffix() {
        parser.parse("<ui><button data-save-action=\"x\"/></ui>");
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "<ui><button data-a.b=\"x\"/></ui>"));
        assertEquals(MarkupException.Kind.UNKNOWN_ATTRIBUTE, failure.kind());
    }

    @Test
    void elementPathCountsSameTagSiblings() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse("""
                <ui>
                  <table>
                    <button id="a" bogus="1"/>
                    <button id="b"/>
                  </table>
                </ui>
                """));
        assertEquals(MarkupException.Kind.UNKNOWN_ATTRIBUTE, failure.kind());
        assertEquals("ui/table/button", failure.elementPath());
    }

    @Test
    void secondSameTagSiblingIsIndexed() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse("""
                <ui>
                  <table>
                    <button id="a"/>
                    <button id="b" bogus="1"/>
                  </table>
                </ui>
                """));
        assertEquals("ui/table/button[1]", failure.elementPath());
        assertEquals(4, failure.line());
    }

    @Test
    void elementPathsAreScopedPerParent() {
        MarkupException firstTableFirstButton = assertThrows(MarkupException.class,
                () -> parser.parse("""
                        <ui>
                          <table>
                            <button id="a" bogus="1"/>
                          </table>
                          <table>
                            <button id="b"/>
                          </table>
                        </ui>
                        """));
        assertEquals("ui/table/button", firstTableFirstButton.elementPath());

        MarkupException firstTableSecondButton = assertThrows(MarkupException.class,
                () -> parser.parse("""
                        <ui>
                          <table>
                            <button id="a"/>
                            <button id="b" bogus="1"/>
                          </table>
                          <table>
                            <button id="c"/>
                          </table>
                        </ui>
                        """));
        assertEquals("ui/table/button[1]", firstTableSecondButton.elementPath());

        MarkupException secondTableFirstButton = assertThrows(MarkupException.class,
                () -> parser.parse("""
                        <ui>
                          <table>
                            <button id="a"/>
                          </table>
                          <table>
                            <button id="b" bogus="1"/>
                          </table>
                        </ui>
                        """));
        assertEquals("ui/table[1]/button", secondTableFirstButton.elementPath());

        MarkupException secondTableSecondButton = assertThrows(MarkupException.class,
                () -> parser.parse("""
                        <ui>
                          <table>
                            <button id="a"/>
                          </table>
                          <table>
                            <button id="b"/>
                            <button id="c" bogus="1"/>
                          </table>
                        </ui>
                        """));
        assertEquals("ui/table[1]/button[1]", secondTableSecondButton.elementPath());
    }

    @Test
    void whitespaceBetweenElementsIsIgnored() {
        MarkupDocument document = parser.parse("""
                <ui>
                  <table>
                    <row/>
                    <row/>
                  </table>
                </ui>
                """);
        Element table = document.root().children().get(0);
        assertNull(table.text());
        assertEquals(2, table.children().size());
    }

    @Test
    void exactByteLimitPathInputParses() throws Exception {
        // The comment body carries the whole payload: comments are lexical, so the tree is a
        // single <ui> root and no element/text bound is touched at exactly MAX_INPUT_BYTES.
        String xml = "<ui><!--" + "x".repeat(MarkupParser.MAX_INPUT_BYTES - 16) + "--></ui>";
        assertEquals(MarkupParser.MAX_INPUT_BYTES,
                xml.getBytes(StandardCharsets.UTF_8).length);
        Path file = tempDir.resolve("exact.xml");
        Files.write(file, xml.getBytes(StandardCharsets.UTF_8));
        MarkupDocument document = parser.parse(file);
        assertEquals("ui", document.root().tag());
        assertEquals(MarkupParser.MAX_INPUT_BYTES, document.byteLength());
    }

    @Test
    void exactByteLimitStringInputSharesTheSameBoundary() {
        String xml = "<ui><!--" + "x".repeat(MarkupParser.MAX_INPUT_BYTES - 16) + "--></ui>";
        MarkupDocument document = parser.parse(xml);
        assertEquals("ui", document.root().tag());
    }

    @Test
    void limitPlusOneBytePathInputIsRejectedBeforeDecoding() throws Exception {
        // The final byte starts a two-byte UTF-8 sequence, so a decode-first implementation
        // (like Files.readString) would fail with an IOException; the bounded reader must
        // reject on size before any decoding or String materialization.
        byte[] over = new byte[MarkupParser.MAX_INPUT_BYTES + 1];
        Arrays.fill(over, (byte) 'x');
        over[over.length - 1] = (byte) 0xC3;
        Path file = tempDir.resolve("over.xml");
        Files.write(file, over);
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(file));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("limit"));
    }

    @Test
    void limitPlusOneByteStringInputIsRejected() {
        String xml = "<ui><!--" + "x".repeat(MarkupParser.MAX_INPUT_BYTES - 15) + "--></ui>";
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(xml));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("limit"));
    }

    @Test
    void truncatedMultibytePathInputFailsDeterministically() throws Exception {
        byte[] truncated = new byte[] {'<', 'u', 'i', '/', '>', (byte) 0xC3};
        Path file = tempDir.resolve("truncated.xml");
        Files.write(file, truncated);
        MarkupException first = assertThrows(MarkupException.class, () -> parser.parse(file));
        assertEquals(MarkupException.Kind.MALFORMED_XML, first.kind());
        MarkupException second = assertThrows(MarkupException.class, () -> parser.parse(file));
        assertEquals(first.kind(), second.kind());
        assertEquals(first.getMessage(), second.getMessage());
    }
}
