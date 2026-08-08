package dev.gdx.markup.qualification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bounded manifest schema (byte/entry/string caps, strict JSON shape) and corpus-contained path
 * resolution: traversal, separator variants, and symlink escapes are typed failures.
 */
final class CorpusManifestTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tempDir;

    // ---------------------------------------------------------------- helpers

    private static ObjectNode entry(String id, String referenceFile) {
        return entry(id, referenceFile, "MIT");
    }

    private static ObjectNode entry(String id, String referenceFile, String license) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", id);
        node.put("referenceFile", referenceFile);
        node.put("license", license);
        node.put("markupFile", id + ".xml");
        node.put("threshold", 0.2);
        node.put("referenceWidth", 1280);
        node.put("referenceHeight", 720);
        return node;
    }

    private Path writeManifest(ObjectNode root) throws IOException {
        Path file = tempDir.resolve("manifest.json");
        Files.writeString(file, JSON.writeValueAsString(root));
        return file;
    }

    private Path writeEntries(ObjectNode... entries) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode array = root.putArray("entries");
        for (ObjectNode entry : entries) {
            array.add(entry);
        }
        return writeManifest(root);
    }

    private static ManifestException reject(Path manifest) {
        return assertThrows(ManifestException.class, () -> CorpusManifest.load(manifest));
    }

    /**
     * Writes a valid one-entry manifest padded with surrounding whitespace to exactly
     * {@code bytes} bytes, so the byte cap can be tested independently of the schema caps.
     */
    private Path writePaddedManifest(int bytes) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("comment", "");
        root.putArray("entries").add(entry("a", "a.png"));
        String base = JSON.writeValueAsString(root);
        int deficit = bytes - base.getBytes(StandardCharsets.UTF_8).length;
        if (deficit < 0) {
            throw new IllegalStateException(
                    "template is " + (-deficit) + " bytes larger than the target " + bytes);
        }
        Path file = tempDir.resolve("manifest.json");
        Files.writeString(file, base + " ".repeat(deficit));
        assertEquals(bytes, Files.size(file), "whitespace padding must land exactly on target");
        return file;
    }

    private static void createSymlinkOrAbort(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable: " + unavailable);
        }
    }

    // ---------------------------------------------------------------- byte and entry caps

    @Test
    void acceptsManifestExactlyAtByteLimit() throws IOException {
        Path manifest = writePaddedManifest(CorpusManifest.MAX_MANIFEST_BYTES);
        assertEquals(1, CorpusManifest.load(manifest).entries().size());
    }

    @Test
    void rejectsManifestOneByteOverTheByteLimit() throws IOException {
        Path manifest = writePaddedManifest(CorpusManifest.MAX_MANIFEST_BYTES + 1);
        ManifestException failure = reject(manifest);
        assertEquals(ManifestException.Kind.TOO_LARGE, failure.kind());
    }

    @Test
    void acceptsExactlyMaxEntries() throws IOException {
        ObjectNode[] entries = new ObjectNode[CorpusManifest.MAX_ENTRIES];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = entry("e" + i, "a.png");
        }
        assertEquals(CorpusManifest.MAX_ENTRIES,
                CorpusManifest.load(writeEntries(entries)).entries().size());
    }

    @Test
    void rejectsMoreThanMaxEntries() throws IOException {
        ObjectNode[] entries = new ObjectNode[CorpusManifest.MAX_ENTRIES + 1];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = entry("e" + i, "a.png");
        }
        ManifestException failure = reject(writeEntries(entries));
        assertEquals(ManifestException.Kind.TOO_MANY_ENTRIES, failure.kind());
    }

    @Test
    void acceptsStringExactlyAtPerStringCap() throws IOException {
        String license = "x".repeat(CorpusManifest.MAX_STRING_LENGTH);
        Path manifest = writeEntries(entry("a", "a.png", license));
        assertEquals(license, CorpusManifest.load(manifest).entries().get(0).license());
    }

    @Test
    void rejectsStringOneOverThePerStringCap() throws IOException {
        String license = "x".repeat(CorpusManifest.MAX_STRING_LENGTH + 1);
        ManifestException failure = reject(writeEntries(entry("a", "a.png", license)));
        assertEquals(ManifestException.Kind.STRING_TOO_LONG, failure.kind());
    }

    @Test
    void acceptsIdExactlyAtIdCap() throws IOException {
        String id = "a".repeat(CorpusManifest.MAX_ID_LENGTH);
        assertEquals(1, CorpusManifest.load(writeEntries(entry(id, "a.png"))).entries().size());
    }

    @Test
    void rejectsIdOneOverTheIdCap() throws IOException {
        String id = "a".repeat(CorpusManifest.MAX_ID_LENGTH + 1);
        ManifestException failure = reject(writeEntries(entry(id, "a.png")));
        assertEquals(ManifestException.Kind.STRING_TOO_LONG, failure.kind());
    }

    @Test
    void rejectsAggregateStringWorkOverTheCap() throws IOException {
        // Each entry passes the per-string cap; 7 entries push the aggregate over the work cap.
        ObjectNode[] entries = new ObjectNode[7];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = entry("e" + i, "x".repeat(CorpusManifest.MAX_STRING_LENGTH),
                    "x".repeat(CorpusManifest.MAX_STRING_LENGTH));
            entries[i].put("markupFile", "x".repeat(CorpusManifest.MAX_STRING_LENGTH));
        }
        ManifestException failure = reject(writeEntries(entries));
        assertEquals(ManifestException.Kind.WORK_LIMIT, failure.kind());
    }

    // ---------------------------------------------------------------- strict JSON shape

    @Test
    void loadsTheCommittedCorpusManifest() throws IOException {
        Path corpus = Path.of("corpus", "manifest.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(corpus),
                "committed corpus manifest not found from the working directory");
        CorpusManifest manifest = CorpusManifest.load(corpus);
        assertTrue(!manifest.entries().isEmpty(),
                "the committed corpus must keep satisfying the bounded schema");
    }

    @Test
    void loadsValidManifestWithImmutableEntries() throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("comment", "fixture corpus");
        ArrayNode array = root.putArray("entries");
        array.add(entry("alpha", "reference/alpha.png"));
        ObjectNode remote = entry("beta", "beta.png");
        remote.remove("referenceFile");
        remote.put("sourceUrl", "https://example.com/beta.png");
        array.add(remote);

        CorpusManifest manifest = CorpusManifest.load(writeManifest(root));

        assertEquals(2, manifest.entries().size());
        assertEquals("alpha", manifest.entries().get(0).id());
        assertEquals("reference/alpha.png", manifest.entries().get(0).referenceFile());
        assertTrue(manifest.entries().get(0).sourceUrl() == null);
        assertEquals("https://example.com/beta.png", manifest.entries().get(1).sourceUrl());
        assertThrows(UnsupportedOperationException.class,
                () -> manifest.entries().add(new CorpusEntry("gamma", null, "gamma.png", "MIT",
                        "gamma.xml", 0.2, 1280, 720)));
    }

    @Test
    void rejectsUnknownManifestField() throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", 1);
        root.putArray("entries").add(entry("a", "a.png"));
        ManifestException failure = reject(writeManifest(root));
        assertEquals(ManifestException.Kind.UNKNOWN_FIELD, failure.kind());
    }

    @Test
    void rejectsUnknownEntryField() throws IOException {
        ObjectNode node = entry("a", "a.png");
        node.put("size", 5);
        ManifestException failure = reject(writeEntries(node));
        assertEquals(ManifestException.Kind.UNKNOWN_FIELD, failure.kind());
    }

    @Test
    void rejectsManifestWithoutEntriesArray() throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("comment", "nothing here");
        ManifestException failure = reject(writeManifest(root));
        assertEquals(ManifestException.Kind.MISSING_FIELD, failure.kind());
    }

    @Test
    void rejectsEmptyEntriesArray() throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.putArray("entries");
        ManifestException failure = reject(writeManifest(root));
        assertEquals(ManifestException.Kind.MISSING_FIELD, failure.kind());
    }

    @Test
    void rejectsEntryWithoutId() throws IOException {
        ObjectNode node = entry("a", "a.png");
        node.remove("id");
        ManifestException failure = reject(writeEntries(node));
        assertEquals(ManifestException.Kind.MISSING_FIELD, failure.kind());
    }

    @Test
    void rejectsEntryWithBothSourceAndReference() throws IOException {
        ObjectNode node = entry("a", "a.png");
        node.put("sourceUrl", "https://example.com/a.png");
        ManifestException failure = reject(writeEntries(node));
        assertEquals(ManifestException.Kind.MISSING_FIELD, failure.kind());
    }

    @Test
    void rejectsEntryWithNeitherSourceNorReference() throws IOException {
        ObjectNode node = entry("a", "a.png");
        node.remove("referenceFile");
        ManifestException failure = reject(writeEntries(node));
        assertEquals(ManifestException.Kind.MISSING_FIELD, failure.kind());
    }

    @Test
    void rejectsNonObjectEntriesField() throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("entries", "not-an-array");
        ManifestException failure = reject(writeManifest(root));
        assertEquals(ManifestException.Kind.WRONG_TYPE, failure.kind());
    }

    @Test
    void rejectsNonStringComment() throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("comment", 42);
        root.putArray("entries").add(entry("a", "a.png"));
        ManifestException failure = reject(writeManifest(root));
        assertEquals(ManifestException.Kind.WRONG_TYPE, failure.kind());
    }

    @Test
    void rejectsNumericId() throws IOException {
        ObjectNode node = entry("a", "a.png");
        node.put("id", 42);
        ManifestException failure = reject(writeEntries(node));
        assertEquals(ManifestException.Kind.WRONG_TYPE, failure.kind());
    }

    @Test
    void rejectsStringThreshold() throws IOException {
        ObjectNode node = entry("a", "a.png");
        node.put("threshold", "low");
        ManifestException failure = reject(writeEntries(node));
        assertEquals(ManifestException.Kind.WRONG_TYPE, failure.kind());
    }

    @Test
    void rejectsNonIntegralDimension() throws IOException {
        ObjectNode node = entry("a", "a.png");
        node.put("referenceWidth", 12.5);
        ManifestException failure = reject(writeEntries(node));
        assertEquals(ManifestException.Kind.WRONG_TYPE, failure.kind());
    }

    @Test
    void rejectsThresholdOutsideUnitInterval() throws IOException {
        ObjectNode node = entry("a", "a.png");
        node.put("threshold", 1.5);
        ManifestException failure = reject(writeEntries(node));
        assertEquals(ManifestException.Kind.INVALID_VALUE, failure.kind());
    }

    @Test
    void rejectsNonPositiveDimension() throws IOException {
        ObjectNode node = entry("a", "a.png");
        node.put("referenceWidth", 0);
        ManifestException failure = reject(writeEntries(node));
        assertEquals(ManifestException.Kind.INVALID_VALUE, failure.kind());
    }

    @Test
    void rejectsDimensionThatOverflowsInt() throws IOException {
        ObjectNode node = entry("a", "a.png");
        node.put("referenceWidth", 2_147_483_648L);
        ManifestException failure = reject(writeEntries(node));
        assertEquals(ManifestException.Kind.INVALID_VALUE, failure.kind());
    }

    @Test
    void rejectsTrailingGarbageAfterJson() throws IOException {
        Path manifest = writeEntries(entry("a", "a.png"));
        Files.writeString(manifest, Files.readString(manifest) + " x");
        ManifestException failure = reject(manifest);
        assertEquals(ManifestException.Kind.INVALID_JSON, failure.kind());
    }

    // ---------------------------------------------------------------- ids and paths

    @Test
    void rejectsInvalidIdShape() throws IOException {
        ManifestException failure = reject(writeEntries(entry("Bad_ID", "a.png")));
        assertEquals(ManifestException.Kind.INVALID_ID, failure.kind());
    }

    @Test
    void rejectsIdWithTraversalShape() throws IOException {
        ManifestException failure = reject(writeEntries(entry("../escape", "a.png")));
        assertEquals(ManifestException.Kind.INVALID_ID, failure.kind());
    }

    @Test
    void rejectsAbsoluteMarkupPath() throws IOException {
        ObjectNode node = entry("a", "a.png");
        node.put("markupFile", "/etc/passwd");
        ManifestException failure = reject(writeEntries(node));
        assertEquals(ManifestException.Kind.ABSOLUTE_PATH, failure.kind());
    }

    @Test
    void rejectsAbsoluteReferencePath() throws IOException {
        ManifestException failure = reject(writeEntries(entry("a", "/etc/passwd")));
        assertEquals(ManifestException.Kind.ABSOLUTE_PATH, failure.kind());
    }

    @Test
    void rejectsDotDotSegmentInPath() throws IOException {
        ObjectNode node = entry("a", "a.png");
        node.put("markupFile", "a/../b.xml");
        ManifestException failure = reject(writeEntries(node));
        assertEquals(ManifestException.Kind.INVALID_PATH, failure.kind());
    }

    @Test
    void rejectsLeadingDotDotInPath() throws IOException {
        ObjectNode node = entry("a", "a.png");
        node.put("markupFile", "../outside.xml");
        ManifestException failure = reject(writeEntries(node));
        assertEquals(ManifestException.Kind.INVALID_PATH, failure.kind());
    }

    @Test
    void rejectsDotOnlyPath() throws IOException {
        ObjectNode node = entry("a", "a.png");
        node.put("markupFile", ".");
        ManifestException failure = reject(writeEntries(node));
        assertEquals(ManifestException.Kind.INVALID_PATH, failure.kind());
    }

    @Test
    void rejectsBackslashSeparatorVariant() throws IOException {
        ObjectNode node = entry("a", "a.png");
        node.put("markupFile", "a\\b.xml");
        ManifestException failure = reject(writeEntries(node));
        assertEquals(ManifestException.Kind.INVALID_PATH, failure.kind());
    }

    // ---------------------------------------------------------------- containment helper

    @Test
    void resolveInsideRejectsPathOutsideRoot() throws IOException {
        Path corpus = Files.createDirectories(tempDir.resolve("corpus"));
        ManifestException failure = assertThrows(ManifestException.class,
                () -> QualificationRunner.resolveInside(corpus, "../outside.png"));
        assertEquals(ManifestException.Kind.OUTSIDE_ROOT, failure.kind());
    }

    @Test
    void resolveInsideRejectsOutputTraversal() throws IOException {
        Path output = Files.createDirectories(tempDir.resolve("output"));
        ManifestException failure = assertThrows(ManifestException.class,
                () -> QualificationRunner.resolveInside(output, "../report.json"));
        assertEquals(ManifestException.Kind.OUTSIDE_ROOT, failure.kind());
    }

    @Test
    void resolveInsideRejectsSymlinkFileEscape() throws IOException {
        Path corpus = Files.createDirectories(tempDir.resolve("corpus"));
        Path outside = Files.createFile(tempDir.resolve("outside.png"));
        createSymlinkOrAbort(corpus.resolve("escape.png"), outside);

        ManifestException failure = assertThrows(ManifestException.class,
                () -> QualificationRunner.resolveInside(corpus, "escape.png"));
        assertEquals(ManifestException.Kind.SYMLINK_ESCAPE, failure.kind());
    }

    @Test
    void resolveInsideRejectsSymlinkDirectoryEscape() throws IOException {
        Path corpus = Files.createDirectories(tempDir.resolve("corpus"));
        Path outsideDir = Files.createDirectories(tempDir.resolve("outside-dir"));
        Files.writeString(outsideDir.resolve("secret.png"), "secret");
        createSymlinkOrAbort(corpus.resolve("ref"), outsideDir);

        ManifestException failure = assertThrows(ManifestException.class,
                () -> QualificationRunner.resolveInside(corpus, "ref/secret.png"));
        assertEquals(ManifestException.Kind.SYMLINK_ESCAPE, failure.kind());
    }

    @Test
    void resolveInsideAllowsSymlinkStayingInsideRoot() throws IOException {
        Path corpus = Files.createDirectories(tempDir.resolve("corpus"));
        Files.writeString(corpus.resolve("real.png"), "real");
        createSymlinkOrAbort(corpus.resolve("alias.png"), corpus.resolve("real.png"));

        Path resolved = QualificationRunner.resolveInside(corpus, "alias.png");
        assertEquals(corpus.toAbsolutePath().normalize().resolve("alias.png"), resolved);
    }
}
