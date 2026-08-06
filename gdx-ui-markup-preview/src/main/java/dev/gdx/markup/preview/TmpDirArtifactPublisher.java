package dev.gdx.markup.preview;

import dev.gdx.uiharness.mcp.ArtifactReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * {@link ArtifactReference.Publisher} for the preview: persists screenshot payloads under
 * {@code java.io.tmpdir/gdx-ui-markup-artifacts} keyed by SHA-256 and returns an opaque
 * {@code artifact:} reference, so the MCP surface never exposes filesystem paths. CI tests in
 * the same JVM read the bytes back by digest.
 */
final class TmpDirArtifactPublisher implements ArtifactReference.Publisher {
    private static final Path ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "gdx-ui-markup-artifacts");

    @Override public ArtifactReference publish(String mediaType, byte[] content) {
        String sha256;
        try {
            sha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
        try {
            Files.createDirectories(ROOT);
            Files.write(ROOT.resolve(sha256), content);
        } catch (IOException failure) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "unable to persist artifact: " + failure.getMessage());
        }
        return new ArtifactReference("artifact:" + sha256.substring(0, 32), mediaType,
                content.length, sha256);
    }

    /** Resolves one published digest to bytes for verification. */
    static byte[] readBack(String sha256) throws IOException {
        return Files.readAllBytes(ROOT.resolve(sha256));
    }
}
