package dev.gdx.markup.preview;

import dev.gdx.uiharness.mcp.ArtifactReference;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * {@link ArtifactReference.Publisher} for the preview: one instance owns one private session
 * directory under {@code java.io.tmpdir/gdx-ui-markup-artifacts} and persists payloads keyed by
 * their full SHA-256 digest. Writes use create-new temporary files plus an atomic install, so a
 * pre-planted symbolic link at the digest name is refused (never followed); per-file, cumulative
 * byte, and count quotas are enforced before retention under one lock; every failed write removes
 * its temporary file; and {@link #close()} recursively deletes exactly the owned session
 * directory (never following links out of it).
 */
final class TmpDirArtifactPublisher implements ArtifactReference.Publisher, AutoCloseable {
    private static final Path ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "gdx-ui-markup-artifacts");

    /** Production defaults: fixed safe bounds for one preview session. */
    private static final long DEFAULT_MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final long DEFAULT_MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    private static final int DEFAULT_MAX_COUNT = 64;

    private final Path sessionDir;
    private final long maxFileBytes;
    private final long maxTotalBytes;
    private final int maxCount;
    private long totalBytes;
    private int count;
    private boolean closed;

    /** Production constructor: fixed safe defaults and a fresh owner-only session directory. */
    TmpDirArtifactPublisher() {
        this(DEFAULT_MAX_FILE_BYTES, DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_COUNT);
    }

    /** Test constructor: bounded quotas for deterministic tests. */
    TmpDirArtifactPublisher(long maxFileBytes, long maxTotalBytes, int maxCount) {
        if (maxFileBytes <= 0 || maxTotalBytes <= 0 || maxCount <= 0) {
            throw new IllegalArgumentException("quotas must be positive");
        }
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.maxCount = maxCount;
        this.sessionDir = createSessionDir();
    }

    /** Returns the owned session directory (test seam). */
    Path sessionDir() {
        return sessionDir;
    }

    @Override
    public synchronized ArtifactReference publish(String mediaType, byte[] content) {
        if (closed) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "artifact publisher is closed");
        }
        // Quotas are enforced before retention: a rejected artifact leaves nothing behind.
        if (content.length > maxFileBytes) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "artifact exceeds the per-file quota of " + maxFileBytes + " bytes");
        }
        if (count >= maxCount) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "artifact count quota of " + maxCount + " reached");
        }
        if (totalBytes + content.length > maxTotalBytes) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "artifact total quota of " + maxTotalBytes + " bytes exceeded");
        }
        String sha256;
        try {
            sha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
        Path target = sessionDir.resolve(sha256);
        // NOFOLLOW_LINKS validation: the session directory must be a real directory, and a
        // pre-planted link at the digest name is refused instead of being written through.
        if (Files.isSymbolicLink(sessionDir)
                || !Files.isDirectory(sessionDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "artifact session directory is not a trusted directory");
        }
        if (Files.isSymbolicLink(target)) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "refusing to write through a pre-planted symbolic link at " + target);
        }
        Path temp = null;
        try {
            temp = Files.createTempFile(sessionDir, ".tmp-", ".part");
            setOwnerOnlyFile(temp);
            Files.write(temp, content, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            temp = null; // installed; ownership moved to the digest name
            totalBytes += content.length;
            count++;
        } catch (IOException failure) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "unable to persist artifact: " + failure.getMessage());
        } finally {
            // Every failed write removes its temporary file (quota/validation failures happen
            // before any temp exists; install failures reach here with the temp still owned).
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best-effort cleanup of the failed temporary file
                }
            }
        }
        return new ArtifactReference("artifact:" + sha256.substring(0, 32), mediaType,
                content.length, sha256);
    }

    /** Resolves one published digest to bytes for verification, searching the owned session. */
    static byte[] readBack(String sha256) throws IOException {
        if (!Files.isDirectory(ROOT)) {
            throw new IOException("no artifact root: " + ROOT);
        }
        try (Stream<Path> walk = Files.walk(ROOT, 2)) {
            Path payload = walk
                    .filter(path -> path.getFileName().toString().equals(sha256))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .findFirst()
                    .orElseThrow(() -> new IOException("no artifact for digest " + sha256));
            return Files.readAllBytes(payload);
        }
    }

    /** Idempotent close: recursively deletes the owned session directory and its artifacts. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        deleteRecursively(sessionDir);
    }

    /** Deletes the owned directory tree without ever following a symbolic link. */
    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            // Files.walk does not follow links: a planted link is deleted as a link, and its
            // target (even outside the owned directory) is never touched.
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup constrained to the owned directory
                }
            });
        } catch (IOException ignored) {
            // the owned directory may already be gone
        }
    }

    private static Path createSessionDir() {
        try {
            Files.createDirectories(ROOT);
            Path dir = Files.createTempDirectory(ROOT, "session-");
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(dir,
                        PosixFilePermissions.fromString("rwx------"));
            }
            return dir;
        } catch (IOException failure) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "unable to create artifact session directory: " + failure.getMessage());
        }
    }

    private static void setOwnerOnlyFile(Path file) {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            try {
                Files.setPosixFilePermissions(file,
                        PosixFilePermissions.fromString("rw-------"));
            } catch (IOException failure) {
                throw new ArtifactReference.ArtifactUnavailableException(
                        "unable to secure artifact file: " + failure.getMessage());
            }
        }
    }
}
