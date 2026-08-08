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
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Secure preview artifact storage tests: the {@link TmpDirArtifactPublisher} must own one
 * unpredictable, owner-only session directory created directly in the OS temporary directory
 * (create-new with owner-only attributes applied atomically, immutable parent/session identity
 * captured as canonical path + fileKey + owner and verified before every operation), persist
 * payloads under full-digest names through a no-replace atomic install (directory-relative with
 * SDS, identity-bracketed otherwise), never follow a pre-planted symbolic link, enforce
 * per-file/total/count quotas before retention, deduplicate identical content without extra
 * quota while rejecting mismatches as collisions, and clean up directory-relative without ever
 * deleting a replacement (leaks are reported, never silently deleted).
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
    void realDirectoryReplantIsRefusedAndReportedNotDeleted() throws Exception {
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null)) {
            Path sessionDir = publisher.sessionDir();
            publisher.publish("text/plain", new byte[] {1});
            // A real (non-symlink) rename + replant: the immutable fileKey identity must fail
            // closed on the next operation even though the canonical path and mode still match.
            Path moved = tempDir.resolve("moved-" + System.nanoTime());
            Files.move(sessionDir, moved);
            Files.createDirectory(sessionDir); // replant a fresh real directory
            assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                    () -> publisher.publish("text/plain", new byte[] {2}),
                    "a real-directory replant fails closed on immutable identity (fileKey)");
            assertTrue(Files.isDirectory(sessionDir), "the replant still exists");
            try (Stream<Path> children = Files.list(sessionDir)) {
                assertTrue(children.findAny().isEmpty(),
                        "the replanted directory received nothing");
            }
            // Cleanup must refuse to delete the replacement and report the leak instead.
            RuntimeException cleanup = assertThrows(RuntimeException.class, publisher::close,
                    "close reports the replant as a leak");
            assertTrue(cleanup.getMessage().contains("replaced"),
                    "the leak message names the replacement: " + cleanup.getMessage());
            assertTrue(Files.isDirectory(sessionDir),
                    "the replacement is never deleted by cleanup");
            // The moved original session's contents were deleted through the retained session
            // fd (directory-relative cleanup of OUR data); the replacement received nothing.
            try (Stream<Path> movedChildren = Files.list(moved)) {
                assertTrue(movedChildren.findAny().isEmpty(),
                        "the moved original session was cleaned through its fd");
            }
            try (Stream<Path> children = Files.list(sessionDir)) {
                assertTrue(children.findAny().isEmpty(),
                        "the replanted directory received nothing");
            }
            Files.deleteIfExists(sessionDir); // the replacement, after the leak was reported
            Files.deleteIfExists(moved); // the emptied original
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
                            directoryCreationAttributes(
                                    java.nio.file.attribute.UserPrincipal parentOwner) {
                        return new java.nio.file.attribute.FileAttribute<?>[0];
                    }

                    @Override public java.nio.file.attribute.FileAttribute<?>[]
                            fileCreationAttributes(
                                    java.nio.file.attribute.UserPrincipal sessionOwner) {
                        return new java.nio.file.attribute.FileAttribute<?>[0];
                    }

                    @Override public void verifyDirectory(Path dir) throws java.io.IOException {
                        throw new java.io.IOException("injected-owner-only-failure");
                    }

                    @Override public void validateParent(Path parent) {
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
    void parentPolicyDenyingOthersPassesAndPermissiveParentFails() throws Exception {
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (!posix) {
            return; // parent-mode validation is POSIX-specific
        }
        // A permissive parent (group/other writable, no sticky bit) must be rejected.
        Path permissive = tempDir.resolve("permissive");
        Files.createDirectories(permissive);
        Files.setPosixFilePermissions(permissive,
                PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                () -> new TmpDirArtifactPublisher(1024, 4096, 4, permissive, null),
                "a parent that allows other-principal rename/delete-child fails closed");
        // The owner-only parent (the @TempDir) is accepted.
        try (TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null)) {
            publisher.publish("text/plain", new byte[] {1});
        }
    }

    @Test
    void aclEntriesGrantOnlyOwnerAndRequiredPrincipals() throws Exception {
        UserPrincipal owner = FileSystems.getDefault().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        List<AclEntry> entries =
                TmpDirArtifactPublisher.AclOwnerOnly.ownerOnlyEntries(owner);
        assertFalse(entries.isEmpty(), "the ACL is not empty");
        for (AclEntry entry : entries) {
            assertEquals(AclEntryType.ALLOW, entry.type(),
                    "every owner-only entry is an ALLOW");
            assertTrue(isOwnerOrRequired(entry.principal(), owner),
                    "every granted principal is the owner or a platform-required principal: "
                            + entry.principal());
        }
        // Real verification logic accepts the constructed owner-only ACL.
        TmpDirArtifactPublisher.AclOwnerOnly.verifyOwnerOnly(entries, owner, tempDir);
        // A foreign ALLOW principal must be rejected by the real verification logic.
        List<AclEntry> tampered = new ArrayList<>(entries);
        tampered.add(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(foreignPrincipal())
                .setPermissions(EnumSet.of(AclEntryPermission.WRITE_DATA))
                .build());
        assertThrows(java.io.IOException.class,
                () -> TmpDirArtifactPublisher.AclOwnerOnly.verifyOwnerOnly(
                        tampered, owner, tempDir),
                "a foreign ALLOW principal is rejected");
        // A DENY entry for the owner is also rejected.
        List<AclEntry> denied = new ArrayList<>(entries);
        denied.add(AclEntry.newBuilder()
                .setType(AclEntryType.DENY)
                .setPrincipal(owner)
                .setPermissions(EnumSet.of(AclEntryPermission.WRITE_DATA))
                .build());
        assertThrows(java.io.IOException.class,
                () -> TmpDirArtifactPublisher.AclOwnerOnly.verifyOwnerOnly(
                        denied, owner, tempDir),
                "a DENY entry for the owner is rejected");
    }

    @Test
    void aclDirectoryCreationAttributeIsAtomicAndOwnerOnly() throws Exception {
        UserPrincipal owner = FileSystems.getDefault().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        FileAttribute<?> attribute =
                TmpDirArtifactPublisher.AclOwnerOnly.aclAttribute(owner);
        assertEquals("acl:acl", attribute.name(),
                "the attribute applies the owner-only ACL atomically at creation");
        @SuppressWarnings("unchecked")
        List<AclEntry> entries = (List<AclEntry>) attribute.value();
        TmpDirArtifactPublisher.AclOwnerOnly.verifyOwnerOnly(entries, owner, tempDir);
    }

    @Test
    void aclParentPolicyRejectsForeignRenameDeleteChild() throws Exception {
        UserPrincipal owner = FileSystems.getDefault().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        AclFileAttributeView view = Files.getFileAttributeView(tempDir, AclFileAttributeView.class);
        Assumptions.assumeTrue(view != null, "an ACL view is available");
        List<AclEntry> original = new ArrayList<>(view.getAcl());
        try {
            // A parent ACL that grants a foreign principal WRITE_DATA / DELETE_CHILD must be
            // rejected by the real validation logic.
            view.setAcl(List.of(
                    AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(owner)
                            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                            .build(),
                    AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(foreignPrincipal())
                            .setPermissions(EnumSet.of(AclEntryPermission.WRITE_DATA,
                                    AclEntryPermission.DELETE_CHILD))
                            .build()));
            assertThrows(java.io.IOException.class,
                    () -> new TmpDirArtifactPublisher.AclOwnerOnly()
                            .validateParent(tempDir),
                    "a parent ACL granting a foreign principal write/delete-child is rejected");
        } finally {
            view.setAcl(original);
        }
    }

    private static boolean isOwnerOrRequired(UserPrincipal principal, UserPrincipal owner) {
        String name = principal.getName();
        return principal.equals(owner)
                || name.equals("SYSTEM") || name.endsWith("\\SYSTEM")
                || name.equals("Administrators") || name.endsWith("\\Administrators");
    }

    @Test
    void constructionFailsWhenFileKeyOrOwnerIsUnavailable() throws Exception {
        // Parent identity unavailable (null fileKey): construction fails closed.
        TmpDirArtifactPublisher.IdentitySource noParentKey = new TmpDirArtifactPublisher
                .IdentitySource() {
            @Override public Object fileKey(Path path) {
                return null;
            }

            @Override public java.nio.file.attribute.UserPrincipal owner(Path path)
                    throws java.io.IOException {
                return Files.getOwner(path);
            }
        };        assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                () -> new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null, noParentKey),
                "construction fails closed when the parent fileKey is unavailable");
        // Session identity unavailable (null owner after the parent is trusted): fail closed
        // and remove the created directory.
        TmpDirArtifactPublisher.IdentitySource noSessionOwner =
                new TmpDirArtifactPublisher.IdentitySource() {
                    @Override public Object fileKey(Path path) throws java.io.IOException {
                        return Files.readAttributes(path, BasicFileAttributes.class,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey();
                    }

                    @Override public java.nio.file.attribute.UserPrincipal owner(Path path)
                            throws java.io.IOException {
                        return path.equals(tempDir) ? Files.getOwner(path) : null;
                    }
                };
        assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                () -> new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null, noSessionOwner),
                "construction fails closed when the session owner is unavailable");
        try (Stream<Path> children = Files.list(tempDir)) {
            assertTrue(children.noneMatch(path ->
                            path.getFileName().toString().startsWith("gdx-markup-")),
                    "the partially created session directory is removed when identity is "
                            + "unavailable");
        }
    }

    @Test
    void replantDifferingOwnerIsRefusedAndNeverDeleted() throws Exception {
        TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null);
        try {
            Path sessionDir = publisher.sessionDir();
            byte[] payload = {1};
            publisher.publish("text/plain", payload);
            String digest = sha256(payload);
            // Sentinel child and nested sentinel tree planted inside the owned session: their
            // contents must remain intact when the same inode is re-owned by another principal.
            Path sentinelFile = sessionDir.resolve("sentinel.txt");
            Files.writeString(sentinelFile, "keep-me");
            Path sentinelTree = sessionDir.resolve("sentinel-tree");
            Files.createDirectories(sentinelTree);
            Path sentinelNested = sentinelTree.resolve("nested.txt");
            Files.writeString(sentinelNested, "nested-content");
            // Swap the identity source so the session path now reports a DIFFERENT owner (a
            // real directory re-owned by another principal): every path must fail closed on
            // the owner inequality even though the fileKey still matches.
            publisher.identitySource = new TmpDirArtifactPublisher.IdentitySource() {
                @Override public Object fileKey(Path path) throws java.io.IOException {
                    return Files.readAttributes(path, BasicFileAttributes.class,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey();
                }

                @Override public java.nio.file.attribute.UserPrincipal owner(Path path) {
                    return new FakePrincipal("someone-else");
                }
            };
            assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                    () -> publisher.publish("text/plain", new byte[] {2}),
                    "a session directory re-owned by another principal fails closed on publish");
            // Cleanup must refuse to delete the re-owned session — neither its children nor
            // the entry — and report the leak, preserving the verification failure as the
            // aggregated cause.
            RuntimeException cleanup = assertThrows(RuntimeException.class, publisher::close,
                    "close reports the re-owned session as a leak");
            assertTrue(cleanup.getMessage().contains("replaced")
                            || cleanup.getMessage().contains("re-owned")
                            || cleanup.getMessage().contains("owner changed"),
                    "the leak message names the re-ownership: " + cleanup.getMessage());
            assertTrue(cleanup.getCause() instanceof ArtifactReference.ArtifactUnavailableException,
                    "the verification failure is aggregated as the cause: " + cleanup.getCause());
            assertTrue(Files.isDirectory(sessionDir),
                    "the re-owned session directory is never deleted by cleanup");
            assertArrayEquals(payload, Files.readAllBytes(sessionDir.resolve(digest)),
                    "the published artifact survives a re-owned close with intact contents");
            assertEquals("keep-me", Files.readString(sentinelFile),
                    "the sentinel child survives a re-owned close with intact contents");
            assertEquals("nested-content", Files.readString(sentinelNested),
                    "the nested sentinel tree survives a re-owned close with intact contents");
            // Idempotent retry: the first close already closed the anchors, so a second close
            // reports the same leak without new stream errors and still deletes nothing.
            RuntimeException retry = assertThrows(RuntimeException.class, publisher::close,
                    "a retried close reports the same re-ownership leak");
            assertTrue(retry.getMessage().contains("replaced")
                            || retry.getMessage().contains("re-owned")
                            || retry.getMessage().contains("owner changed"),
                    "the retried leak message names the re-ownership: " + retry.getMessage());
            assertFalse(retry.getMessage().contains("failed to close"),
                    "the anchors were closed once; the retry adds no stream-close error");
            assertEquals("keep-me", Files.readString(sentinelFile),
                    "the sentinel child still survives the retried close");
            // The re-owned directory (with its intact contents) is left for the finally block,
            // which restores the real identity source and completes the cleanup.
        } finally {
            publisher.identitySource = new TmpDirArtifactPublisher.RealIdentitySource();
            try {
                publisher.close(); // real identity now matches: cleanup completes
            } catch (RuntimeException expected) {
                // the session dir was deleted above; an already-gone close is a no-op
            }
        }
    }

    @Test
    void precomputedFileAttributesUseCapturedSessionOwner() throws Exception {
        java.nio.file.attribute.UserPrincipal realOwner =
                Files.getOwner(tempDir, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        TmpDirArtifactPublisher publisher =
                new TmpDirArtifactPublisher(1024, 4096, 4, tempDir, null);
        Path sessionDir = publisher.sessionDir();
        try {
            // The precomputed file attributes must be derived from the captured immutable
            // session owner — never re-read from an absolute path after construction.
            java.nio.file.attribute.FileAttribute<?>[] attrs =
                    publisher.fileCreationAttributes();
            assertEquals(1, attrs.length, "one precomputed file creation attribute");
            if ("acl:acl".equals(attrs[0].name())) {
                @SuppressWarnings("unchecked")
                List<AclEntry> entries = (List<AclEntry>) attrs[0].value();
                TmpDirArtifactPublisher.AclOwnerOnly.verifyOwnerOnly(entries, realOwner, tempDir);
            } else {
                // POSIX rw-------: owner-only by construction; nothing to compare by principal.
                assertEquals("posix:permissions", attrs[0].name());
            }
            // A replant (rename + fresh real directory at the same name) never grants the
            // replacement owner: the anchored fd still points at the original directory.
            publisher.publish("text/plain", new byte[] {1});
            Path moved = tempDir.resolve("moved-" + System.nanoTime());
            Files.move(sessionDir, moved);
            Files.createDirectory(sessionDir); // replant a fresh real directory
            assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                    () -> publisher.publish("text/plain", new byte[] {2}),
                    "the replanted directory fails closed (fileKey identity)");
            // Close reports the replant as a leak instead of deleting the replacement.
            RuntimeException cleanup = assertThrows(RuntimeException.class, publisher::close,
                    "close reports the replant as a leak");
            assertTrue(cleanup.getMessage().contains("replaced"),
                    "the leak message names the replacement: " + cleanup.getMessage());
            assertTrue(Files.isDirectory(sessionDir),
                    "the replacement is never deleted by cleanup");
            // The moved original's contents were cleaned through the anchored fd.
            try (Stream<Path> movedChildren = Files.list(moved)) {
                assertTrue(movedChildren.findAny().isEmpty(),
                        "the moved original session was cleaned through its fd");
            }
            Files.deleteIfExists(sessionDir); // the replacement, after the leak was reported
            Files.deleteIfExists(moved); // the emptied original
        } finally {
            publisher.close(); // already closed or idempotent
        }
    }

    @Test
    void aclRejectsMaliciousLookalikePrincipals() throws Exception {
        UserPrincipal owner = FileSystems.getDefault().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        // Malicious principals whose NAMES resemble SYSTEM/Administrators but which are NOT
        // the exact resolved UserPrincipal objects must be rejected by exact-equality
        // verification (never name suffix/substring matching).
        UserPrincipal domainSystem = new FakePrincipal("DOMAIN\\SYSTEM");
        UserPrincipal notAdministrators = new FakePrincipal("NotAdministrators");
        List<AclEntry> entries = new ArrayList<>(
                TmpDirArtifactPublisher.AclOwnerOnly.ownerOnlyEntries(owner));
        entries.add(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(domainSystem)
                .setPermissions(EnumSet.of(AclEntryPermission.WRITE_DATA))
                .build());
        assertThrows(java.io.IOException.class,
                () -> TmpDirArtifactPublisher.AclOwnerOnly.verifyOwnerOnly(
                        entries, owner, tempDir),
                "DOMAIN\\SYSTEM is not the exact resolved SYSTEM principal and is rejected");
        List<AclEntry> adminLookalike = new ArrayList<>(
                TmpDirArtifactPublisher.AclOwnerOnly.ownerOnlyEntries(owner));
        adminLookalike.add(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(notAdministrators)
                .setPermissions(EnumSet.of(AclEntryPermission.WRITE_DATA))
                .build());
        assertThrows(java.io.IOException.class,
                () -> TmpDirArtifactPublisher.AclOwnerOnly.verifyOwnerOnly(
                        adminLookalike, owner, tempDir),
                "NotAdministrators is not the exact resolved Administrators principal");
    }

    @Test
    void aclParentPolicyRejectsForeignWriteAclAndWriteOwner() throws Exception {
        UserPrincipal owner = FileSystems.getDefault().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        AclFileAttributeView view = Files.getFileAttributeView(tempDir, AclFileAttributeView.class);
        Assumptions.assumeTrue(view != null, "an ACL view is available");
        List<AclEntry> original = new ArrayList<>(view.getAcl());
        try {
            for (AclEntryPermission foreign : new AclEntryPermission[] {
                    AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER}) {
                view.setAcl(List.of(
                        AclEntry.newBuilder()
                                .setType(AclEntryType.ALLOW)
                                .setPrincipal(owner)
                                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                                .build(),
                        AclEntry.newBuilder()
                                .setType(AclEntryType.ALLOW)
                                .setPrincipal(foreignPrincipal())
                                .setPermissions(EnumSet.of(foreign))
                                .build()));
                assertThrows(java.io.IOException.class,
                        () -> new TmpDirArtifactPublisher.AclOwnerOnly()
                                .validateParent(tempDir),
                        "a parent ACL granting a foreign principal " + foreign
                                + " is rejected");
            }
        } finally {
            view.setAcl(original);
        }
    }

    /** A minimal principal with an arbitrary name, for testing exact-equality ACL checks. */
    private static final class FakePrincipal implements UserPrincipal {
        private final String name;

        FakePrincipal(String name) {
            this.name = name;
        }

        @Override public String getName() {
            return name;
        }

        @Override public boolean equals(Object other) {
            return other instanceof FakePrincipal fake && fake.name.equals(name);
        }

        @Override public int hashCode() {
            return name.hashCode();
        }

        @Override public String toString() {
            return name;
        }
    }

    private static UserPrincipal foreignPrincipal() throws Exception {
        // "nobody" exists on Unix; on Windows, use "Everyone" when resolvable.
        try {
            return FileSystems.getDefault().getUserPrincipalLookupService()
                    .lookupPrincipalByName("nobody");
        } catch (java.io.IOException nobodyMissing) {
            try {
                return FileSystems.getDefault().getUserPrincipalLookupService()
                        .lookupPrincipalByName("Everyone");
            } catch (java.io.IOException everyoneMissing) {
                throw new java.io.IOException(
                        "no foreign principal available", everyoneMissing);
            }
        }
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
        assertTrue(first.getMessage().contains("failed to delete")
                        || first.getSuppressed().length > 0,
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
