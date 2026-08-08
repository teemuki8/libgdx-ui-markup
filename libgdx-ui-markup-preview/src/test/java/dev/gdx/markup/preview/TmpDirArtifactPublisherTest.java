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

/**
 * Secure preview artifact storage tests: the {@link TmpDirArtifactPublisher} must own one
 * private owner-only session directory, persist payloads under full-digest names through
 * create-new temporary writes plus atomic install, never follow a pre-planted symbolic link,
 * enforce per-file/total/count quotas before retention, remove its temporary file on every
 * failed write, and recursively delete exactly the owned directory on close.
 */
final class TmpDirArtifactPublisherTest {
    @Test
    void publishCreatesOwnerOnlySessionDirectoryWithFullDigestNames() throws Exception {
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        byte[] content = {1, 2, 3, 4};
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4)) {
            ArtifactReference reference = publisher.publish("image/png", content);
            Path sessionDir = publisher.sessionDir();
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
            assertArrayEquals(content, TmpDirArtifactPublisher.readBack(reference.sha256()),
                    "readBack resolves the published digest");
        }
    }

    @Test
    void differentPayloadsGetDistinctFullDigestNames() throws Exception {
        byte[] first = "first payload".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second payload".getBytes(StandardCharsets.UTF_8);
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4)) {
            ArtifactReference a = publisher.publish("text/plain", first);
            ArtifactReference b = publisher.publish("text/plain", second);
            assertFalse(a.sha256().equals(b.sha256()), "distinct payloads have distinct digests");
            assertTrue(a.reference().startsWith("artifact:"), "the reference is opaque");
            assertFalse(a.reference().contains("/"), "the reference never exposes a path");
            assertTrue(Files.isRegularFile(publisher.sessionDir().resolve(a.sha256())));
            assertTrue(Files.isRegularFile(publisher.sessionDir().resolve(b.sha256())));
            assertArrayEquals(first, TmpDirArtifactPublisher.readBack(a.sha256()));
            assertArrayEquals(second, TmpDirArtifactPublisher.readBack(b.sha256()));
        }
    }

    @Test
    void prePlantedSymlinkAtDigestPathIsNeverFollowed() throws Exception {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        String digest = sha256(content);
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4)) {
            Path sessionDir = publisher.sessionDir();
            Path victim = sessionDir.resolveSibling("victim-" + System.nanoTime() + ".txt");
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
                new TmpDirArtifactPublisher(1024, 4096, 4)) {
            Path sessionDir = publisher.sessionDir();
            Path swapped = sessionDir.resolveSibling("swapped-" + System.nanoTime());
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
    void perFileQuotaIsRejectedBeforeRetention() throws Exception {
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(8, 4096, 4)) {
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
                new TmpDirArtifactPublisher(1024, 16, 4)) {
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
                new TmpDirArtifactPublisher(1024, 4096, 2)) {
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
                new TmpDirArtifactPublisher(1024, 4096, 4)) {
            Path sessionDir = publisher.sessionDir();
            Path blocking = sessionDir.resolve(digest);
            Files.createDirectory(blocking); // a directory occupies the digest path
            assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                    () -> publisher.publish("text/plain", content),
                    "the atomic install over a directory fails");
            assertTrue(Files.isDirectory(blocking), "the blocking directory remains");
            assertNoTempFiles(sessionDir);
        }
    }

    @Test
    void closeDeletesTheSessionDirectoryAndRejectsLaterPublish() throws Exception {
        TmpDirArtifactPublisher publisher = new TmpDirArtifactPublisher(1024, 4096, 4);
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
        TmpDirArtifactPublisher publisher = new TmpDirArtifactPublisher(1024, 4096, 4);
        Path sessionDir = publisher.sessionDir();
        Path outside = sessionDir.resolveSibling("outside-" + System.nanoTime() + ".txt");
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
    @Timeout(60)
    void concurrentPublishesHonorTheCountQuota() throws Exception {
        int maxCount = 2;
        int threads = 8;
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, maxCount)) {
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
                    path.getFileName().toString().startsWith(".tmp-")).toList();
            assertTrue(tempFiles.isEmpty(), "no temporary files remain: " + tempFiles);
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
    }
}
