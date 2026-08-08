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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileOwnerAttributeView;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * {@link ArtifactReference.Publisher} for the preview: one instance owns one unpredictable
 * session directory created directly in the OS temporary directory with create-new semantics and
 * an owner-only access policy applied atomically at creation, and persists payloads keyed by
 * their full SHA-256 digest.
 *
 * <p>Trust model: the immutable parent/session identities — canonical path, {@code fileKey},
 * and owner — are captured at construction and are <b>mandatory</b>: construction fails closed
 * when a fileKey or owner cannot be obtained, and every publish/cleanup/delete path requires the
 * current non-null fileKey <b>and</b> owner to equal the captured ones (never skipped, never a
 * null comparison). A replacement — symlink, fresh real directory, or a directory owned by a
 * different principal — fails closed and is never deleted by cleanup (the leak is reported).
 * Owner-only directory/file attributes are precomputed once from the captured immutable parent
 * and session owners and passed into relative creation, never re-read from an absolute path.
 *
 * <p>Where the provider supports {@link SecureDirectoryStream}, both the parent and the session
 * directory are held as open directory anchors: temp creation/writes/reads and the temp→target
 * install are directory-relative (an install race is a no-replace
 * {@link FileAlreadyExistsException}, compared no-follow), and cleanup proves the retained
 * session fd's OWN fileKey and owner (name-independent, so a rename/replant cannot substitute
 * for the anchored-original identity) before deleting contents directory-relative, then removes
 * the original session entry through the parent anchor — a same-inode re-ownership deletes
 * neither contents nor entry (the leak is reported), a replaced entry is refused, and a
 * renamed-away original whose inode is still linked elsewhere is cleaned through the fd and
 * reported as a leak. On providers without SDS, every absolute operation is bracketed by a
 * parent+session fileKey/owner recheck, and the parent must hold a validated owner-only policy
 * that denies other-principal rename/delete-child.
 *
 * <p>Per-file, cumulative byte, and count quotas are enforced before retention under one lock;
 * identical content deduplicates without extra quota and mismatches are collisions (never
 * replaced). {@link #close()} aggregates all cleanup failures, is idempotent and retry-safe, and
 * never follows a symbolic link out of the owned directory.
 */
final class TmpDirArtifactPublisher implements ArtifactReference.Publisher, AutoCloseable {
    /** Production defaults: fixed safe bounds for one preview session. */
    private static final long DEFAULT_MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final long DEFAULT_MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    private static final int DEFAULT_MAX_COUNT = 64;

    private static final String SESSION_PREFIX = "gdx-markup-";
    private static final String TEMP_PREFIX = "gdx-tmp-";
    private static final int NAME_RETRIES = 16;
    /** Parent entries scanned before giving up on proving the captured session inode is gone;
     * exceeding the bound (or any scan failure) is treated as still-linked (leak, fail closed). */
    private static final int PARENT_SCAN_LIMIT = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path sessionDir;
    private final Path sessionReal;
    private final Path parentReal;
    private final Object sessionFileKey;
    private final UserPrincipal sessionOwner;
    private final Object parentFileKey;
    private final UserPrincipal parentOwner;
    private final OwnerOnlyPolicy ownerPolicy;
    private final FileAttribute<?>[] fileCreationAttrs;
    private final SecureDirectoryStream<Path> parentStream;
    private final SecureDirectoryStream<Path> dirStream;
    private final long maxFileBytes;
    private final long maxTotalBytes;
    private final int maxCount;
    /** Package-visible seam for immutable identity reads; tests swap in providers with null
     * fileKey/owner or a different owner to prove fail-closed behavior. */
    IdentitySource identitySource;
    private long totalBytes;
    private int count;
    private boolean closed;
    private boolean parentStreamClosed;
    private boolean sessionStreamClosed;

    /** Production constructor: fixed safe defaults and a fresh owner-only session directory. */
    TmpDirArtifactPublisher() {
        this(DEFAULT_MAX_FILE_BYTES, DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_COUNT,
                Path.of(System.getProperty("java.io.tmpdir")), null, null);
    }

    /** Test constructor: bounded quotas for deterministic tests. */
    TmpDirArtifactPublisher(long maxFileBytes, long maxTotalBytes, int maxCount) {
        this(maxFileBytes, maxTotalBytes, maxCount,
                Path.of(System.getProperty("java.io.tmpdir")), null, null);
    }

    /** Test seam constructor: bounded quotas, an explicit parent, and an explicit owner-only
     * policy; a {@code null} policy selects the platform policy. */
    TmpDirArtifactPublisher(long maxFileBytes, long maxTotalBytes, int maxCount,
            Path parent, OwnerOnlyPolicy policy) {
        this(maxFileBytes, maxTotalBytes, maxCount, parent, policy, null);
    }

    /**
     * Test seam constructor: bounded quotas, an explicit parent (so tests can pre-plant or
     * replace the parent/session), an explicit owner-only policy (so tests can simulate ACL
     * success and failure), and an identity source (so tests can simulate providers without
     * fileKey/owner or a different owner). A {@code null} policy selects the platform policy;
     * a {@code null} identity source uses the real filesystem.
     */
    TmpDirArtifactPublisher(long maxFileBytes, long maxTotalBytes, int maxCount,
            Path parent, OwnerOnlyPolicy policy, IdentitySource identitySource) {
        if (maxFileBytes <= 0 || maxTotalBytes <= 0 || maxCount <= 0) {
            throw new IllegalArgumentException("quotas must be positive");
        }
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.maxCount = maxCount;
        this.identitySource = identitySource != null ? identitySource : new RealIdentitySource();
        Path created = null;
        try {
            if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new ArtifactReference.ArtifactUnavailableException(
                        "artifact parent is not a trusted directory: " + parent);
            }
            Path parentReal = parent.toRealPath();
            OwnerOnlyPolicy effective = policy != null ? policy : OwnerOnlyPolicy.detect(parentReal);
            // The parent must deny other-principal rename/delete-child of our session entry.
            effective.validateParent(parentReal);
            // Stable identity is mandatory: a provider without a parent fileKey or owner cannot
            // be trusted, so construction fails closed (never a null comparison later).
            Object parentFileKey = this.identitySource.fileKey(parentReal);
            UserPrincipal parentOwner = this.identitySource.owner(parentReal);
            if (parentFileKey == null || parentOwner == null) {
                throw new ArtifactReference.ArtifactUnavailableException(
                        "artifact parent identity (fileKey/owner) is unavailable: " + parentReal);
            }
            FileAttribute<?>[] directoryCreationAttrs =
                    effective.directoryCreationAttributes(parentOwner);
            created = createSessionDirectory(parentReal, effective, directoryCreationAttrs);
            Path sessionReal = created.toRealPath();
            if (sessionReal.getParent() == null || !sessionReal.getParent().equals(parentReal)) {
                throw new ArtifactReference.ArtifactUnavailableException(
                        "session directory escaped its parent: " + created);
            }
            // Stable identity is mandatory for the session too.
            Object sessionFileKey = this.identitySource.fileKey(created);
            UserPrincipal sessionOwner = this.identitySource.owner(created);
            if (sessionFileKey == null || sessionOwner == null) {
                throw new ArtifactReference.ArtifactUnavailableException(
                        "artifact session identity (fileKey/owner) is unavailable: " + created);
            }
            this.sessionDir = created;
            this.sessionReal = sessionReal;
            this.parentReal = parentReal;
            this.sessionFileKey = sessionFileKey;
            this.sessionOwner = sessionOwner;
            this.parentFileKey = parentFileKey;
            this.parentOwner = parentOwner;
            this.ownerPolicy = effective;
            // Precompute the owner-only file attributes once from the captured immutable
            // session owner; relative creation uses these, never a re-read of an absolute path.
            this.fileCreationAttrs = effective.fileCreationAttributes(sessionOwner);
            this.parentStream = openSecureStream(parentReal);
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

    /** Returns the precomputed owner-only file creation attributes (test seam). */
    FileAttribute<?>[] fileCreationAttributes() {
        return fileCreationAttrs.clone();
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
                if (dirStream != null) {
                    // Directory-relative atomic install: an existing target is a no-replace
                    // race (provider-dependent) caught as FileAlreadyExistsException.
                    dirStream.move(Path.of(tempName), dirStream, Path.of(sha256));
                } else {
                    // No SDS: bracket the absolute install with identity rechecks (fail closed).
                    verifyTrusted();
                    try {
                        Files.createLink(target, temp); // atomic no-replace
                    } catch (UnsupportedOperationException noHardLinks) {
                        Files.move(temp, target); // no REPLACE_EXISTING: never replaces
                    }
                    verifyTrusted();
                }
                installed = true;
            } catch (FileAlreadyExistsException raced) {
                byte[] racedExisting = readExisting(sha256, true);
                deleteTempQuietly(temp);
                tempName = null;
                if (racedExisting != null && Arrays.equals(racedExisting, content)) {
                    return reference(mediaType, content.length, sha256); // concurrent dedupe
                }
                throw new ArtifactReference.ArtifactUnavailableException(
                        "artifact digest collision at " + sha256);
            }
            if (dirStream == null) {
                deleteTempQuietly(temp); // hard-link install leaves the temp name
                tempName = null;
            }
            totalBytes += content.length;
            count++;
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
     * create-new semantics with the precomputed owner-only attributes applied atomically at
     * creation, then verifies the policy. On any failure the created directory is removed
     * (staged ownership).
     */
    private static Path createSessionDirectory(Path parentReal, OwnerOnlyPolicy policy,
            FileAttribute<?>[] creationAttrs) {
        for (int attempt = 0; attempt < NAME_RETRIES; attempt++) {
            Path candidate = parentReal.resolve(SESSION_PREFIX + randomHex(16));
            try {
                Files.createDirectory(candidate, creationAttrs);
            } catch (FileAlreadyExistsException collision) {
                continue; // retry with a fresh unpredictable name
            } catch (IOException failure) {
                throw unavailable(
                        "unable to create artifact session directory: " + failure.getMessage(),
                        failure);
            }
            try {
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

    /** Opens a directory as a {@link SecureDirectoryStream} when the provider supports it;
     * returns {@code null} to select the identity-checked absolute fallback. */
    private static SecureDirectoryStream<Path> openSecureStream(Path realDir) {
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(realDir);
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

    /** Fails closed unless the parent and session still hold their immutable identities
     * (canonical path, mandatory non-null fileKey and owner, all equal) and the owner-only
     * policies still hold. */
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
            verifyIdentity(sessionDir, sessionFileKey, sessionOwner, "session");
            verifyIdentity(parentReal, parentFileKey, parentOwner, "parent");
            ownerPolicy.verifyDirectory(sessionDir);
            ownerPolicy.validateParent(parentReal);
        } catch (IOException failure) {
            throw unavailable(
                    "unable to verify artifact session directory: " + failure.getMessage(),
                    failure);
        }
    }

    /** Fails closed unless the path's immutable fileKey and owner are both non-null and equal
     * to the captured identities — null is never a valid comparison result. */
    private void verifyIdentity(Path path, Object capturedKey, UserPrincipal capturedOwner,
            String role) {
        try {
            Object currentKey = identitySource.fileKey(path);
            if (currentKey == null || !capturedKey.equals(currentKey)) {
                throw new IOException(role + " directory identity (fileKey) missing or changed: "
                        + path);
            }
            UserPrincipal currentOwner = identitySource.owner(path);
            if (currentOwner == null || !capturedOwner.equals(currentOwner)) {
                throw new IOException(role + " directory owner missing or changed: " + path);
            }
        } catch (IOException failure) {
            throw unavailable(
                    role + " identity cannot be proven: " + failure.getMessage(), failure);
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
        } catch (NoSuchFileException absent) {
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

    /** Creates a unique temporary file (create-new, precomputed owner-only attributes) and
     * writes the content; returns the temp name. On failure the partial file is removed. */
    private String createTemp(byte[] content) throws IOException {
        for (int attempt = 0; attempt < NAME_RETRIES; attempt++) {
            String name = TEMP_PREFIX + randomHex(16);
            Path path = sessionReal.resolve(name);
            try {
                if (dirStream != null) {
                    try (SeekableByteChannel channel = dirStream.newByteChannel(
                            Path.of(name),
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS),
                            fileCreationAttrs)) {
                        writeAll(channel, content);
                    }
                } else {
                    verifyTrusted(); // no directory-relative stream: re-verify before the op
                    try (SeekableByteChannel channel = Files.newByteChannel(path,
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS),
                            fileCreationAttrs)) {
                        writeAll(channel, content);
                    }
                }
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
            } catch (NoSuchFileException alreadyGone) {
                return;
            }
        }
        verifyTrusted(); // no directory-relative stream: re-verify before the op
        Files.deleteIfExists(sessionReal.resolve(name));
    }

    /**
     * Idempotent, retry-safe close: refuses publishes, deletes the owned contents
     * directory-relative through the session anchor (or the checked fallback), closes the
     * anchors, then removes the original session entry through the parent anchor. Mandatory
     * identity verification runs BEFORE any deletion: a same-inode re-ownership deletes
     * neither children nor the entry (the leak is reported); a rename/replant never redirects
     * the fd and the replacement entry is refused; each failure is aggregated and a retry
     * re-attempts whatever failed.
     */
    @Override
    public synchronized void close() {
        closed = true;
        RuntimeException failure = null;
        // Mandatory identity verification ahead of deleteContentsDirRelative: never delete the
        // children or the entry of a session whose anchored identity cannot be proven (the
        // retained fd's own fileKey/owner missing/changed) — the leak is reported instead.
        CleanupVerdict verdict = verifyCleanupIdentity();
        boolean mayDeleteContents = verdict.state == CleanupVerdict.State.MATCHES
                || verdict.state == CleanupVerdict.State.REPLACED
                || verdict.state == CleanupVerdict.State.RENAMED
                || verdict.state == CleanupVerdict.State.GONE;
        if (dirStream != null && !sessionStreamClosed) {
            if (mayDeleteContents) {
                failure = aggregate(failure, deleteContents(verdict));
            }
            failure = aggregate(failure, closeStream(sessionDir, dirStream, "session", () ->
                    sessionStreamClosed = true));
        }
        // With the session stream closed (or absent), remove the session entry itself through
        // the parent anchor — identity-verified so a replacement is never deleted.
        if (sessionStreamClosed || dirStream == null) {
            failure = aggregate(failure, deleteSessionEntryThroughParent(verdict));
        }
        if (parentStream != null && !parentStreamClosed) {
            failure = aggregate(failure, closeStream(parentReal, parentStream, "parent", () ->
                    parentStreamClosed = true));
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Mandatory pre-deletion identity verification for cleanup, run ahead of every deletion.
     * The anchored-original identity is proven FIRST through the retained session SDS's own
     * {@link BasicFileAttributeView} fileKey and {@link FileOwnerAttributeView} owner (the fd
     * cannot be redirected by a rename/replant, so this is name-independent): both must be
     * non-null and equal to the captured identities, and the identity-source seam must confirm
     * the owner when readable. A mismatch or unprovable anchored identity yields
     * {@link CleanupVerdict.State#REOWNED}/{@code UNVERIFIABLE} and NOTHING may be deleted —
     * the parent entry's MISSING/REPLACED state never substitutes for it. Only then is the
     * parent-entry state consulted: a replaced entry refuses the replacement while the anchored
     * fd cleans the original; a missing entry whose inode is still linked elsewhere (renamed
     * away) cleans the original through the fd and reports the leak; a missing entry whose
     * inode is truly gone is an idempotent no-op.
     */
    private CleanupVerdict verifyCleanupIdentity() {
        // 1. Anchored-original identity through the retained session SDS (or, when the stream
        //    is closed/absent, through the identity-source seam over the captured path).
        ArtifactReference.ArtifactUnavailableException anchoredFailure = null;
        boolean anchoredOk;
        if (dirStream != null && !sessionStreamClosed) {
            try {
                Object fdKey = null;
                UserPrincipal fdOwner = null;
                BasicFileAttributeView keyView = dirStream.getFileAttributeView(Path.of("."),
                        BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                FileOwnerAttributeView ownerView = dirStream.getFileAttributeView(Path.of("."),
                        FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (keyView != null) {
                    fdKey = keyView.readAttributes().fileKey();
                }
                if (ownerView != null) {
                    fdOwner = ownerView.getOwner();
                }
                anchoredOk = fdKey != null && sessionFileKey.equals(fdKey)
                        && fdOwner != null && sessionOwner.equals(fdOwner);
                if (!anchoredOk) {
                    anchoredFailure = unavailable(
                            "anchored session directory identity (fileKey/owner) missing or "
                                    + "changed: " + sessionDir, null);
                }
            } catch (IOException | RuntimeException failure) {
                anchoredOk = false;
                anchoredFailure = unavailable(
                        "anchored session directory identity cannot be verified: "
                                + failure.getMessage(), failure);
            }
            // The identity-source seam (test hook for a re-owned principal) must also confirm
            // the owner when the name is readable; a gone name leaves the anchored fd as the
            // authority.
            if (anchoredOk) {
                try {
                    UserPrincipal seamOwner = identitySource.owner(sessionReal);
                    if (seamOwner == null || !sessionOwner.equals(seamOwner)) {
                        anchoredOk = false;
                        anchoredFailure = unavailable(
                                "session directory owner missing or changed: " + sessionReal,
                                null);
                    }
                } catch (NoSuchFileException nameGone) {
                    // the captured name is gone; the anchored fd read governs
                } catch (IOException failure) {
                    anchoredOk = false;
                    anchoredFailure = unavailable(
                            "session directory owner cannot be proven: " + failure.getMessage(),
                            failure);
                }
            }
        } else {
            // Session stream closed or absent: verify through the identity-source seam over
            // the captured path. A gone path is an idempotent no-op unless the inode is still
            // linked elsewhere (renamed away).
            if (!Files.exists(sessionReal, LinkOption.NOFOLLOW_LINKS)) {
                return sessionInodeStillLinkedInParent()
                        ? CleanupVerdict.renamed()
                        : CleanupVerdict.gone();
            }
            try {
                verifyIdentity(sessionReal, sessionFileKey, sessionOwner, "session");
                anchoredOk = true;
            } catch (ArtifactReference.ArtifactUnavailableException changed) {
                anchoredOk = false;
                anchoredFailure = changed;
            }
        }
        if (!anchoredOk) {
            return CleanupVerdict.reowned(anchoredFailure);
        }
        // 2. Parent-entry state: which entry — if any — occupies the captured name now.
        if (parentStream != null && !parentStreamClosed) {
            Path name = sessionReal.getFileName();
            try {
                BasicFileAttributeView view = parentStream.getFileAttributeView(name,
                        BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (view == null) {
                    return sessionInodeStillLinkedInParent()
                            ? CleanupVerdict.renamed()
                            : CleanupVerdict.gone();
                }
                if (!sessionFileKey.equals(view.readAttributes().fileKey())) {
                    return CleanupVerdict.replaced();
                }
                return CleanupVerdict.matches();
            } catch (NoSuchFileException alreadyGone) {
                return sessionInodeStillLinkedInParent()
                        ? CleanupVerdict.renamed()
                        : CleanupVerdict.gone();
            } catch (IOException | RuntimeException failure) {
                return CleanupVerdict.unverifiable(unavailable(
                        "session directory identity cannot be verified: "
                                + failure.getMessage(), failure));
            }
        }
        // No parent anchor (or already closed): fall back to path-based reads, idempotent when
        // the session is already gone.
        if (!Files.exists(sessionReal, LinkOption.NOFOLLOW_LINKS)) {
            return sessionInodeStillLinkedInParent()
                    ? CleanupVerdict.renamed()
                    : CleanupVerdict.gone();
        }
        try {
            Object currentKey = identitySource.fileKey(sessionReal);
            if (currentKey == null || !sessionFileKey.equals(currentKey)) {
                return CleanupVerdict.replaced();
            }
            return CleanupVerdict.matches();
        } catch (IOException failure) {
            return CleanupVerdict.unverifiable(unavailable(
                    "session directory identity cannot be verified: "
                            + failure.getMessage(), failure));
        }
    }

    /**
     * Bounded, no-follow scan of the CURRENT parent entries for the one whose fileKey equals
     * the captured session identity, proving whether the session inode is still linked
     * somewhere in the parent (renamed away) and, when it is, where. The scan opens a FRESH
     * directory stream and reads each sibling by absolute path: a directory stream retained
     * across a rename cannot enumerate reliably on every platform (the retained fd's readdir
     * may not reflect the rename), while a fresh stream opened at scan time and path-based
     * stat reads always reflect the current directory state. Returns the current path of the
     * original session inode, or {@code null} when it is not linked in the parent. Exceeding
     * the bound or failing to enumerate the parent is reported as an error (fail closed).
     */
    private Path findSessionInodeInParent() throws IOException {
        try (DirectoryStream<Path> siblings = Files.newDirectoryStream(parentReal)) {
            int scanned = 0;
            for (Path sibling : siblings) {
                if (scanned++ >= PARENT_SCAN_LIMIT) {
                    throw new IOException(
                            "parent scan bound exceeded: " + parentReal);
                }
                if (sibling.getFileName().equals(sessionReal.getFileName())) {
                    continue; // the captured name itself (missing or replaced) is not a link elsewhere
                }
                try {
                    if (sessionFileKey.equals(Files.readAttributes(sibling,
                            BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS).fileKey())) {
                        return sibling;
                    }
                } catch (IOException | RuntimeException unreadable) {
                    // unreadable sibling: skip it
                }
            }
        }
        return null;
    }

    /** Whether the session inode is still linked somewhere in the parent (renamed away). */
    private boolean sessionInodeStillLinkedInParent() {
        try {
            return findSessionInodeInParent() != null;
        } catch (IOException | RuntimeException failure) {
            return true; // cannot prove the inode is gone: fail closed (leak reported)
        }
    }

    /** Deletes the owned contents through the session directory stream (directory-relative),
     * recursing into subdirectories; a rename/replant cannot redirect the fd. */
    private RuntimeException deleteContentsDirRelative() {
        RuntimeException primary = null;
        try {
            primary = deleteChildren(dirStream, sessionDir);
        } catch (RuntimeException walkFailure) {
            primary = aggregate(primary, new IllegalStateException(
                    "failed to delete session contents: " + walkFailure.getMessage(),
                    walkFailure));
        }
        return primary;
    }

    /**
     * Deletes the owned session contents for the verdict. The retained fd (name-independent)
     * always gets the first attempt; for a REPLACED or RENAMED session the original inode no
     * longer sits at the captured name, and a directory stream retained across that rename
     * cannot enumerate its contents reliably on every platform — so the original's current
     * name is located by fileKey through a FRESH parent scan and its contents are cleaned
     * through a freshly opened, re-verified stream. A replacement is never touched: the fresh
     * stream's anchored identity (its own fd fileKey) must equal the captured identity before
     * anything is deleted.
     */
    private RuntimeException deleteContents(CleanupVerdict verdict) {
        RuntimeException primary = deleteContentsDirRelative();
        if (verdict.state == CleanupVerdict.State.REPLACED
                || verdict.state == CleanupVerdict.State.RENAMED) {
            primary = aggregate(primary, deleteOriginalContentsThroughCurrentName());
        }
        return primary;
    }

    /**
     * Cleans the contents of the original session inode through a freshly opened stream at
     * its current parent path (located by fileKey), deleting directory-relative and re-proving
     * the anchored identity through the fresh fd before any deletion. Returns {@code null}
     * when the original is not linked in the parent (the retained-fd attempt governs).
     */
    private RuntimeException deleteOriginalContentsThroughCurrentName() {
        Path original;
        try {
            original = findSessionInodeInParent();
        } catch (IOException | RuntimeException failure) {
            return new IllegalStateException(
                    "unable to locate the renamed original session directory: "
                            + failure.getMessage(), failure);
        }
        if (original == null) {
            return null; // not linked in the parent; the retained-fd attempt governs
        }
        RuntimeException primary = null;
        SecureDirectoryStream<Path> fresh = openSecureStream(original);
        if (fresh == null) {
            return new IllegalStateException(
                    "unable to open the renamed original session directory for cleanup: "
                            + original);
        }
        try {
            // Re-prove the anchored identity through the fresh fd before any deletion: a
            // same-inode re-ownership or a raced replacement is refused, never deleted.
            BasicFileAttributeView keyView = fresh.getFileAttributeView(Path.of("."),
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            Object fdKey = keyView != null ? keyView.readAttributes().fileKey() : null;
            if (fdKey == null || !sessionFileKey.equals(fdKey)) {
                return new IllegalStateException(
                        "refusing to clean the unverified renamed original session directory: "
                                + original);
            }
            try {
                primary = aggregate(primary, deleteChildren(fresh, original));
            } catch (RuntimeException walkFailure) {
                primary = aggregate(primary, new IllegalStateException(
                        "failed to delete the renamed original session contents: "
                                + walkFailure.getMessage(), walkFailure));
            }
        } catch (IOException | RuntimeException failure) {
            primary = aggregate(primary, new IllegalStateException(
                    "unable to verify the renamed original session directory: "
                            + failure.getMessage(), failure));
        } finally {
            try {
                fresh.close();
            } catch (IOException | RuntimeException closeFailure) {
                primary = aggregate(primary, new IllegalStateException(
                        "failed to close the renamed original session stream: "
                                + closeFailure.getMessage(), closeFailure));
            }
        }
        return primary;
    }

    private RuntimeException deleteChildren(SecureDirectoryStream<Path> sds, Path dirPath) {
        RuntimeException primary = null;
        try {
            for (Path child : sds) {
                String name = child.getFileName().toString();
                try {
                    sds.deleteFile(Path.of(name));
                } catch (NoSuchFileException alreadyGone) {
                    // raced deletion: fine
                } catch (java.nio.file.FileSystemException notPlainFile) {
                    // Either a subdirectory (EISDIR/DirectoryNotEmptyException) or a genuine
                    // failure (e.g. access denied on a file). Distinguish by entry type.
                    boolean isDirectory = false;
                    try {
                        BasicFileAttributeView typeView = sds.getFileAttributeView(
                                Path.of(name), BasicFileAttributeView.class,
                                LinkOption.NOFOLLOW_LINKS);
                        isDirectory = typeView != null
                                && typeView.readAttributes().isDirectory();
                    } catch (IOException typeFailure) {
                        isDirectory = false;
                    }
                    if (!isDirectory) {
                        primary = aggregate(primary, new IllegalStateException(
                                "failed to delete " + name, notPlainFile));
                        continue;
                    }
                    // A subdirectory: recurse through its own directory-relative stream, then
                    // delete the (now empty) entry.
                    RuntimeException inner = null;
                    try (DirectoryStream<Path> subStream =
                            sds.newDirectoryStream(Path.of(name))) {
                        if (subStream instanceof SecureDirectoryStream<?> secureSub) {
                            @SuppressWarnings("unchecked")
                            SecureDirectoryStream<Path> sub =
                                    (SecureDirectoryStream<Path>) secureSub;
                            inner = deleteChildren(sub, dirPath.resolve(name));
                        } else {
                            inner = deleteChildrenFallback(dirPath.resolve(name));
                        }
                    } catch (IOException | RuntimeException subFailure) {
                        inner = aggregate(inner, new IllegalStateException(
                                "failed to open subdirectory " + name, subFailure));
                    }
                    if (inner != null) {
                        primary = aggregate(primary, inner);
                    }
                    try {
                        sds.deleteDirectory(Path.of(name));
                    } catch (IOException deleteFailure) {
                        primary = aggregate(primary, new IllegalStateException(
                                "failed to delete subdirectory " + name, deleteFailure));
                    }
                } catch (IOException deleteFailure) {
                    primary = aggregate(primary, new IllegalStateException(
                            "failed to delete " + name, deleteFailure));
                }
            }
        } catch (RuntimeException listFailure) {
            primary = aggregate(primary, new IllegalStateException(
                    "failed to list session contents: " + listFailure.getMessage(),
                    listFailure));
        }
        return primary;
    }

    /** Fallback recursion for a provider whose subdirectory streams are not directory-
     * relative: delete the subtree through checked absolute operations. */
    private RuntimeException deleteChildrenFallback(Path dirPath) {
        RuntimeException primary = null;
        try {
            primary = aggregate(primary, deleteOwnedAggregating(dirPath));
        } catch (RuntimeException walkFailure) {
            primary = aggregate(primary, walkFailure);
        }
        return primary;
    }

    /**
     * Removes the original session entry through the parent anchor, honoring the verdict of
     * the mandatory pre-deletion identity verification: only a {@code MATCHES} verdict deletes
     * (through the parent anchor, re-proving the entry's fileKey immediately before the
     * delete, or the identity-verified fallback when the anchor is unavailable/closed); a
     * re-owned same-inode session, a replaced entry, or an unprovable identity is refused and
     * reported as a leak — never deleted.
     */
    private RuntimeException deleteSessionEntryThroughParent(CleanupVerdict verdict) {
        switch (verdict.state) {
            case REOWNED:
                return new IllegalStateException(
                        "session directory owner changed; refusing to delete the re-owned "
                                + "session (leak reported): " + sessionDir, verdict.cause);
            case REPLACED:
                return new IllegalStateException(
                        "session directory was replaced; refusing to delete the replacement "
                                + "(leak reported): " + sessionDir);
            case RENAMED:
                return new IllegalStateException(
                        "session directory was renamed or moved; its inode is still linked "
                                + "elsewhere (leak reported): " + sessionDir);
            case GONE:
                return null; // already deleted: idempotent
            case UNVERIFIABLE:
                return new IllegalStateException(
                        "session directory identity cannot be proven; refusing to delete "
                                + "(leak reported): " + sessionDir, verdict.cause);
            case MATCHES:
                break;
        }
        if (sessionStreamClosed) {
            // The session stream is gone; clean any remaining contents through the
            // identity-verified fallback (refuses a replacement, never deletes it).
            return deleteOwnedFallback();
        }
        if (parentStream == null || parentStreamClosed) {
            return deleteSessionEntryFallback();
        }
        Path name = sessionReal.getFileName();
        try {
            // Re-prove the entry's identity through the parent anchor immediately before the
            // delete (no path race): a swap in the window since the close() verdict is refused.
            BasicFileAttributeView view = parentStream.getFileAttributeView(name,
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                return null; // already gone
            }
            if (!sessionFileKey.equals(view.readAttributes().fileKey())) {
                return new IllegalStateException(
                        "session directory was replaced; refusing to delete the replacement "
                                + "(leak reported): " + sessionDir);
            }
            parentStream.deleteDirectory(name);
            return null;
        } catch (NoSuchFileException alreadyGone) {
            return null;
        } catch (IOException | RuntimeException failure) {
            return new IllegalStateException("failed to delete session entry " + name,
                    failure);
        }
    }

    /** Fallback removal of the session entry without a parent anchor: the identity-verified
     * full cleanup (children first, then the entry) — fileKey/owner-refusing a replacement. */
    private RuntimeException deleteSessionEntryFallback() {
        return deleteOwnedFallback();
    }

    /** Closes one anchor; a failure leaves the stream open so a retry can close it. */
    private RuntimeException closeStream(Path dir, SecureDirectoryStream<Path> stream,
            String role, Runnable onSuccess) {
        try {
            stream.close();
            onSuccess.run();
            return null;
        } catch (IOException | RuntimeException failure) {
            return new IllegalStateException("failed to close " + role
                    + " directory stream (retryable): " + failure.getMessage(), failure);
        }
    }

    /** Fallback cleanup without SDS: identity-refusing (mandatory non-null equal fileKey and
     * owner), never deleting a replacement. An already-gone session is idempotent. */
    private RuntimeException deleteOwnedFallback() {
        if (!Files.exists(sessionReal, LinkOption.NOFOLLOW_LINKS)) {
            return null; // already deleted: idempotent
        }
        RuntimeException primary = null;
        try {
            try {
                verifyIdentity(sessionReal, sessionFileKey, sessionOwner, "session");
            } catch (ArtifactReference.ArtifactUnavailableException changed) {
                return new IllegalStateException(
                        "session directory was replaced or re-owned; refusing to delete the "
                                + "replacement (leak reported): " + sessionDir, changed);
            }
            primary = aggregate(primary, deleteOwnedAggregating(sessionReal));
            if (Files.exists(sessionReal, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.deleteIfExists(sessionReal);
                } catch (IOException deleteFailure) {
                    primary = aggregate(primary, new IllegalStateException(
                            "failed to delete session entry " + sessionReal, deleteFailure));
                }
            }
            return primary;
        } catch (RuntimeException failure) {
            return aggregate(primary, new IllegalStateException(
                    "failed to clean up session directory: " + failure.getMessage(), failure));
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
        if (next == null) {
            return primary;
        }
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
     * when neither can be established or verified. Owner-only directory/file attributes are
     * derived from the captured immutable owner principals and precomputed once. */
    interface OwnerOnlyPolicy {
        /** Owner-only attributes applied atomically at session-directory creation, derived
         * from the captured immutable parent owner. */
        FileAttribute<?>[] directoryCreationAttributes(UserPrincipal parentOwner)
                throws IOException;

        /** Owner-only attributes applied atomically at artifact-file creation, derived from
         * the captured immutable session owner. */
        FileAttribute<?>[] fileCreationAttributes(UserPrincipal sessionOwner)
                throws IOException;

        /** Verifies a directory is owner-only; throws if trust cannot be proven. */
        void verifyDirectory(Path dir) throws IOException;

        /** Verifies the parent denies other-principal rename/delete-child and ACL/owner
         * changes of the session entry; throws (fail closed) otherwise. */
        void validateParent(Path parent) throws IOException;

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

        @Override public FileAttribute<?>[] directoryCreationAttributes(UserPrincipal parentOwner) {
            return new FileAttribute<?>[] {
                    PosixFilePermissions.asFileAttribute(DIRECTORY)};
        }

        @Override public FileAttribute<?>[] fileCreationAttributes(UserPrincipal sessionOwner) {
            return new FileAttribute<?>[] {
                    PosixFilePermissions.asFileAttribute(FILE)};
        }

        @Override public void verifyDirectory(Path dir) throws IOException {
            if (!Files.getPosixFilePermissions(dir).equals(DIRECTORY)) {
                throw new IOException("session directory is not owner-only (POSIX): " + dir);
            }
        }

        @Override public void validateParent(Path parent) throws IOException {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(parent);
            boolean othersCanRenameOrDelete = perms.contains(PosixFilePermission.GROUP_WRITE)
                    || perms.contains(PosixFilePermission.OTHERS_WRITE);
            if (!othersCanRenameOrDelete) {
                return; // only the owner can rename/delete children
            }
            // Group/other writable: safe only when the sticky bit lets only the owner rename
            // or delete their own entries (e.g. the OS temp dir). Fail closed otherwise.
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("unix")) {
                int mode = (Integer) Files.getAttribute(parent, "unix:mode",
                        LinkOption.NOFOLLOW_LINKS);
                if ((mode & 01000) != 0) {
                    return; // sticky: other principals cannot rename/delete our entry
                }
            }
            throw new IOException("parent directory allows other-principal rename/delete-child"
                    + " (writable without sticky bit): " + parent);
        }
    }

    /** ACL owner-only policy for filesystems without POSIX views. The trusted principals are
     * the exact resolved {@link UserPrincipal} objects for the owner, the current process
     * user (the actor), {@code SYSTEM}, and {@code BUILTIN\Administrators} (when resolvable
     * on the platform) — never matched by name suffix or substring. The ACL is applied
     * atomically at directory/file creation via the {@code acl:acl} attribute, and parent
     * validation rejects other-principal WRITE_DATA/DELETE_CHILD/DELETE/WRITE_ACL/WRITE_OWNER. */
    static final class AclOwnerOnly implements OwnerOnlyPolicy {
        private static final String SYSTEM_PRINCIPAL = "SYSTEM";
        private static final String ADMINISTRATORS_PRINCIPAL = "BUILTIN\\Administrators";

        /** Exact resolved platform-required principals; empty when unresolvable. */
        private static final Set<UserPrincipal> REQUIRED_PRINCIPALS = resolveRequiredPrincipals();

        /** The current process user (the actor creating the session entry), resolved once;
         * {@code null} when unresolvable (then only the owner and the required principals are
         * trusted). On Windows an elevated process's token default-owns new objects to
         * {@code BUILTIN\Administrators}, so a directory the actor created can be owned by
         * the Administrators group while its ACL grants the actor full control; the actor's
         * own grant is not an other-principal grant and must not fail parent validation. */
        private static final UserPrincipal CURRENT_USER = resolveCurrentUser();

        private static Set<UserPrincipal> resolveRequiredPrincipals() {
            Set<UserPrincipal> required = new HashSet<>();
            java.nio.file.attribute.UserPrincipalLookupService lookup =
                    FileSystems.getDefault().getUserPrincipalLookupService();
            for (String name : new String[] {SYSTEM_PRINCIPAL, ADMINISTRATORS_PRINCIPAL}) {
                try {
                    required.add(lookup.lookupPrincipalByName(name));
                } catch (IOException ignored) {
                    // principal not resolvable on this platform: not required here
                }
            }
            return required;
        }

        private static UserPrincipal resolveCurrentUser() {
            try {
                return FileSystems.getDefault().getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name"));
            } catch (IOException | RuntimeException unresolvable) {
                return null;
            }
        }

        /** Whether the principal is the current process user: its own ACL grant cannot enable
         * an other-principal to rename/delete the session entry. */
        private static boolean isCurrentUser(UserPrincipal principal) {
            return CURRENT_USER != null && CURRENT_USER.equals(principal);
        }

        @Override public FileAttribute<?>[] directoryCreationAttributes(UserPrincipal parentOwner) {
            return new FileAttribute<?>[] {aclAttribute(parentOwner)};
        }

        @Override public FileAttribute<?>[] fileCreationAttributes(UserPrincipal sessionOwner) {
            return new FileAttribute<?>[] {aclAttribute(sessionOwner)};
        }

        @Override public void verifyDirectory(Path dir) throws IOException {
            AclFileAttributeView view = view(dir);
            UserPrincipal owner = view.getOwner();
            verifyOwnerOnly(view.getAcl(), owner, dir);
        }

        @Override public void validateParent(Path parent) throws IOException {
            AclFileAttributeView view = view(parent);
            UserPrincipal owner = view.getOwner();
            for (AclEntry entry : view.getAcl()) {
                if (entry.type() != AclEntryType.ALLOW
                        || owner.equals(entry.principal())
                        || isCurrentUser(entry.principal())
                        || REQUIRED_PRINCIPALS.contains(entry.principal())) {
                    continue;
                }
                Set<AclEntryPermission> permissions = entry.permissions();
                if (permissions.contains(AclEntryPermission.WRITE_DATA)
                        || permissions.contains(AclEntryPermission.DELETE_CHILD)
                        || permissions.contains(AclEntryPermission.DELETE)
                        || permissions.contains(AclEntryPermission.WRITE_ACL)
                        || permissions.contains(AclEntryPermission.WRITE_OWNER)) {
                    throw new IOException("parent ACL grants other-principal rename/delete/"
                            + "ACL/owner changes on " + parent + ": " + entry.principal());
                }
            }
        }

        private static AclFileAttributeView view(Path path) throws IOException {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) {
                throw new IOException("no ACL attribute view for " + path);
            }
            return view;
        }

        /** Builds the owner-only ACL: the exact owner principal with all permissions, plus the
         * exact resolved platform-required principals. */
        static List<AclEntry> ownerOnlyEntries(UserPrincipal owner) {
            List<AclEntry> entries = new ArrayList<>();
            entries.add(entry(AclEntryType.ALLOW, owner, EnumSet.allOf(AclEntryPermission.class)));
            for (UserPrincipal required : REQUIRED_PRINCIPALS) {
                entries.add(entry(AclEntryType.ALLOW, required,
                        EnumSet.allOf(AclEntryPermission.class)));
            }
            return entries;
        }

        /** Builds the {@code acl:acl} creation attribute for the given owner principal. */
        static FileAttribute<List<AclEntry>> aclAttribute(UserPrincipal owner) {
            return aclAttribute(ownerOnlyEntries(owner));
        }

        private static AclEntry entry(AclEntryType type, UserPrincipal principal,
                Set<AclEntryPermission> permissions) {
            return AclEntry.newBuilder()
                    .setType(type)
                    .setPrincipal(principal)
                    .setPermissions(permissions)
                    .build();
        }

        private static FileAttribute<List<AclEntry>> aclAttribute(List<AclEntry> entries) {
            return new FileAttribute<List<AclEntry>>() {
                @Override public String name() {
                    return "acl:acl";
                }

                @Override public List<AclEntry> value() {
                    return entries;
                }
            };
        }

        /** Verifies the ACL grants access only to the exact owner and the exact resolved
         * platform-required principals; throws otherwise (fail closed). A principal whose name
         * merely resembles {@code SYSTEM} or {@code Administrators} but is not one of the
         * resolved objects is rejected. */
        static void verifyOwnerOnly(List<AclEntry> acl, UserPrincipal owner, Path path)
                throws IOException {
            for (AclEntry entry : acl) {
                if (entry.type() == AclEntryType.DENY && owner.equals(entry.principal())) {
                    throw new IOException("ACL denies the owner on " + path);
                }
                if (entry.type() == AclEntryType.ALLOW
                        && !owner.equals(entry.principal())
                        && !REQUIRED_PRINCIPALS.contains(entry.principal())) {
                    throw new IOException("ACL grants a non-owner principal on " + path
                            + ": " + entry.principal());
                }
            }
        }
    }

    /** Package-visible seam for reading immutable identity (fileKey + owner); tests inject
     * providers with null fileKey/owner or a different owner to prove fail-closed behavior. */
    interface IdentitySource {
        /** Returns the immutable fileKey, or {@code null} when the provider has none. */
        Object fileKey(Path path) throws IOException;

        /** Returns the owner principal, or {@code null} when the provider has none. */
        UserPrincipal owner(Path path) throws IOException;
    }

    /** Real identity source: fileKey from NOFOLLOW attributes, owner from the owner view. */
    static final class RealIdentitySource implements IdentitySource {
        @Override public Object fileKey(Path path) throws IOException {
            return Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey();
        }

        @Override public UserPrincipal owner(Path path) throws IOException {
            return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        }
    }

    /** Outcome of the mandatory pre-deletion identity verification for {@link #close()}. */
    private static final class CleanupVerdict {
        enum State {
            /** The anchored original is the exact captured inode and owner AND the parent
             * entry is identical: children and entry may be deleted. */
            MATCHES,
            /** The anchored-original identity (the retained session SDS's own fileKey/owner, or
             * the seam-confirmed owner) mismatches or is missing: neither children nor the entry
             * may be deleted. */
            REOWNED,
            /** Anchored original valid, but the parent entry was replaced by a different inode:
             * the replacement entry is refused, while the anchored fd still refers to the
             * original and its contents may be cleaned. */
            REPLACED,
            /** Anchored original valid but the parent entry is missing while the inode is still
             * linked elsewhere (renamed away): the original's contents may be cleaned through
             * the anchored fd, and close reports the renamed-original leak. */
            RENAMED,
            /** Anchored original valid but the parent entry is missing and the inode is no
             * longer linked anywhere (truly deleted): an idempotent no-op. */
            GONE,
            /** The anchored identity could not be read at all: nothing may be deleted. */
            UNVERIFIABLE
        }

        final State state;
        /** Verification failure preserved for aggregation; non-null for REOWNED/UNVERIFIABLE. */
        final ArtifactReference.ArtifactUnavailableException cause;

        private CleanupVerdict(State state,
                ArtifactReference.ArtifactUnavailableException cause) {
            this.state = state;
            this.cause = cause;
        }

        static CleanupVerdict matches() {
            return new CleanupVerdict(State.MATCHES, null);
        }

        static CleanupVerdict reowned(ArtifactReference.ArtifactUnavailableException cause) {
            return new CleanupVerdict(State.REOWNED, cause);
        }

        static CleanupVerdict replaced() {
            return new CleanupVerdict(State.REPLACED, null);
        }

        static CleanupVerdict renamed() {
            return new CleanupVerdict(State.RENAMED, null);
        }

        static CleanupVerdict gone() {
            return new CleanupVerdict(State.GONE, null);
        }

        static CleanupVerdict unverifiable(
                ArtifactReference.ArtifactUnavailableException failure) {
            return new CleanupVerdict(State.UNVERIFIABLE, failure);
        }
    }
}
