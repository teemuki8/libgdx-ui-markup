package dev.gdx.markup.preview;

import dev.gdx.uiharness.mcp.ArtifactReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ArtifactReference.Publisher} for the preview: one instance retains payloads only in
 * bounded per-session memory keyed by their full SHA-256 digest, never on disk.
 *
 * <p>Per-file, cumulative byte, and count quotas are enforced before retention under one lock;
 * identical content deduplicates without extra quota and mismatches are collisions (never
 * replaced). Retained payloads are immutable defensive copies: {@link #publish} clones the
 * caller's array and {@link #readBack} clones the stored array. {@link #close()} zeroizes every
 * retained payload before removing it, is idempotent, and rejects all later
 * publish/readback with a typed {@link ArtifactReference.ArtifactUnavailableException}. There
 * is no filesystem, symbolic link, ACL, or platform-dependent code anywhere in this class.
 */
final class InMemoryArtifactPublisher implements ArtifactReference.Publisher, AutoCloseable {
    /** Production defaults: fixed safe bounds for one preview session. */
    private static final long DEFAULT_MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final long DEFAULT_MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    private static final int DEFAULT_MAX_COUNT = 64;

    private static final HexFormat HEX = HexFormat.of();

    private final long maxFileBytes;
    private final long maxTotalBytes;
    private final int maxCount;
    /** Full SHA-256 digest to the retained defensive copy (insertion order = publication
     * order, deterministic for the count-quota boundary). */
    private final Map<String, byte[]> retained = new LinkedHashMap<>();
    private long totalBytes;
    private boolean closed;

    /** Test seam: digest provider, injectable to force deterministic digest collisions. */
    DigestFunction digestFunction = InMemoryArtifactPublisher::sha256;

    /** Production constructor: fixed safe defaults for one preview session. */
    InMemoryArtifactPublisher() {
        this(DEFAULT_MAX_FILE_BYTES, DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_COUNT);
    }

    /** Test constructor: bounded quotas for deterministic tests. */
    InMemoryArtifactPublisher(long maxFileBytes, long maxTotalBytes, int maxCount) {
        if (maxFileBytes <= 0 || maxTotalBytes <= 0 || maxCount <= 0) {
            throw new IllegalArgumentException("quotas must be positive");
        }
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.maxCount = maxCount;
    }

    @Override
    public synchronized ArtifactReference publish(String mediaType, byte[] content) {
        if (closed) {
            throw unavailable("artifact publisher is closed");
        }
        if (content.length > maxFileBytes) {
            throw unavailable("artifact exceeds the per-file quota of " + maxFileBytes + " bytes");
        }
        // Snapshot the caller-owned array exactly once, immediately after the size check: the
        // digest, dedupe comparison, and retention must all describe the same immutable bytes
        // as they were at publish time, immune to concurrent caller-side mutation.
        byte[] snapshot = content.clone();
        String sha256 = digestFunction.digest(snapshot);
        byte[] existing = retained.get(sha256);
        if (existing != null) {
            if (Arrays.equals(existing, snapshot)) {
                return reference(mediaType, snapshot.length, sha256);
            }
            throw unavailable("artifact digest collision at " + sha256);
        }
        // Quotas are enforced before retention: a rejected new artifact leaves nothing behind.
        if (retained.size() >= maxCount) {
            throw unavailable("artifact count quota of " + maxCount + " reached");
        }
        if (totalBytes + snapshot.length > maxTotalBytes) {
            throw unavailable(
                    "artifact total quota of " + maxTotalBytes + " bytes exceeded");
        }
        retained.put(sha256, snapshot);
        totalBytes += snapshot.length;
        return reference(mediaType, snapshot.length, sha256);
    }

    /** Resolves one published digest to a defensive copy of its bytes (package-visible seam
     * for in-process readback during the session). */
    synchronized byte[] readBack(String sha256) {
        if (closed) {
            throw unavailable("artifact publisher is closed");
        }
        byte[] payload = retained.get(sha256);
        if (payload == null) {
            throw unavailable("no artifact for digest " + sha256);
        }
        return payload.clone();
    }

    /** Idempotent: zeroizes every retained payload, removes it, and rejects later
     * publish/readback. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (byte[] payload : retained.values()) {
            Arrays.fill(payload, (byte) 0);
        }
        retained.clear();
        totalBytes = 0;
    }

    /** Whether {@link #close()} has run (package-visible test seam). */
    synchronized boolean isClosed() {
        return closed;
    }

    /** Number of retained payloads (package-visible test seam). */
    synchronized int retainedCount() {
        return retained.size();
    }

    private static ArtifactReference reference(String mediaType, int byteLength, String sha256) {
        return new ArtifactReference("artifact:" + sha256.substring(0, 32), mediaType,
                byteLength, sha256);
    }

    private static String sha256(byte[] content) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static ArtifactReference.ArtifactUnavailableException unavailable(String message) {
        return new ArtifactReference.ArtifactUnavailableException(message);
    }

    /** Test seam: produces the digest key for one payload. */
    @FunctionalInterface
    interface DigestFunction {
        String digest(byte[] content);
    }
}
