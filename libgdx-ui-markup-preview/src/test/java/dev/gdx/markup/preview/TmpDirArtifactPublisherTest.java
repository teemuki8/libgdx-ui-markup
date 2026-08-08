package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.mcp.ArtifactReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Secure preview artifact storage tests: the {@link TmpDirArtifactPublisher} must own one
 * unpredictable, owner-only session directory created directly in the OS temporary directory
 * (create-new + owner-only attributes, canonical parent/session identity retained and verified),
 * persist payloads under full-digest names through a no-replace atomic install, never follow a
 * pre-planted symbolic link, enforce per-file/total/count quotas before retention, deduplicate
 * identical content without extra quota while rejecting mismatches as collisions, aggregate
 * cleanup failures retry-safely, and remove the created directory when its owner-only policy
 * cannot be established (staged ownership).
 */
final class TmpDirArtifactPublisherTest {
    @TempDir
    Path tempDir;

    @Test
    void publishCreatesOwnerOnlyUnpredictableSessionDirectoryWithFullDigestNames() throws Exception {
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        byte[] content = {1, 2, 3, 4};
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null)) {
            ArtifactReference reference = publisher.publish("image/png", content);
            Path sessionDir = publisher.sessionDir();
            assertTrue(sessionDir.startsWith(tempDir), "the session dir lives in the given parent");
            assertTrue(sessionDir.getFileName().toString().startsWith("gdx-markup-"),
                    "the session dir name is unpredictable: " + sessionDir.getFileName());
            assertTrue(Files.isDirectory(sessionDir, LinkOption.NOFOLLOW_LINKS),
                    "the session directory exists");
            assertFalse(Files.isSymbolicLink(sessionDir), "the session directory is not a link");
            if (posix) {
                assertEquals(PosixFilePermissions.fromString("rwx------"),
                        Files.getPosixFilePermissions(sessionDir),
                        "the session directory is owner-only");
            }
            Path payload = sessionDir.resolve(reference.sha256());
            assertTrue(Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS),
                    "the artifact is a regular file at its full-digest name");
            assertEquals(reference.sha256(), payload.getFileName().toString(),
                    "the artifact name is the full SHA-256 digest");
            if (posix) {
                assertEquals(PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(payload),
                        "the artifact file is owner-only");
            }
            assertArrayEquals(content, Files.readAllBytes(payload),
                    "the published bytes are retained verbatim");
            assertArrayEquals(content, publisher.readBack(reference.sha256()),
                    "readBack resolves the published digest");
        }
    }

    @Test
    void differentPayloadsGetDistinctFullDigestNames() throws Exception {
        byte[] first = "first payload".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second payload".getBytes(StandardCharsets.UTF_8);
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null)) {
            ArtifactReference a = publisher.publish("text/plain", first);
            ArtifactReference b = publisher.publish("text/plain", second);
            assertFalse(a.sha256().equals(b.sha256()), "distinct payloads have distinct digests");
            assertTrue(a.reference().startsWith("artifact:"), "the reference is opaque");
            assertFalse(a.reference().contains("/"), "the reference never exposes a path");
            assertTrue(Files.isRegularFile(publisher.sessionDir().resolve(a.sha256())));
            assertTrue(Files.isRegularFile(publisher.sessionDir().resolve(b.sha256())));
            assertArrayEquals(first, publisher.readBack(a.sha256()));
            assertArrayEquals(second, publisher.readBack(b.sha256()));
        }
    }

    @Test
    void prePlantedSymlinkAtDigestPathIsNeverFollowed() throws Exception {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        String digest = sha256(content);
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null)) {
            Path sessionDir = publisher.sessionDir();
            Path victim = tempDir.resolve("victim-" + System.nanoTime() + ".txt");
            Files.writeString(victim, "do not touch");
            Files.createSymbolicLink(sessionDir.resolve(digest), victim);
            assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                    () -> publisher.publish("text/plain", content),
                    "a pre-planted symbolic link at the digest path is refused");
            assertEquals("do not touch", Files.readString(victim),
                    "the link target is never written through");
            assertNoTempFiles(sessionDir);
            Files.deleteIfExists(victim);
        }
    }

    @Test
    void sessionDirectorySwappedForSymlinkIsRefused() throws Exception {
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null)) {
            Path sessionDir = publisher.sessionDir();
            Path swapped = tempDir.resolve("swapped-" + System.nanoTime());
            Files.createDirectories(swapped);
            Files.delete(sessionDir);
            Files.createSymbolicLink(sessionDir, swapped);
            assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                    () -> publisher.publish("text/plain", new byte[] {1}),
                    "a session directory replaced by a symbolic link is refused");
            Files.deleteIfExists(sessionDir); // the link itself, not the swapped directory
            try (Stream<Path> walk = Files.walk(swapped)) {
                walk.sorted((x, y) -> y.compareTo(x)).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // best-effort cleanup of the swapped directory
                    }
                });
            }
        }
    }

    @Test
    void prePlantedParentSymlinkIsRejectedAtConstruction() throws Exception {
        Path victimParent = tempDir.resolve("victim-parent");
        Files.createDirectories(victimParent);
        Path parent = tempDir.resolve("parent");
        Files.createSymbolicLink(parent, victimParent);
        assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                () -> new TmpDirArtifactPublisher(1024, 4096, 4, parent, null),
                "a pre-planted parent symlink fails closed at construction");
        Files.deleteIfExists(parent);
        try (Stream<Path> walk = Files.walk(victimParent)) {
            walk.sorted((x, y) -> y.compareTo(x)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    @Test
    void replacedParentFailsClosedOnPublish() throws Exception {
        Path parent = tempDir.resolve("parent");
        Files.createDirectories(parent);
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, parent, null)) {
            Path moved = tempDir.resolve("parent-moved");
            Files.move(parent, moved);
            Files.createSymbolicLink(parent, moved); // parent path now resolves elsewhere
            assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                    () -> publisher.publish("text/plain", new byte[] {1}),
                    "a replaced parent fails closed on publish (canonical identity changed)");
            Files.deleteIfExists(parent); // the link, not the moved directory
            Files.move(moved, parent);
        }
    }

    @Test
    void perFileQuotaIsRejectedBeforeRetention() throws Exception {
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(8, 4096, 4, tempDir, null)) {
            assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                    () -> publisher.publish("text/plain", new byte[9]),
                    "an artifact over the per-file quota is rejected");
            assertTrue(listRegularFiles(publisher.sessionDir()).isEmpty(),
                    "nothing is retained after the per-file quota rejection");
        }
    }

    @Test
    void totalQuotaIsRejectedBeforeRetention() throws Exception {
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 16, 4, tempDir, null)) {
            publisher.publish("text/plain", new byte[10]);
            assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                    () -> publisher.publish("text/plain", new byte[7]),
                    "an artifact over the cumulative total quota is rejected");
            assertEquals(1, listRegularFiles(publisher.sessionDir()).size(),
                    "only the first artifact is retained");
        }
    }

    @Test
    void countQuotaIsRejectedBeforeRetention() throws Exception {
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 2, tempDir, null)) {
            publisher.publish("text/plain", new byte[] {1});
            publisher.publish("text/plain", new byte[] {2});
            assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                    () -> publisher.publish("text/plain", new byte[] {3}),
                    "an artifact over the count quota is rejected");
            assertEquals(2, listRegularFiles(publisher.sessionDir()).size(),
                    "only the two in-quota artifacts are retained");
        }
    }

    @Test
    void failedWriteRemovesItsTemporaryFile() throws Exception {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        String digest = sha256(content);
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null)) {
            Path sessionDir = publisher.sessionDir();
            Path blocking = sessionDir.resolve(digest);
            Files.createDirectory(blocking); // a directory occupies the digest path
            assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                    () -> publisher.publish("text/plain", content),
                    "the atomic no-replace install over a directory fails");
            assertTrue(Files.isDirectory(blocking), "the blocking directory remains");
            assertNoTempFiles(sessionDir);
        }
    }

    @Test
    void identicalContentDeduplicatesWithoutExtraQuota() throws Exception {
        byte[] content = "same payload".getBytes(StandardCharsets.UTF_8);
        // Count quota of 1 and a total quota that fits exactly one copy: a deduplicated
        // re-publish must succeed without consuming quota.
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, content.length, 1, tempDir, null)) {
            ArtifactReference first = publisher.publish("text/plain", content);
            ArtifactReference second = publisher.publish("text/plain", content);
            assertEquals(first.sha256(), second.sha256(),
                    "identical content publishes the same digest");
            assertEquals(1, listRegularFiles(publisher.sessionDir()).size(),
                    "only one artifact file is retained for identical content");
            // A distinct payload is rejected: the count/total quota was not consumed twice.
            assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                    () -> publisher.publish("text/plain", new byte[] {9, 9, 9}),
                    "a distinct artifact is rejected after dedupe consumed no quota");
        }
    }

    @Test
    void mismatchedExistingDigestIsACollision() throws Exception {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        String digest = sha256(content);
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null)) {
            Path sessionDir = publisher.sessionDir();
            // A regular file already occupies the digest name with different bytes.
            Files.writeString(sessionDir.resolve(digest), "attacker bytes");
            assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                    () -> publisher.publish("text/plain", content),
                    "a mismatched existing digest is a collision, never replaced");
            assertEquals("attacker bytes",
                    Files.readString(sessionDir.resolve(digest), StandardCharsets.UTF_8),
                    "the mismatched file is never replaced (no REPLACE_EXISTING)");
            assertNoTempFiles(sessionDir);
        }
    }

    @Test
    @Timeout(60)
    void concurrentIdenticalPublishesDeduplicateExactlyOnce() throws Exception {
        byte[] content = "shared payload".getBytes(StandardCharsets.UTF_8);
        int threads = 8;
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, content.length, 1, tempDir, null)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger succeeded = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        publisher.publish("text/plain", content);
                        succeeded.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertEquals(threads, succeeded.get(),
                    "every identical concurrent publish succeeds (dedupe consumes no quota)");
            assertEquals(1, listRegularFiles(publisher.sessionDir()).size(),
                    "exactly one artifact file is retained for identical content");
        }
    }

    @Test
    void ownerOnlyPolicyFailureRemovesTheCreatedDirectory() throws Exception {
        TmpDirArtifactPublisher.OwnerOnlyPolicy failing =
                new TmpDirArtifactPublisher.OwnerOnlyPolicy() {
                    @Override public java.nio.file.attribute.FileAttribute<?>[]
                            directoryCreationAttributes() {
                        return new java.nio.file.attribute.FileAttribute<?>[0];
                    }

                    @Override public void applyDirectory(Path dir) throws java.io.IOException {
                        throw new java.io.IOException("injected-owner-only-failure");
                    }

                    @Override public void verifyDirectory(Path dir) {
                    }

                    @Override public void applyFile(Path file) {
                    }
                };
        assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                () -> new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, failing),
                "construction fails closed when the owner-only policy cannot be established");
        try (Stream<Path> children = Files.list(tempDir)) {
            assertTrue(children.noneMatch(path ->
                            path.getFileName().toString().startsWith("gdx-markup-")),
                    "the partially created session directory is removed on policy failure");
        }
    }

    @Test
    void ownerOnlyPolicyVerifyFailureFailsConstructionAndCleansUp() throws Exception {
        TmpDirArtifactPublisher.OwnerOnlyPolicy verifyFailing =
                new TmpDirArtifactPublisher.OwnerOnlyPolicy() {
                    @Override public java.nio.file.attribute.FileAttribute<?>[]
                            directoryCreationAttributes() {
                        return new java.nio.file.attribute.FileAttribute<?>[0];
                    }

                    @Override public void applyDirectory(Path dir) {
                    }

                    @Override public void verifyDirectory(Path dir) throws java.io.IOException {
                        throw new java.io.IOException("injected-verify-failure");
                    }

                    @Override public void applyFile(Path file) {
                    }
                };
        assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                () -> new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, verifyFailing),
                "construction fails closed when the owner-only policy cannot be verified");
        try (Stream<Path> children = Files.list(tempDir)) {
            assertTrue(children.noneMatch(path ->
                            path.getFileName().toString().startsWith("gdx-markup-")),
                    "the created directory is removed when verification fails");
        }
    }

    @Test
    void aclStyleOwnerOnlyPolicyIsAppliedAndVerified() throws Exception {
        AtomicInteger applies = new AtomicInteger();
        AtomicInteger verifies = new AtomicInteger();
        TmpDirArtifactPublisher.OwnerOnlyPolicy acl =
                new TmpDirArtifactPublisher.OwnerOnlyPolicy() {
                    @Override public java.nio.file.attribute.FileAttribute<?>[]
                            directoryCreationAttributes() {
                        return new java.nio.file.attribute.FileAttribute<?>[0];
                    }

                    @Override public void applyDirectory(Path dir) throws java.io.IOException {
                        applies.incrementAndGet();
                    }

                    @Override public void verifyDirectory(Path dir) throws java.io.IOException {
                        verifies.incrementAndGet();
                    }

                    @Override public void applyFile(Path file) throws java.io.IOException {
                        applies.incrementAndGet();
                    }
                };
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, acl)) {
            publisher.publish("text/plain", new byte[] {1, 2, 3});
        }
        assertTrue(applies.get() >= 2, "the ACL-style policy is applied to dir and file: "
                + applies);
        assertTrue(verifies.get() >= 1, "the ACL-style policy is verified: " + verifies);
    }

    @Test
    void closeDeletesTheSessionDirectoryAndRejectsLaterPublish() throws Exception {
        TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null);
        Path sessionDir = publisher.sessionDir();
        publisher.publish("text/plain", new byte[] {1});
        assertTrue(Files.isDirectory(sessionDir), "the session directory exists before close");
        publisher.close();
        assertFalse(Files.exists(sessionDir, LinkOption.NOFOLLOW_LINKS),
                "close deletes the session directory and its artifacts");
        publisher.close(); // idempotent
        assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                () -> publisher.publish("text/plain", new byte[] {2}),
                "publish after close is rejected");
    }

    @Test
    void closeNeverFollowsSymlinksOutOfTheOwnedDirectory() throws Exception {
        TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null);
        Path sessionDir = publisher.sessionDir();
        Path outside = tempDir.resolve("outside-" + System.nanoTime() + ".txt");
        Files.writeString(outside, "keep me");
        Path planted = sessionDir.resolve("planted-link");
        Files.createSymbolicLink(planted, outside);
        publisher.close();
        assertFalse(Files.exists(sessionDir, LinkOption.NOFOLLOW_LINKS),
                "close deletes the owned session directory");
        assertEquals("keep me", Files.readString(outside),
                "the outside target survives close: links are deleted, not followed");
        Files.deleteIfExists(outside);
    }

    @Test
    void closeAggregatesDeletionFailuresAndIsRetrySafe() throws Exception {
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null);
        Path sessionDir = publisher.sessionDir();
        publisher.publish("text/plain", new byte[] {1, 2, 3});
        if (posix) {
            // Remove the owner's write bit so deleting the owned entries fails.
            Files.setPosixFilePermissions(sessionDir,
                    PosixFilePermissions.fromString("r-x------"));
        } else {
            publisher.close(); // non-POSIX cannot force deletion failure; close cleanly
            return;
        }
        RuntimeException first = assertThrows(RuntimeException.class, publisher::close,
                "close aggregates a deletion failure instead of swallowing it");
        assertTrue(first.getMessage().contains("failed to delete"),
                "the aggregated failure names the failed deletion: " + first.getMessage());
        assertTrue(Files.isDirectory(sessionDir), "the session directory survives the failure");
        // Retry-safe: restoring the write bit lets a second close complete the cleanup.
        Files.setPosixFilePermissions(sessionDir,
                PosixFilePermissions.fromString("rwx------"));
        publisher.close();
        assertFalse(Files.exists(sessionDir, LinkOption.NOFOLLOW_LINKS),
                "a retried close completes the cleanup");
        publisher.close(); // idempotent after success
    }

    @Test
    void closeAfterDirectoryAlreadyGoneIsIdempotent() throws Exception {
        TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null);
        Path sessionDir = publisher.sessionDir();
        publisher.publish("text/plain", new byte[] {1});
        try (Stream<Path> walk = Files.walk(sessionDir)) {
            walk.sorted((x, y) -> y.compareTo(x)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best-effort external deletion
                }
            });
        }
        publisher.close(); // already-deleted entries are fine
        publisher.close();
    }

    @Test
    @Timeout(60)
    void concurrentPublishesHonorTheCountQuota() throws Exception {
        int maxCount = 2;
        int threads = 8;
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, maxCount, tempDir, null)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger succeeded = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                byte[] payload = ("payload-" + index).getBytes(StandardCharsets.UTF_8);
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        publisher.publish("text/plain", payload);
                        succeeded.incrementAndGet();
                    } catch (ArtifactReference.ArtifactUnavailableException expected) {
                        // the quota is exhausted for this thread
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertEquals(maxCount, succeeded.get(),
                    "exactly the count quota of concurrent publishes succeed");
            assertEquals(maxCount, listRegularFiles(publisher.sessionDir()).size(),
                    "exactly the count quota of artifacts are retained");
        }
    }

    private static List<Path> listRegularFiles(Path sessionDir) throws Exception {
        try (Stream<Path> stream = Files.list(sessionDir)) {
            return stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .toList();
        }
    }

    private static void assertNoTempFiles(Path sessionDir) throws Exception {
        try (Stream<Path> stream = Files.list(sessionDir)) {
            List<Path> tempFiles = stream.filter(path ->
                    path.getFileName().toString().startsWith("gdx-tmp-")).toList();
            assertTrue(tempFiles.isEmpty(), "no temporary files remain: " + tempFiles);
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
    }
}
