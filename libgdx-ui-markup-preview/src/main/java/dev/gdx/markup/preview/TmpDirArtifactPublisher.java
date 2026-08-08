package dev.gdx.markup.preview;

import dev.gdx.uiharness.mcp.ArtifactReference;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * {@link ArtifactReference.Publisher} for the preview: one instance owns one unpredictable
 * session directory created directly in the OS temporary directory with create-new semantics and
 * an owner-only access policy, and persists payloads keyed by their full SHA-256 digest.
 *
 * <p>Trust model: the canonical parent and session identities are captured at construction and
 * re-verified before every path operation (fail closed on any change). Where the provider
 * supports {@link SecureDirectoryStream}, temporary-file creation, writes, and no-follow reads
 * are directory-relative; on providers without it, every absolute operation is preceded by an
 * owner-only ACL verification and identity check. Install is atomic and never replaces: the
 * digest name is created as a hard link to the fully written temporary file, so an existing
 * digest entry (including a pre-planted link) fails with {@link FileAlreadyExistsException} and
 * is then opened no-follow and compared — identical content deduplicates without extra quota,
 * any mismatch is a collision.
 *
 * <p>Per-file, cumulative byte, and count quotas are enforced before retention under one lock.
 * {@link #close()} attempts every temp/artifact/session deletion, aggregates failures, stays
 * idempotent and retry-safe, and never follows a symbolic link out of the owned directory.
 */
final class TmpDirArtifactPublisher implements ArtifactReference.Publisher, AutoCloseable {
    /** Production defaults: fixed safe bounds for one preview session. */
    private static final long DEFAULT_MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final long DEFAULT_MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    private static final int DEFAULT_MAX_COUNT = 64;

    private static final String SESSION_PREFIX = "gdx-markup-";
    private static final String TEMP_PREFIX = "gdx-tmp-";
    private static final int NAME_RETRIES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path sessionDir;
    private final Path sessionReal;
    private final Path parentReal;
    private final OwnerOnlyPolicy ownerPolicy;
    private final SecureDirectoryStream<Path> dirStream;
    private final long maxFileBytes;
    private final long maxTotalBytes;
    private final int maxCount;
    private long totalBytes;
    private int count;
    private boolean closed;
    private boolean streamClosed;

    /** Production constructor: fixed safe defaults and a fresh owner-only session directory. */
    TmpDirArtifactPublisher() {
        this(DEFAULT_MAX_FILE_BYTES, DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_COUNT,
                Path.of(System.getProperty("java.io.tmpdir")), null);
    }

    /** Test constructor: bounded quotas for deterministic tests. */
    TmpDirArtifactPublisher(long maxFileBytes, long maxTotalBytes, int maxCount) {
        this(maxFileBytes, maxTotalBytes, maxCount,
                Path.of(System.getProperty("java.io.tmpdir")), null);
    }

    /**
     * Test seam constructor: bounded quotas, an explicit parent (so tests can pre-plant or
     * replace the parent/session), and an explicit owner-only policy (so tests can simulate
     * ACL success and failure). A {@code null} policy selects the platform policy.
     */
    TmpDirArtifactPublisher(long maxFileBytes, long maxTotalBytes, int maxCount,
            Path parent, OwnerOnlyPolicy policy) {
        if (maxFileBytes <= 0 || maxTotalBytes <= 0 || maxCount <= 0) {
            throw new IllegalArgumentException("quotas must be positive");
        }
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.maxCount = maxCount;
        Path created = null;
        try {
            if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new ArtifactReference.ArtifactUnavailableException(
                        "artifact parent is not a trusted directory: " + parent);
            }
            Path parentReal = parent.toRealPath();
            OwnerOnlyPolicy effective = policy != null ? policy : OwnerOnlyPolicy.detect(parentReal);
            created = createSessionDirectory(parentReal, effective);
            Path sessionReal = created.toRealPath();
            if (sessionReal.getParent() == null || !sessionReal.getParent().equals(parentReal)) {
                throw new ArtifactReference.ArtifactUnavailableException(
                        "session directory escaped its parent: " + created);
            }
            this.sessionDir = created;
            this.sessionReal = sessionReal;
            this.parentReal = parentReal;
            this.ownerPolicy = effective;
            this.dirStream = openSecureStream(sessionReal);
        } catch (RuntimeException | IOException failure) {
            if (created != null) {
                try {
                    deleteOwnedBestEffort(created);
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure instanceof ArtifactReference.ArtifactUnavailableException available
                    ? available
                    : unavailable("unable to create artifact session directory: "
                                    + failure.getMessage(),
                            failure);
        }
    }

    /** Returns the owned session directory (test seam). */
    Path sessionDir() {
        return sessionDir;
    }

    /** Resolves one published digest to bytes for verification (instance, session-relative). */
    byte[] readBack(String sha256) throws IOException {
        verifyTrusted();
        byte[] existing = readExisting(sha256, false);
        if (existing == null) {
            throw new IOException("no artifact for digest " + sha256);
        }
        return existing;
    }

    @Override
    public synchronized ArtifactReference publish(String mediaType, byte[] content) {
        if (closed) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "artifact publisher is closed");
        }
        if (content.length > maxFileBytes) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "artifact exceeds the per-file quota of " + maxFileBytes + " bytes");
        }
        String sha256 = digest(content);
        verifyTrusted();
        // Existing digest: open no-follow and compare — identical deduplicates (no extra
        // quota), mismatch is a collision, a pre-planted link is refused.
        byte[] existing = readExisting(sha256, false);
        if (existing != null) {
            if (Arrays.equals(existing, content)) {
                return reference(mediaType, content.length, sha256);
            }
            throw new ArtifactReference.ArtifactUnavailableException(
                    "artifact digest collision at " + sha256);
        }
        // Quotas are enforced before retention: a rejected new artifact leaves nothing behind.
        if (count >= maxCount) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "artifact count quota of " + maxCount + " reached");
        }
        if (totalBytes + content.length > maxTotalBytes) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "artifact total quota of " + maxTotalBytes + " bytes exceeded");
        }
        String tempName = null;
        boolean installed = false;
        try {
            tempName = createTemp(content);
            Path target = sessionReal.resolve(sha256);
            Path temp = sessionReal.resolve(tempName);
            try {
                // Atomic no-replace install: hard-link the fully written temp to the digest
                // name; an existing entry (file, directory, or pre-planted link) fails here.
                // Providers without hard links fall back to a no-REPLACE atomic move, which
                // also throws FileAlreadyExistsException when the target exists.
                try {
                    Files.createLink(target, temp);
                } catch (UnsupportedOperationException noHardLinks) {
                    Files.move(temp, target); // no REPLACE_EXISTING: never replaces
                }
                installed = true;
            } catch (FileAlreadyExistsException raced) {
                // A concurrent publisher (or a pre-existing entry) won the digest name.
                byte[] racedExisting = readExisting(sha256, true);
                if (racedExisting != null && Arrays.equals(racedExisting, content)) {
                    deleteTempQuietly(temp); // dedupe: drop our temp, keep the existing
                    return reference(mediaType, content.length, sha256); // no extra quota
                }
                throw new ArtifactReference.ArtifactUnavailableException(
                        "artifact digest collision at " + sha256);
            }
            // Installed (or deduplicated): the temp name is ours to drop; a failure here is
            // best-effort because the artifact is already live under the digest name (the
            // owned session cleanup will remove any leftover temp).
            deleteTempQuietly(temp);
            tempName = null;
            if (installed) {
                totalBytes += content.length;
                count++;
            }
            return reference(mediaType, content.length, sha256);
        } catch (IOException | RuntimeException failure) {
            RuntimeException primary = failure instanceof RuntimeException runtime
                    ? runtime
                    : unavailable("unable to persist artifact: " + failure.getMessage(),
                            failure);
            if (tempName != null) {
                try {
                    deleteTemp(tempName);
                } catch (IOException cleanup) {
                    primary.addSuppressed(cleanup);
                }
            }
            if (primary instanceof ArtifactReference.ArtifactUnavailableException unavailable) {
                throw unavailable;
            }
            throw primary;
        }
    }

    /** Best-effort removal of a temp file after a successful install/dedupe: never throws. */
    private void deleteTempQuietly(Path temp) {
        try {
            deleteTemp(temp.getFileName().toString());
        } catch (IOException ignored) {
            // the artifact is already live; a leftover temp is removed by owned cleanup
        }
    }

    private ArtifactReference reference(String mediaType, int byteLength, String sha256) {
        return new ArtifactReference("artifact:" + sha256.substring(0, 32), mediaType,
                byteLength, sha256);
    }

    /** Builds an unavailable-artifact failure preserving the cause (the published exception
     * type only offers a message constructor). */
    private static ArtifactReference.ArtifactUnavailableException unavailable(
            String message, Throwable cause) {
        ArtifactReference.ArtifactUnavailableException failure =
                new ArtifactReference.ArtifactUnavailableException(message);
        failure.initCause(cause);
        return failure;
    }

    /**
     * Creates the unpredictable session directory directly in the canonical parent using
     * create-new semantics, then establishes and verifies the owner-only policy. On policy
     * failure the created directory is removed (staged ownership) and the failure propagates.
     */
    private static Path createSessionDirectory(Path parentReal, OwnerOnlyPolicy policy) {
        for (int attempt = 0; attempt < NAME_RETRIES; attempt++) {
            Path candidate = parentReal.resolve(SESSION_PREFIX + randomHex(16));
            try {
                Files.createDirectory(candidate, policy.directoryCreationAttributes());
            } catch (FileAlreadyExistsException collision) {
                continue; // retry with a fresh unpredictable name
            } catch (IOException failure) {
                throw unavailable(
                        "unable to create artifact session directory: " + failure.getMessage(),
                        failure);
            }
            try {
                policy.applyDirectory(candidate);
                policy.verifyDirectory(candidate);
                return candidate;
            } catch (RuntimeException | IOException failure) {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure instanceof RuntimeException runtime
                        ? runtime
                        : unavailable(
                                "unable to secure artifact session directory: "
                                        + failure.getMessage(),
                                failure);
            }
        }
        throw new ArtifactReference.ArtifactUnavailableException(
                "unable to create a unique artifact session directory");
    }

    /** Opens the session directory as a {@link SecureDirectoryStream} when the provider
     * supports it; returns {@code null} to select the identity-checked absolute fallback. */
    private static SecureDirectoryStream<Path> openSecureStream(Path sessionReal) {
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(sessionReal);
            if (stream instanceof SecureDirectoryStream<?> secure) {
                @SuppressWarnings("unchecked")
                SecureDirectoryStream<Path> typed = (SecureDirectoryStream<Path>) secure;
                return typed;
            }
            stream.close();
        } catch (IOException ignored) {
            // provider without directory-relative streams: fall back to checked absolute ops
        }
        return null;
    }

    /** Fails closed unless the session directory still is the same real, owner-only directory. */
    private void verifyTrusted() {
        try {
            if (Files.isSymbolicLink(sessionDir)
                    || !Files.isDirectory(sessionDir, LinkOption.NOFOLLOW_LINKS)) {
                throw new ArtifactReference.ArtifactUnavailableException(
                        "artifact session directory is not a trusted directory");
            }
            Path real = sessionDir.toRealPath();
            if (!real.equals(sessionReal) || !real.getParent().equals(parentReal)) {
                throw new ArtifactReference.ArtifactUnavailableException(
                        "artifact session directory identity changed");
            }
            ownerPolicy.verifyDirectory(sessionDir);
        } catch (IOException failure) {
            throw unavailable(
                    "unable to verify artifact session directory: " + failure.getMessage(),
                    failure);
        }
    }

    /**
     * Reads an existing digest entry without following links. Returns {@code null} when
     * absent. A pre-planted symbolic link is refused; when {@code rejectOther} is set a
     * non-file entry is a collision (used after an install race), otherwise it is treated as
     * absent so the atomic install gate reports it deterministically.
     */
    private byte[] readExisting(String name, boolean rejectOther) {
        Path path = sessionReal.resolve(name);
        if (Files.isSymbolicLink(path)) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "refusing to read through a pre-planted symbolic link at " + name);
        }
        try {
            if (dirStream != null) {
                try (SeekableByteChannel channel = dirStream.newByteChannel(
                        Path.of(name), Set.of(StandardOpenOption.READ,
                                LinkOption.NOFOLLOW_LINKS))) {
                    return readBounded(channel);
                }
            }
            // No directory-relative stream: re-verify identity/owner-only before every
            // fallback path operation (fail closed if trust cannot be proven).
            verifyTrusted();
            try (SeekableByteChannel channel = Files.newByteChannel(path,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                return readBounded(channel);
            }
        } catch (java.nio.file.NoSuchFileException absent) {
            return null;
        } catch (IOException failure) {
            boolean occupied = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
            if (occupied && rejectOther) {
                throw new ArtifactReference.ArtifactUnavailableException(
                        "artifact digest path is occupied by a non-file entry: " + name);
            }
            if (occupied) {
                return null; // let the atomic install gate report it deterministically
            }
            throw unavailable(
                    "unable to read artifact digest " + name + ": " + failure.getMessage(),
                    failure);
        }
    }

    private byte[] readBounded(SeekableByteChannel channel) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long total = 0;
        int read;
        while ((read = channel.read(buffer)) != -1) {
            buffer.flip();
            total += read;
            if (total > maxFileBytes + 1) {
                throw new IOException("existing artifact exceeds the per-file quota");
            }
            out.write(buffer.array(), 0, read);
            buffer.clear();
        }
        return out.toByteArray();
    }

    /** Creates a unique temporary file (create-new), writes the content, and applies the
     * owner-only file policy; returns the temp name. On failure the partial file is removed. */
    private String createTemp(byte[] content) throws IOException {
        for (int attempt = 0; attempt < NAME_RETRIES; attempt++) {
            String name = TEMP_PREFIX + randomHex(16);
            Path path = sessionReal.resolve(name);
            try {
                if (dirStream != null) {
                    try (SeekableByteChannel channel = dirStream.newByteChannel(
                            Path.of(name),
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS))) {
                        writeAll(channel, content);
                    }
                } else {
                    verifyTrusted(); // no directory-relative stream: re-verify before the op
                    try (SeekableByteChannel channel = Files.newByteChannel(path,
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS))) {
                        writeAll(channel, content);
                    }
                }
                ownerPolicy.applyFile(path);
                return name;
            } catch (FileAlreadyExistsException collision) {
                continue; // retry with a fresh unpredictable name
            } catch (IOException | RuntimeException failure) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure instanceof IOException io ? io
                        : new IOException("unable to create temporary artifact file: "
                                + failure.getMessage(), failure);
            }
        }
        throw new IOException("unable to create a unique temporary artifact file");
    }

    private static void writeAll(SeekableByteChannel channel, byte[] content) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private void deleteTemp(String name) throws IOException {
        if (dirStream != null) {
            try {
                dirStream.deleteFile(Path.of(name));
                return;
            } catch (java.nio.file.NoSuchFileException alreadyGone) {
                return;
            }
        }
        verifyTrusted(); // no directory-relative stream: re-verify before the op
        Files.deleteIfExists(sessionReal.resolve(name));
    }

    /**
     * Idempotent, retry-safe close: refuses publishes, closes the directory stream once, and
     * attempts every temp/artifact/session deletion, aggregating failures (first failure
     * primary, later failures suppressed) so a second close retries what failed.
     */
    @Override
    public synchronized void close() {
        closed = true;
        RuntimeException failure = null;
        if (dirStream != null && !streamClosed) {
            streamClosed = true;
            try {
                dirStream.close();
            } catch (IOException streamFailure) {
                failure = new IllegalStateException(
                        "failed to close artifact session directory stream", streamFailure);
            }
        }
        RuntimeException deleteFailure = deleteOwnedAggregating(sessionReal);
        if (deleteFailure != null) {
            if (failure == null) {
                failure = deleteFailure;
            } else {
                failure.addSuppressed(deleteFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Deletes the owned tree (deepest first, never following links), attempting every entry
     * and aggregating failures; already-deleted entries are fine (retry-safe). */
    private static RuntimeException deleteOwnedAggregating(Path owned) {
        RuntimeException primary = null;
        if (!Files.exists(owned, LinkOption.NOFOLLOW_LINKS)) {
            return null; // already deleted: idempotent
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(owned)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException | RuntimeException failure) {
                    primary = aggregate(primary,
                            new IllegalStateException("failed to delete " + path, failure));
                }
            }
        } catch (IOException | RuntimeException walkFailure) {
            primary = aggregate(primary, new IllegalStateException(
                    "failed to walk owned session directory " + owned, walkFailure));
        }
        return primary;
    }

    private static RuntimeException aggregate(RuntimeException primary, RuntimeException next) {
        if (primary == null) {
            return next;
        }
        primary.addSuppressed(next);
        return primary;
    }

    /** Best-effort recursive delete used only to undo a partially constructed session. */
    private static void deleteOwnedBestEffort(Path owned) {
        RuntimeException failure = deleteOwnedAggregating(owned);
        if (failure != null) {
            throw failure;
        }
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    /** Owner-only access policy abstraction: POSIX when available, ACL otherwise; the
     * platform policy is selected by {@link #detect(Path)}, and construction fails closed
     * when neither can be established or verified. */
    interface OwnerOnlyPolicy {
        /** Attributes to pass at session-directory creation (owner-only when supported). */
        FileAttribute<?>[] directoryCreationAttributes();

        /** Applies an owner-only policy to a fresh directory; throws if it cannot be set. */
        void applyDirectory(Path dir) throws IOException;

        /** Verifies the owner-only policy holds; throws if trust cannot be proven. */
        void verifyDirectory(Path dir) throws IOException;

        /** Applies an owner-only policy to a fresh file. */
        void applyFile(Path file) throws IOException;

        static OwnerOnlyPolicy detect(Path dir) {
            Set<String> views = FileSystems.getDefault().supportedFileAttributeViews();
            if (views.contains("posix")) {
                return new PosixOwnerOnly();
            }
            if (views.contains("acl") || Files.getFileAttributeView(
                    dir, AclFileAttributeView.class) != null) {
                return new AclOwnerOnly();
            }
            throw new ArtifactReference.ArtifactUnavailableException(
                    "no owner-only file policy (POSIX or ACL) is available on this platform");
        }
    }

    /** POSIX owner-only policy: {@code rwx------} directories, {@code rw-------} files. */
    static final class PosixOwnerOnly implements OwnerOnlyPolicy {
        private static final Set<PosixFilePermission> DIRECTORY =
                PosixFilePermissions.fromString("rwx------");
        private static final Set<PosixFilePermission> FILE =
                PosixFilePermissions.fromString("rw-------");

        @Override public FileAttribute<?>[] directoryCreationAttributes() {
            return new FileAttribute<?>[] {
                    PosixFilePermissions.asFileAttribute(DIRECTORY)};
        }

        @Override public void applyDirectory(Path dir) throws IOException {
            Files.setPosixFilePermissions(dir, DIRECTORY);
        }

        @Override public void verifyDirectory(Path dir) throws IOException {
            if (!Files.getPosixFilePermissions(dir).equals(DIRECTORY)) {
                throw new IOException("session directory is not owner-only (POSIX): " + dir);
            }
        }

        @Override public void applyFile(Path file) throws IOException {
            Files.setPosixFilePermissions(file, FILE);
        }
    }

    /** ACL owner-only policy for filesystems without POSIX views: only the owner is granted
     * access, and verification re-reads the ACL and rejects any other principal. */
    static final class AclOwnerOnly implements OwnerOnlyPolicy {
        @Override public FileAttribute<?>[] directoryCreationAttributes() {
            return new FileAttribute<?>[0]; // ACL is applied after creation
        }

        private static Set<AclEntryPermission> allPermissions() {
            return EnumSet.allOf(AclEntryPermission.class);
        }

        private static AclFileAttributeView view(Path path) throws IOException {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) {
                throw new IOException("no ACL attribute view for " + path);
            }
            return view;
        }

        @Override public void applyDirectory(Path dir) throws IOException {
            apply(dir);
        }

        @Override public void verifyDirectory(Path dir) throws IOException {
            verify(dir);
        }

        @Override public void applyFile(Path file) throws IOException {
            apply(file);
        }

        private static void apply(Path path) throws IOException {
            AclFileAttributeView view = view(path);
            UserPrincipal owner = view.getOwner();
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(allPermissions())
                    .build();
            view.setAcl(List.of(ownerOnly));
        }

        private static void verify(Path path) throws IOException {
            AclFileAttributeView view = view(path);
            UserPrincipal owner = view.getOwner();
            for (AclEntry entry : view.getAcl()) {
                if (!owner.equals(entry.principal())) {
                    throw new IOException("ACL grants a non-owner principal on " + path
                            + ": " + entry.principal());
                }
                if (entry.type() != AclEntryType.ALLOW) {
                    throw new IOException("ACL denies the owner on " + path);
                }
            }
        }
    }
}
