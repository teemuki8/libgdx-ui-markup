package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.mcp.ArtifactReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * In-memory preview artifact storage tests: the {@link InMemoryArtifactPublisher} must retain
 * payloads only in bounded per-session memory (never on disk), publish opaque
 * {@code artifact:<digest-prefix>} references carrying the full SHA-256 digest, deduplicate
 * identical content without extra quota while rejecting mismatches as typed collisions, enforce
 * per-file/total/count quotas before retention, store and serve defensive byte copies, keep
 * concurrent publishes safe under one accounting, and on {@code close()} reject all later
 * publish/readback while zeroizing and removing the retained payloads.
 */
final class InMemoryArtifactPublisherTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void publishReturnsAnOpaqueReferenceWithFullDigestMetadata() {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        try (InMemoryArtifactPublisher publisher = new InMemoryArtifactPublisher()) {
            ArtifactReference reference = publisher.publish("image/png", content);
            assertTrue(reference.reference().matches("artifact:[0-9a-f]{32}"),
                    "the reference is opaque artifact:<digest-prefix>, got: " + reference.reference());
            assertEquals(reference.sha256().substring(0, 32),
                    reference.reference().substring("artifact:".length()),
                    "the opaque reference embeds the digest's 128-bit prefix");
            assertEquals(reference.sha256(), sha256(content),
                    "the reference carries the full SHA-256 of the payload");
            assertEquals(content.length, reference.byteLength(),
                    "the reference reports the exact byte length");
            assertEquals("image/png", reference.mediaType(),
                    "the reference preserves the media type");
            ArtifactReference.requireOpaque(reference.reference());
        }
    }

    @Test
    void publishRetainsBytesVerbatimAndReadBackResolvesTheDigest() {
        byte[] content = {1, 2, 3, 4, 5};
        try (InMemoryArtifactPublisher publisher = new InMemoryArtifactPublisher()) {
            ArtifactReference reference = publisher.publish("text/plain", content);
            assertArrayEquals(content, publisher.readBack(reference.sha256()),
                    "readBack resolves the published digest to the retained bytes");
        }
    }

    @Test
    void distinctPayloadsGetDistinctOpaqueReferences() {
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);
        try (InMemoryArtifactPublisher publisher = new InMemoryArtifactPublisher()) {
            ArtifactReference a = publisher.publish("text/plain", first);
            ArtifactReference b = publisher.publish("text/plain", second);
            assertNotEquals(a.sha256(), b.sha256(), "distinct payloads have distinct digests");
            assertNotEquals(a.reference(), b.reference(),
                    "distinct payloads get distinct opaque references");
            assertArrayEquals(first, publisher.readBack(a.sha256()));
            assertArrayEquals(second, publisher.readBack(b.sha256()));
        }
    }

    @Test
    void identicalContentDeduplicatesWithoutExtraQuota() {
        byte[] content = "shared".getBytes(StandardCharsets.UTF_8);
        try (InMemoryArtifactPublisher publisher = new InMemoryArtifactPublisher(1024, 4096, 2)) {
            ArtifactReference first = publisher.publish("text/plain", content);
            ArtifactReference dedupe = publisher.publish("text/plain", content);
            assertEquals(first.sha256(), dedupe.sha256(),
                    "re-publishing identical content resolves to the same digest");
            assertEquals(first.reference(), dedupe.reference(),
                    "the deduplicated reference is identical");
            // The dedupe consumed no quota slot: one more distinct payload still fits maxCount=2.
            ArtifactReference distinct = publisher.publish("text/plain", new byte[] {9, 9, 9});
            assertNotEquals(first.sha256(), distinct.sha256());
            assertEquals(2, publisher.retainedCount(),
                    "identical content is retained exactly once");
            assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                    () -> publisher.publish("text/plain", new byte[] {1}),
                    "the count quota still applies after the dedupe");
        }
    }

    @Test
    void digestCollisionIsRejectedAndNeverReplaces() {
        byte[] first = "one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "two".getBytes(StandardCharsets.UTF_8);
        try (InMemoryArtifactPublisher publisher = new InMemoryArtifactPublisher()) {
            // Collision seam: a broken digest provider maps every payload to one key, so the
            // second distinct payload must be rejected as a collision, never silently replaced.
            publisher.digestFunction = bytes -> "0".repeat(64);
            ArtifactReference reference = publisher.publish("text/plain", first);
            ArtifactReference.ArtifactUnavailableException collision =
                    assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                            () -> publisher.publish("text/plain", second),
                            "a digest collision is a typed failure");
            assertTrue(collision.getMessage().contains("collision"),
                    "the collision failure is typed and names the condition: "
                            + collision.getMessage());
            assertArrayEquals(first, publisher.readBack(reference.sha256()),
                    "the first payload is never replaced by the colliding one");
            assertEquals(1, publisher.retainedCount(),
                    "the colliding payload is never retained");
        }
    }

    @Test
    void perFileQuotaIsEnforcedBeforeRetention() {
        byte[] oversized = new byte[5];
        byte[] fitting = new byte[4];
        try (InMemoryArtifactPublisher publisher = new InMemoryArtifactPublisher(4, 4096, 4)) {
            ArtifactReference.ArtifactUnavailableException failure =
                    assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                            () -> publisher.publish("text/plain", oversized),
                            "a payload over the per-file quota is rejected");
            assertTrue(failure.getMessage().contains("per-file quota"),
                    "the per-file quota failure is typed: " + failure.getMessage());
            ArtifactReference accepted = publisher.publish("text/plain", fitting);
            assertArrayEquals(fitting, publisher.readBack(accepted.sha256()),
                    "the rejected publish left no partial state behind");
        }
    }

    @Test
    void countQuotaIsEnforced() {
        try (InMemoryArtifactPublisher publisher = new InMemoryArtifactPublisher(1024, 4096, 2)) {
            publisher.publish("text/plain", new byte[] {1});
            publisher.publish("text/plain", new byte[] {2});
            ArtifactReference.ArtifactUnavailableException failure =
                    assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                            () -> publisher.publish("text/plain", new byte[] {3}),
                            "the count quota rejects the next new payload");
            assertTrue(failure.getMessage().contains("count quota"),
                    "the count quota failure is typed: " + failure.getMessage());
        }
    }

    @Test
    void totalByteQuotaIsEnforcedBeforeRetention() {
        byte[] first = new byte[6];
        byte[] second = {1, 2, 3, 4, 5, 6};
        try (InMemoryArtifactPublisher publisher = new InMemoryArtifactPublisher(1024, 10, 4)) {
            ArtifactReference accepted = publisher.publish("text/plain", first);
            ArtifactReference.ArtifactUnavailableException failure =
                    assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                            () -> publisher.publish("text/plain", second),
                            "a payload over the remaining total quota is rejected");
            assertTrue(failure.getMessage().contains("total quota"),
                    "the total quota failure is typed: " + failure.getMessage());
            // Dedupe of already-retained content bypasses the quota path entirely.
            ArtifactReference dedupe = publisher.publish("text/plain", first);
            assertEquals(accepted.reference(), dedupe.reference(),
                    "dedupe still succeeds when the total quota is exhausted");
        }
    }

    @Test
    void publishedBytesAreDefensivelyCopied() {
        byte[] content = {10, 20, 30};
        try (InMemoryArtifactPublisher publisher = new InMemoryArtifactPublisher()) {
            ArtifactReference reference = publisher.publish("text/plain", content);
            content[0] = 99; // caller mutates the original array after publishing
            assertArrayEquals(new byte[] {10, 20, 30}, publisher.readBack(reference.sha256()),
                    "the retained payload is an immutable copy of the published array");
        }
    }

    @Test
    void concurrentCallerMutationCannotCorruptTheRetainedPayload() throws Exception {
        byte[] content = "original bytes".getBytes(StandardCharsets.UTF_8);
        try (InMemoryArtifactPublisher publisher = new InMemoryArtifactPublisher()) {
            // Deterministic race seam: the digest hook blocks mid-publish, and the test mutates
            // the caller-owned array while the publisher is still hashing it. The publisher must
            // snapshot the input once before hashing, so the retained payload and its digest are
            // the bytes as they were at publish call time — never the later-mutated array.
            CountDownLatch hashing = new CountDownLatch(1);
            CountDownLatch mutated = new CountDownLatch(1);
            publisher.digestFunction = bytes -> {
                hashing.countDown();
                try {
                    mutated.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted inside the digest seam", interrupted);
                }
                return sha256(bytes);
            };
            AtomicReference<ArtifactReference> published = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread publishThread = new Thread(() -> {
                try {
                    published.set(publisher.publish("text/plain", content));
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            }, "artifact-publish-mutation-race");
            publishThread.start();
            assertTrue(hashing.await(30, TimeUnit.SECONDS),
                    "the publisher reached the digest seam");
            Arrays.fill(content, (byte) 0x7f); // concurrent caller-side mutation mid-publish
            mutated.countDown();
            publishThread.join(30_000);
            assertNull(failure.get(), "the publish did not fail under caller mutation");
            assertArrayEquals("original bytes".getBytes(StandardCharsets.UTF_8),
                    publisher.readBack(published.get().sha256()),
                    "the retained payload is the snapshot taken at publish time, never the "
                            + "mutated caller array");
            assertEquals(published.get().sha256(), sha256(
                    "original bytes".getBytes(StandardCharsets.UTF_8)),
                    "the reference digest describes the published snapshot, not the mutation");
        }
    }

    @Test
    void readBackReturnsDefensiveCopies() {
        byte[] content = {7, 8, 9};
        try (InMemoryArtifactPublisher publisher = new InMemoryArtifactPublisher()) {
            ArtifactReference reference = publisher.publish("text/plain", content);
            byte[] firstRead = publisher.readBack(reference.sha256());
            firstRead[0] = 42; // caller mutates the returned copy
            assertArrayEquals(content, publisher.readBack(reference.sha256()),
                    "readBack never exposes the retained array itself");
        }
    }

    @Test
    void concurrentDistinctPublishesSatisfyTheCountQuotaExactly() throws Exception {
        int threads = 8;
        int perThread = 8;
        int maxCount = threads * perThread;
        try (InMemoryArtifactPublisher publisher =
                new InMemoryArtifactPublisher(1024, 1024L * maxCount, maxCount)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ArtifactReference>> futures = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                int threadIndex = thread;
                futures.add(pool.submit(() -> {
                    start.await();
                    ArtifactReference last = null;
                    for (int item = 0; item < perThread; item++) {
                        byte[] payload = new byte[] {
                                (byte) threadIndex, (byte) (item + 1), (byte) (threadIndex * item)};
                        last = publisher.publish("text/plain", payload);
                    }
                    return last;
                }));
            }
            start.countDown();
            for (Future<ArtifactReference> future : futures) {
                future.get(30, TimeUnit.SECONDS); // any quota/accounting failure surfaces here
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(maxCount, publisher.retainedCount(),
                    "every distinct payload was retained exactly once under contention");
            assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                    () -> publisher.publish("text/plain", new byte[] {0, 1, 2}),
                    "the count quota is still exact after the concurrent burst");
        }
    }

    @Test
    void concurrentIdenticalPublishesDedupeToASingleRetention() throws Exception {
        int threads = 16;
        byte[] shared = new byte[512];
        try (InMemoryArtifactPublisher publisher = new InMemoryArtifactPublisher(1024, 512, 1)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ArtifactReference>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return publisher.publish("text/plain", shared);
                }));
            }
            start.countDown();
            ArtifactReference expected = null;
            for (Future<ArtifactReference> future : futures) {
                ArtifactReference reference = future.get(30, TimeUnit.SECONDS);
                if (expected == null) {
                    expected = reference;
                } else {
                    assertEquals(expected.reference(), reference.reference(),
                            "concurrent identical publishes dedupe to one reference");
                }
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(1, publisher.retainedCount(),
                    "a single byte budget admits exactly one retention under the dedupe race");
            assertArrayEquals(shared, publisher.readBack(expected.sha256()),
                    "the deduplicated payload is still readable");
        }
    }

    @Test
    void closeRejectsPublishAndReadBackIdempotentlyAndClearsRetainedPayloads() {
        byte[] content = "keep-me".getBytes(StandardCharsets.UTF_8);
        InMemoryArtifactPublisher publisher = new InMemoryArtifactPublisher();
        ArtifactReference reference = publisher.publish("text/plain", content);
        assertEquals(1, publisher.retainedCount());
        publisher.close();
        assertTrue(publisher.isClosed(), "the publisher is closed");
        assertEquals(0, publisher.retainedCount(),
                "close removes every retained payload");
        ArtifactReference.ArtifactUnavailableException publishFailure =
                assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                        () -> publisher.publish("text/plain", new byte[] {1}),
                        "publish after close is rejected");
        assertTrue(publishFailure.getMessage().contains("closed"),
                "the post-close publish failure is typed: " + publishFailure.getMessage());
        ArtifactReference.ArtifactUnavailableException readFailure =
                assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                        () -> publisher.readBack(reference.sha256()),
                        "readBack after close is rejected");
        assertTrue(readFailure.getMessage().contains("closed"),
                "the post-close readback failure is typed: " + readFailure.getMessage());
        publisher.close(); // idempotent: a second close neither throws nor re-closes
        assertTrue(publisher.isClosed());
        assertEquals(0, publisher.retainedCount());
    }

    @Test
    void neverTouchesTheFileSystem() throws Exception {
        Path osTemp = Path.of(System.getProperty("java.io.tmpdir"));
        List<String> before = osTempEntries(osTemp);
        try (InMemoryArtifactPublisher publisher = new InMemoryArtifactPublisher()) {
            ArtifactReference first = publisher.publish("text/plain", new byte[] {1, 2, 3});
            ArtifactReference second = publisher.publish("text/plain", new byte[4096]);
            assertArrayEquals(new byte[] {1, 2, 3}, publisher.readBack(first.sha256()));
            publisher.readBack(second.sha256());
        }
        assertEquals(before, osTempEntries(osTemp),
                "publishing and closing an in-memory session creates no OS temp entries");
    }

    @Test
    void quotasMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryArtifactPublisher(0, 4096, 4),
                "a zero per-file quota is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryArtifactPublisher(1024, -1, 4),
                "a negative total quota is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryArtifactPublisher(1024, 4096, 0),
                "a zero count quota is rejected");
    }

    private static List<String> osTempEntries(Path osTemp) throws Exception {
        List<String> entries = new ArrayList<>();
        try (var stream = Files.list(osTemp)) {
            stream.filter(path -> path.getFileName().toString().startsWith("gdx-markup-")
                            || path.getFileName().toString().startsWith("gdx-tmp-"))
                    .forEach(path -> entries.add(path.getFileName().toString()));
        }
        return entries;
    }

    private static String sha256(byte[] content) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception impossible) {
            throw new AssertionError("SHA-256 is not available", impossible);
        }
    }
}
