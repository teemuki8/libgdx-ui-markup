package dev.gdx.markup.qualification;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * Fetches copyrighted reference images at test time into a gitignored, per-session, owner-only
 * cache and never redistributes them. Every remote entry declares its exact identity (HTTPS
 * URL, SHA-256, byte length, media type, dimensions) and the store refuses anything that does
 * not match: the URL must be https on port 443 with a host and no user info or fragment, the
 * host must be in the allowlist, and every fetch connects over TLS to one approved,
 * globally-routable resolved address (the transport never re-resolves, so a rebinding attack
 * cannot reach a different peer). Redirects are followed manually with the same policy applied
 * to a fresh approval per target, bounded to {@link #MAX_REDIRECTS}. The payload must match the
 * declared digest, byte length, media type, and header dimensions, and is decoded once into a
 * bounded {@link ReferenceImage} at the analysis resolution; the cache is written and re-read
 * through single {@code NOFOLLOW} handles so a forged or symlinked cache entry can never be
 * used or followed.
 *
 * <p>The session cache is anchored by a trust chain: every component from the filesystem root
 * down to the session parent is validated as a real directory that cannot be used to replace
 * its next component (sticky shared root, or writable only by a trusted owner), the identity is
 * revalidated immediately before every directory-relative operation, and on providers that
 * support it a {@link SecureDirectoryStream} is retained over the session so all cache I/O and
 * cleanup happen relative to the anchored directory handle. The security policy is abstracted
 * over POSIX mode bits and Windows ACLs ({@link FilesystemPolicy}); when neither policy can be
 * proven the store fails closed with a typed {@link ReferenceException}.
 *
 * <p>HTTP framing is parsed byte-for-byte: every raw octet including the CRLF terminators
 * counts toward the per-line and total header bounds before normalization, lines must be
 * terminated by CRLF (bare CR, bare LF, and other control octets are rejected), and the exact
 * bounds pass while one octet over fails.
 *
 * <p>Policy, identity, cache, decode, and transport failures raise typed
 * {@link ReferenceException}s so the qualification fails loudly; {@link Optional#empty()} is
 * reserved for references that are explicitly absent (HTTP 404/410).
 */
public final class ReferenceImageStore implements AutoCloseable {
    /** Maximum accepted reference payload. */
    public static final long MAX_BYTES = 8L * 1024 * 1024;
    /** Maximum redirect hops followed per fetch, each re-validated against the full policy. */
    public static final int MAX_REDIRECTS = 5;
    /** Image media types accepted from servers and declared by the manifest. */
    public static final Set<String> ALLOWED_MEDIA_TYPES = Set.of("image/jpeg", "image/png");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    static final int MAX_HEADER_LINE = 16 * 1024;
    static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<Integer> ABSENT_STATUSES = Set.of(404, 410);
    private static final java.util.regex.Pattern CONTENT_LENGTH =
            java.util.regex.Pattern.compile("[0-9]+");
    private static final Set<String> DEFAULT_ALLOWED_HOSTS =
            Set.of("shared.akamai.steamstatic.com");
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    /** One GET response: status, Content-Type, Location, and the bounded body bytes. */
    public record Response(int statusCode, String contentType, String location, byte[] body) {
        public Response {
            Objects.requireNonNull(body, "body");
            contentType = contentType == null ? "" : contentType;
            location = location == null ? "" : location;
        }
    }

    /**
     * Transport seam: performs one GET against an already-approved resolved address. The
     * transport must connect only to {@code approved} addresses and must never resolve the
     * hostname itself.
     */
    @FunctionalInterface
    public interface Transport {
        Response get(URI uri, List<InetAddress> approved) throws IOException;
    }

    /** Resolves a hostname to addresses; injectable so tests avoid real DNS. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** Monotonic time source for the per-exchange deadline; injectable for deterministic tests. */
    @FunctionalInterface
    interface Clock {
        long nanoTime();
    }

    /** TLS handshake step; injectable so the deadline owner can be proven without real TLS. */
    @FunctionalInterface
    interface Handshake {
        void run() throws IOException;
    }

    /**
     * Immutable per-directory security facts, the pure input to the POSIX trust-chain
     * decisions (extracted from {@code unix:mode}, {@code unix:uid}, and the owner). The
     * decisions are pure functions of these facts so the policy is deterministically testable
     * without a filesystem.
     */
    record DirectorySecurity(boolean directory, boolean sticky, boolean groupOrOtherWritable,
            boolean ownerIsCurrentUser, boolean ownerIsRoot) {

        static final DirectorySecurity UNPROVABLE =
                new DirectorySecurity(false, false, false, false, false);

        /**
         * The session parent: a real directory that is private (owned by the current user,
         * unshared) or a sticky shared root owned by a trusted principal, so no other
         * principal can delete or replace the session directory inside it.
         */
        boolean secureParent() {
            return directory
                    && (ownerIsCurrentUser && !groupOrOtherWritable
                        || sticky && (ownerIsCurrentUser || ownerIsRoot));
        }

        /**
         * An ancestor that could replace its next component: a real directory that is either
         * sticky (other principals cannot rename/delete a child they do not own) or writable
         * only by its owner, and whose owner is a trusted principal (the current user or the
         * platform root).
         */
        boolean secureAncestor() {
            return directory
                    && (sticky || !groupOrOtherWritable)
                    && (ownerIsCurrentUser || ownerIsRoot);
        }
    }

    /**
     * Filesystem security policy abstraction: proves the trust chain for the session cache on
     * POSIX filesystems (mode bits) or Windows (ACLs). The policy is selected once per parent;
     * when neither implementation can probe the filesystem, the store fails closed with a
     * typed {@link ReferenceException} instead of falling back to an unvalidated location.
     */
    interface FilesystemPolicy {
        /** Whether this policy can prove security facts about the given directory. */
        boolean canProbe(Path dir);

        /** Proves the final session parent is secure (see {@link DirectorySecurity#secureParent()}). */
        boolean isSecureParent(Path parent);

        /** Proves an ancestor cannot replace its next component (see
         * {@link DirectorySecurity#secureAncestor()}). */
        boolean isSecureAncestor(Path ancestor);

        /** Creates a fresh private directory (owner-only mode or ACL) under the parent. */
        Path createPrivateDirectory(Path parent, String prefix) throws IOException;

        /** Selects the first policy that can probe the directory: POSIX, then ACL. */
        static FilesystemPolicy detect(Path probe) {
            PosixFilesystemPolicy posix = new PosixFilesystemPolicy();
            if (posix.canProbe(probe)) {
                return posix;
            }
            AclFilesystemPolicy acl = new AclFilesystemPolicy();
            return acl.canProbe(probe) ? acl : null;
        }
    }

    /**
     * POSIX mode-bit policy. On systems where {@code unix:mode} is unreadable (for example
     * Windows) the directory is unprovable and refused, so the store falls through to the ACL
     * policy or fails closed.
     */
    static final class PosixFilesystemPolicy implements FilesystemPolicy {
        @Override
        public boolean canProbe(Path dir) {
            try {
                Files.getAttribute(dir, "unix:mode", LinkOption.NOFOLLOW_LINKS);
                return true;
            } catch (IOException | UnsupportedOperationException unprovable) {
                return false;
            }
        }

        DirectorySecurity facts(Path dir) {
            try {
                int mode = (Integer) Files.getAttribute(dir, "unix:mode",
                        LinkOption.NOFOLLOW_LINKS);
                boolean directory = (mode & 0040000) != 0; // S_IFDIR
                boolean sticky = (mode & 01000) != 0;
                boolean groupOrOtherWritable = (mode & 0022) != 0;
                boolean ownerIsRoot;
                try {
                    ownerIsRoot = (Integer) Files.getAttribute(dir, "unix:uid",
                            LinkOption.NOFOLLOW_LINKS) == 0;
                } catch (IOException | UnsupportedOperationException noUid) {
                    ownerIsRoot = false; // cannot prove: treat the owner as untrusted
                }
                boolean ownerIsCurrentUser = Files.getOwner(dir).equals(currentUser());
                return new DirectorySecurity(directory, sticky, groupOrOtherWritable,
                        ownerIsCurrentUser, ownerIsRoot);
            } catch (IOException | UnsupportedOperationException unprovable) {
                return DirectorySecurity.UNPROVABLE;
            }
        }

        @Override
        public boolean isSecureParent(Path parent) {
            return facts(parent).secureParent();
        }

        @Override
        public boolean isSecureAncestor(Path ancestor) {
            return facts(ancestor).secureAncestor();
        }

        @Override
        public Path createPrivateDirectory(Path parent, String prefix) throws IOException {
            return Files.createTempDirectory(parent, prefix,
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        }
    }

    /**
     * Windows ACL policy. The session parent is provably secure when it is owned by the
     * current user and no untrusted principal is granted entry-modifying permissions
     * ({@code DELETE_CHILD}, {@code WRITE_DATA}, {@code APPEND_DATA}, {@code DELETE},
     * {@code WRITE_ACL}, or {@code WRITE_OWNER}); an ancestor is provably secure when
     * additionally its owner is a trusted principal (the current user or an exactly resolved
     * well-known system/administrators account). Session directories are created with an
     * owner-only ACL. When the ACL view cannot be read the directory is unprovable and
     * refused (fail closed). The decisions are pure functions of the ACL entries and the
     * exact resolved principal names, so they are deterministically testable on any platform.
     */
    static final class AclFilesystemPolicy implements FilesystemPolicy {
        /**
         * Permissions that let a principal create, modify, or delete the directory's entries,
         * or take or rewrite its DACL/owner and thereby replace the session.
         */
        static final Set<AclEntryPermission> ENTRY_MODIFY = Set.of(
                AclEntryPermission.DELETE_CHILD, AclEntryPermission.WRITE_DATA,
                AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE,
                AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER);

        private final UserPrincipal currentUser;
        /**
         * Exactly resolved principal names trusted for the ancestor policy: the current user
         * plus every well-known system/administrators account the platform's principal lookup
         * service can resolve. A name is only trusted when it equals a resolved account name
         * — display-name or substring matches are never used.
         */
        private final Set<String> trustedPrincipalNames;

        AclFilesystemPolicy() {
            this.currentUser = currentUser();
            Set<String> trusted = new HashSet<>(resolveTrustedSystemPrincipalNames());
            trusted.add(currentUser.getName());
            this.trustedPrincipalNames = Set.copyOf(trusted);
        }

        /**
         * Resolves the exact well-known system principals through the principal lookup
         * service. Only accounts that resolve exactly are trusted; when an account is not
         * resolvable on this platform it is conservatively not trusted. Package-private for
         * tests.
         */
        static Set<String> resolveTrustedSystemPrincipalNames() {
            Set<String> trusted = new HashSet<>();
            UserPrincipalLookupService lookup = FileSystems.getDefault()
                    .getUserPrincipalLookupService();
            for (String candidate : List.of("SYSTEM", "NT AUTHORITY\\SYSTEM",
                    "BUILTIN\\Administrators", "Administrators", "root")) {
                try {
                    trusted.add(lookup.lookupPrincipalByName(candidate).getName());
                } catch (IOException | UnsupportedOperationException unavailable) {
                    // the well-known account is not resolvable on this platform: not trusted
                }
            }
            return trusted;
        }

        @Override
        public boolean canProbe(Path dir) {
            return aclView(dir) != null;
        }

        @Override
        public boolean isSecureParent(Path parent) {
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            try {
                AclFileAttributeView view = aclView(parent);
                if (view == null) {
                    return false;
                }
                UserPrincipal owner = view.getOwner();
                return owner.getName().equals(currentUser.getName())
                        && !allowsNonOwnerModification(view.getAcl(), owner,
                                trustedPrincipalNames);
            } catch (IOException | UnsupportedOperationException unprovable) {
                return false;
            }
        }

        @Override
        public boolean isSecureAncestor(Path ancestor) {
            if (!Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            try {
                AclFileAttributeView view = aclView(ancestor);
                if (view == null) {
                    return false;
                }
                UserPrincipal owner = view.getOwner();
                return ownerTrusted(owner, trustedPrincipalNames)
                        && !allowsNonOwnerModification(view.getAcl(), owner,
                                trustedPrincipalNames);
            } catch (IOException | UnsupportedOperationException unprovable) {
                return false;
            }
        }

        @Override
        public Path createPrivateDirectory(Path parent, String prefix) throws IOException {
            Path dir = Files.createTempDirectory(parent, prefix);
            try {
                AclFileAttributeView view = aclView(dir);
                if (view == null) {
                    throw new IOException("ACL attribute view unavailable for " + dir);
                }
                UserPrincipal owner = view.getOwner();
                List<AclEntry> acl = ownerOnlyAcl(owner);
                view.setAcl(acl);
                // Verify the private ACL was actually established: the owner must hold every
                // permission and no untrusted principal may modify the entries.
                if (!acl.equals(view.getAcl())
                        || allowsNonOwnerModification(view.getAcl(), owner,
                                Set.of(owner.getName()))) {
                    throw new IOException("could not establish a private ACL on " + dir);
                }
                return dir;
            } catch (IOException failure) {
                try {
                    Files.deleteIfExists(dir);
                } catch (IOException ignored) {
                    // preserve the original failure
                }
                throw failure;
            }
        }

        private static AclFileAttributeView aclView(Path path) {
            try {
                return Files.getFileAttributeView(path, AclFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException unavailable) {
                return null;
            }
        }

        /**
         * Pure: the private session ACL grants the owner every permission with inheritance and
         * nothing to anyone else.
         */
        static List<AclEntry> ownerOnlyAcl(UserPrincipal owner) {
            return List.of(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                    .build());
        }

        /**
         * Pure: true when any ALLOW entry grants entry-modifying, DACL-rewriting, or
         * ownership-taking permissions to a principal other than the owner that is not one of
         * the exactly resolved trusted principals. DENY entries and grants to the owner or to
         * trusted principals are harmless.
         */
        static boolean allowsNonOwnerModification(List<AclEntry> acl, UserPrincipal owner,
                Set<String> trustedPrincipalNames) {
            for (AclEntry entry : acl) {
                if (entry.type() == AclEntryType.ALLOW
                        && !entry.principal().getName().equals(owner.getName())
                        && !trustedPrincipalNames.contains(entry.principal().getName())
                        && entry.permissions().stream().anyMatch(ENTRY_MODIFY::contains)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Pure: an ancestor's owner may anchor the chain only when its name equals the exact
         * name of the current user or of a resolved well-known system/administrators account.
         */
        static boolean ownerTrusted(UserPrincipal owner, Set<String> trustedPrincipalNames) {
            return trustedPrincipalNames.contains(owner.getName());
        }
    }

    /**
     * The anchored session: its path, real-path identity, non-null file key, and retained
     * directory streams over the session's parent and the session itself.
     */
    record SessionAnchor(Path dir, Path realPath, Object fileKey,
            SecureDirectoryStream<Path> parentStream, SecureDirectoryStream<Path> sessionStream) {
    }

    /** A reference whose bytes were authenticated and decoded once into a bounded image. */
    public record ReferenceImage(BufferedImage image) {
        public ReferenceImage {
            Objects.requireNonNull(image, "image");
        }
    }

    private final Path cacheDir;
    private final Path anchorRealPath;
    private final Object anchorFileKey;
    private final SecureDirectoryStream<Path> parentStream;
    private final SecureDirectoryStream<Path> sessionStream;
    private final Transport transport;
    private final HostResolver resolver;
    private final Set<String> allowedHosts;
    private final Clock clock;

    /** Creates a store with the default pinned-TLS transport, DNS resolution, and host allowlist. */
    public ReferenceImageStore(Path cacheDir) {
        this(cacheDir, null, null, null, System::nanoTime);
    }

    /**
     * Creates a store over injected seams (transport, resolver, host allowlist); package-private
     * so deterministic tests never touch the network.
     */
    ReferenceImageStore(Path cacheDir, Transport transport, HostResolver resolver,
            Set<String> allowedHosts) {
        this(cacheDir, transport, resolver, allowedHosts, System::nanoTime);
    }

    /** Package-private seam with an injected clock for deterministic deadline tests. */
    ReferenceImageStore(Path cacheDir, Transport transport, HostResolver resolver,
            Set<String> allowedHosts, Clock clock) {
        SessionAnchor anchor = createSessionDir(cacheDir);
        this.cacheDir = anchor.dir();
        this.anchorRealPath = anchor.realPath();
        this.anchorFileKey = anchor.fileKey();
        this.parentStream = anchor.parentStream();
        this.sessionStream = anchor.sessionStream();
        this.transport = transport != null ? transport : this::httpsGet;
        this.resolver = resolver != null ? resolver : this::resolveAll;
        this.allowedHosts = allowedHosts != null ? allowedHosts : DEFAULT_ALLOWED_HOSTS;
        this.clock = clock;
    }

    /**
     * Returns the authenticated reference image for the entry, fetching it when absent. Empty
     * only when the reference is explicitly absent (HTTP 404/410). Policy, identity, cache,
     * decode, and transport failures raise {@link ReferenceException}.
     */
    public Optional<ReferenceImage> reference(CorpusEntry entry) {
        String name = cacheFileName(entry);
        if (hasCacheFile(name)) {
            return Optional.of(decodedFromCache(name, entry));
        }
        byte[] body;
        try {
            body = fetchVerified(entry);
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.IO,
                    "cannot fetch " + entry.sourceUrl(), failure);
        }
        if (body == null) {
            return Optional.empty();
        }
        ReferenceImage image = decodeVerified(body, entry, entry.sourceUrl());
        writeCache(name, body);
        return Optional.of(image);
    }

    /** Returns the private session cache directory (package-private for tests). */
    Path sessionDir() {
        return cacheDir;
    }

    /** Returns the retained secure directory stream over the session (null when unsupported). */
    SecureDirectoryStream<Path> retainedSessionStream() {
        return sessionStream;
    }

    private String cacheFileName(CorpusEntry entry) {
        return entry.id() + extension(entry.mediaType());
    }

    /**
     * Revalidates the session identity immediately before a directory-relative operation: the
     * session path still names the same real directory created at construction (file key and
     * real path), so a swapped, replaced, or symlinked path can never receive cache data.
     */
    private void revalidateSession() {
        if (!Files.isDirectory(cacheDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new ReferenceException(ReferenceException.Kind.CACHE,
                    "session cache directory vanished or was replaced: " + cacheDir);
        }
        try {
            Object currentKey = fileKeyOf(cacheDir);
            // The anchor key is non-null by construction; an unprovable current key is a
            // mismatch, never proof of identity.
            if (currentKey == null || !anchorFileKey.equals(currentKey)) {
                throw new ReferenceException(ReferenceException.Kind.CACHE,
                        "session cache directory was replaced or its identity is unprovable: "
                                + cacheDir);
            }
            if (!cacheDir.toRealPath().equals(anchorRealPath)) {
                throw new ReferenceException(ReferenceException.Kind.CACHE,
                        "session cache path changed: " + cacheDir);
            }
        } catch (ReferenceException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.CACHE,
                    "cannot revalidate the session cache path " + cacheDir, failure);
        }
    }

    private static Object fileKeyOf(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey();
        } catch (IOException | UnsupportedOperationException unavailable) {
            return null; // unprovable: never treated as proof of identity
        }
    }

    /**
     * Pure identity decision: two file keys prove the same directory only when both are
     * non-null and equal. A missing key on either side is unprovable, never the same.
     */
    static boolean sameFileKey(Object firstKey, Object secondKey) {
        return firstKey != null && secondKey != null && firstKey.equals(secondKey);
    }

    /**
     * Opens a cache entry relative to the retained {@link SecureDirectoryStream} when the
     * provider supports one (the fd anchors the original session directory, so no path
     * traversal can redirect the open); otherwise a NOFOLLOW open on the session path.
     */
    private SeekableByteChannel sessionChannel(String name, Set<OpenOption> options)
            throws IOException {
        if (sessionStream != null) {
            return sessionStream.newByteChannel(Path.of(name), options);
        }
        return Files.newByteChannel(cacheDir.resolve(name), options);
    }

    private boolean hasCacheFile(String name) {
        revalidateSession();
        try (SeekableByteChannel channel = sessionChannel(name,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            return channel.size() >= 0; // the entry exists and is readable
        } catch (NoSuchFileException missing) {
            return false;
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.CACHE,
                    "cannot inspect cache file " + name, failure);
        }
    }

    /**
     * Re-authenticates a cache hit from a single NOFOLLOW handle: reads the bytes once, then
     * checks length, digest, and header dimensions before decoding. A forged or tampered cache
     * entry fails the qualification with a typed {@code CACHE} error.
     */
    private ReferenceImage decodedFromCache(String name, CorpusEntry entry) {
        revalidateSession();
        byte[] bytes;
        try (SeekableByteChannel channel = sessionChannel(name,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            bytes = readAllBounded(channel, name);
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.CACHE,
                    "cannot read cache file " + name, failure);
        }
        if (bytes.length != entry.bytes()) {
            throw new ReferenceException(ReferenceException.Kind.CACHE,
                    "cache file " + name + " is " + bytes.length + " bytes, declared "
                            + entry.bytes());
        }
        if (!digestMatches(bytes, entry.sha256())) {
            throw new ReferenceException(ReferenceException.Kind.CACHE,
                    "cache file " + name + " fails the declared SHA-256 identity");
        }
        return decodeVerified(bytes, entry, "cache " + name);
    }

    /**
     * Writes the verified bytes with CREATE_NEW and NOFOLLOW relative to the anchored session,
     * so a pre-planted regular file or symlink at the cache path is never replaced or followed.
     */
    private void writeCache(String name, byte[] body) {
        revalidateSession();
        try (SeekableByteChannel channel = sessionChannel(name,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.wrap(body);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.CACHE,
                    "cannot write cache file " + name, failure);
        }
    }

    private static byte[] readAllBounded(SeekableByteChannel channel, String name)
            throws IOException {
        long size = channel.size();
        if (size > MAX_BYTES) {
            throw new IOException("cache file " + name + " exceeds the " + MAX_BYTES
                    + " byte cap");
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) size);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("truncated cache file " + name);
            }
        }
        return buffer.array();
    }

    /**
     * Fetches the entry with manual bounded redirect handling, approving a fresh set of
     * globally-routable addresses for every target, then verifying the payload against the
     * declared identity. Returns null when the reference is explicitly absent.
     */
    private byte[] fetchVerified(CorpusEntry entry) throws IOException {
        URI target;
        try {
            target = URI.create(entry.sourceUrl());
        } catch (IllegalArgumentException failure) {
            throw new ReferenceException(ReferenceException.Kind.UNSAFE_TARGET,
                    "malformed source URL: " + entry.sourceUrl(), failure);
        }
        int redirects = 0;
        while (true) {
            validateTarget(target);
            List<InetAddress> approved = approve(target.getHost());
            Response response = transport.get(target, approved);
            if (REDIRECT_STATUSES.contains(response.statusCode())) {
                if (response.location().isEmpty()) {
                    throw new ReferenceException(ReferenceException.Kind.UNSAFE_TARGET,
                            "redirect without a Location header from " + target);
                }
                if (redirects >= MAX_REDIRECTS) {
                    throw new ReferenceException(ReferenceException.Kind.UNSAFE_TARGET,
                            "more than " + MAX_REDIRECTS + " redirects from "
                                    + entry.sourceUrl());
                }
                redirects++;
                try {
                    target = target.resolve(response.location());
                } catch (IllegalArgumentException failure) {
                    throw new ReferenceException(ReferenceException.Kind.UNSAFE_TARGET,
                            "invalid redirect Location from " + target + ": "
                                    + response.location(), failure);
                }
                continue;
            }
            if (ABSENT_STATUSES.contains(response.statusCode())) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new ReferenceException(ReferenceException.Kind.UNEXPECTED_STATUS,
                        "status " + response.statusCode() + " from " + target);
            }
            verifyIdentity(response, entry, target);
            return response.body();
        }
    }

    /** Validates URL shape (https, host, port 443, no user info or fragment) and host allowlist. */
    private void validateTarget(URI target) {
        try {
            CorpusEntry.validateSourceUrl(target.toString());
        } catch (ManifestException failure) {
            throw new ReferenceException(ReferenceException.Kind.UNSAFE_TARGET,
                    "refusing target " + target + ": " + failure.getMessage());
        }
        if (!allowedHosts.contains(normalizeHost(target.getHost()))) {
            throw new ReferenceException(ReferenceException.Kind.UNSAFE_TARGET,
                    "host " + target.getHost() + " is not in the allowlist");
        }
    }

    /**
     * Resolves the host once and returns every globally-routable address. The resolved list is
     * pinned into the transport; nothing else resolves the host, so a DNS rebinding attack
     * cannot redirect the connection to a different peer.
     */
    private List<InetAddress> approve(String host) {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException failure) {
            throw new ReferenceException(ReferenceException.Kind.UNSAFE_TARGET,
                    "cannot resolve host " + host, failure);
        }
        List<InetAddress> approved = new ArrayList<>(addresses.length);
        for (InetAddress address : addresses) {
            if (isGloballyRoutable(address)) {
                approved.add(address);
            }
        }
        if (approved.isEmpty()) {
            throw new ReferenceException(ReferenceException.Kind.UNSAFE_TARGET,
                    "host " + host + " resolves only to non-globally-routable addresses: "
                            + Arrays.toString(addresses));
        }
        return List.copyOf(approved);
    }

    /** Refuses the payload unless media type, length, and digest match the declared identity. */
    private static void verifyIdentity(Response response, CorpusEntry entry, URI target) {
        String mediaType = baseMediaType(response.contentType());
        if (mediaType.isEmpty() || !ALLOWED_MEDIA_TYPES.contains(mediaType)
                || !mediaType.equals(entry.mediaType())) {
            throw new ReferenceException(ReferenceException.Kind.IDENTITY_MISMATCH,
                    "Content-Type '" + response.contentType()
                            + "' does not match the declared media type '" + entry.mediaType()
                            + "' from " + target);
        }
        if (response.body().length > MAX_BYTES) {
            throw new ReferenceException(ReferenceException.Kind.IDENTITY_MISMATCH,
                    "payload exceeds the " + MAX_BYTES + " byte cap from " + target);
        }
        if (response.body().length != entry.bytes()) {
            throw new ReferenceException(ReferenceException.Kind.IDENTITY_MISMATCH,
                    "payload is " + response.body().length + " bytes, declared " + entry.bytes()
                            + " from " + target);
        }
        if (!digestMatches(response.body(), entry.sha256())) {
            throw new ReferenceException(ReferenceException.Kind.IDENTITY_MISMATCH,
                    "payload SHA-256 does not match the declared identity from " + target);
        }
    }

    /** Reads the image header, verifies declared dimensions and format, then decodes bounded. */
    private static ReferenceImage decodeVerified(byte[] body, CorpusEntry entry, String context) {
        BoundedDecode.Header header;
        try {
            header = BoundedDecode.header(body);
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.DECODE,
                    "cannot read image header from " + context, failure);
        }
        if (header.width() != entry.referenceWidth()
                || header.height() != entry.referenceHeight()) {
            throw new ReferenceException(ReferenceException.Kind.IDENTITY_MISMATCH,
                    "image header is " + header.width() + "x" + header.height() + ", declared "
                            + entry.referenceWidth() + "x" + entry.referenceHeight() + " from "
                            + context);
        }
        if (!expectedFormat(entry.mediaType()).equalsIgnoreCase(header.formatName())) {
            throw new ReferenceException(ReferenceException.Kind.IDENTITY_MISMATCH,
                    "decoded format " + header.formatName() + " does not match declared media "
                            + "type " + entry.mediaType() + " from " + context);
        }
        try {
            return new ReferenceImage(BoundedDecode.decode(body));
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.DECODE,
                    "cannot decode image from " + context, failure);
        }
    }

    private static String expectedFormat(String mediaType) {
        return switch (mediaType) {
            case "image/jpeg" -> "jpeg";
            case "image/png" -> "png";
            default -> throw new IllegalArgumentException("unexpected media type " + mediaType);
        };
    }

    private static String extension(String mediaType) {
        return switch (mediaType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> throw new IllegalArgumentException("unexpected media type " + mediaType);
        };
    }

    private static String baseMediaType(String contentType) {
        int separator = contentType.indexOf(';');
        String base = separator < 0 ? contentType : contentType.substring(0, separator);
        return base.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    /**
     * Positive address policy: an address is approved only if it is explicitly globally
     * routable. All special-purpose, documentation, benchmark, reserved, private, link-local,
     * site-local, unique-local, loopback, multicast, translation, and unspecified ranges are
     * rejected by explicit range checks (the JDK predicates do not cover them all).
     */
    static boolean isGloballyRoutable(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 ? ipv4GloballyRoutable(bytes) : ipv6GloballyRoutable(bytes);
    }

    private static boolean ipv4GloballyRoutable(byte[] b) {
        int b0 = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;
        int b2 = b[2] & 0xFF;
        if (b0 == 0 || b0 >= 224) {
            // 0.0.0.0/8 unspecified, 224/4 multicast, 240/4 reserved, 255.255.255.255 broadcast
            return false;
        }
        if (b0 == 10) {
            return false; // RFC 1918
        }
        if (b0 == 100 && (b1 & 0xC0) == 0x40) {
            return false; // CGNAT 100.64.0.0/10
        }
        if (b0 == 127) {
            return false; // loopback
        }
        if (b0 == 169 && b1 == 254) {
            return false; // link-local 169.254.0.0/16
        }
        if (b0 == 172 && (b1 & 0xF0) == 16) {
            return false; // RFC 1918
        }
        if (b0 == 192 && b1 == 0) {
            return false; // 192.0.0.0/24 protocol assignments and 192.0.2.0/24 documentation
        }
        if (b0 == 192 && b1 == 88) {
            return false; // 192.88.99.0/24 deprecated 6to4 relay anycast
        }
        if (b0 == 192 && b1 == 168) {
            return false; // RFC 1918
        }
        if (b0 == 198 && (b1 & 0xFE) == 0x12) {
            return false; // 198.18.0.0/15 benchmark
        }
        if (b0 == 198 && b1 == 51 && b2 == 100) {
            return false; // 198.51.100.0/24 documentation
        }
        if (b0 == 203 && b1 == 0 && b2 == 113) {
            return false; // 203.0.113.0/24 documentation
        }
        return true;
    }

    private static boolean ipv6GloballyRoutable(byte[] b) {
        int b0 = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;
        int b2 = b[2] & 0xFF;
        int b3 = b[3] & 0xFF;
        // Positive gate: only 2000::/3 global unicast is eligible; everything else (loopback,
        // link/site-local, ULA, multicast, IPv4-mapped, NAT64 including local-use 64:ff9b:1::/48,
        // discard, and all unallocated/reserved space such as 4000::/1) fails.
        if ((b0 & 0xE0) != 0x20) {
            return false;
        }
        // IANA special-purpose and non-global registrations inside 2000::/3; conservative
        // rejection: the whole 2001::/23 IETF protocol-assignment block is refused (covers
        // Teredo 2001::/32, benchmarking 2001:2::/48, AMT 2001:3::/32, AS112 2001:4:112::/48,
        // ORCHID 2001:10::/28, ORCHIDv2 2001:20::/28, and the rest).
        if (b1 == 0x01 && b2 <= 1) {
            return false;
        }
        if (b1 == 0x01 && b2 == 0x0D && b3 == 0xB8) {
            return false; // documentation 2001:db8::/32
        }
        if (b1 == 0x02) {
            return false; // 6to4 2002::/16
        }
        if (b0 == 0x26 && b1 == 0x20 && b2 == 0x00 && b3 == 0x4F && b[4] == (byte) 0x80
                && b[5] == 0) {
            return false; // direct-delegation AS112 anycast 2620:4f:8000::/48
        }
        if (b0 == 0x3F && (b1 & 0xF0) == 0xF0) {
            return false; // documentation 3fff::/20
        }
        return true;
    }

    private static boolean digestMatches(byte[] body, String declared) {
        return hex(sha256(body)).equals(declared);
    }

    private static byte[] sha256(byte[] body) {
        return sha256().digest(body);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static String hex(byte[] hash) {
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    // ---------------------------------------------------------- pinned TLS transport

    /**
     * Connects to the approved addresses in order, never resolving the host itself. One
     * absolute monotonic deadline covers connect, TLS handshake, headers, and body across
     * address retries; each blocking read re-derives the remaining budget.
     */
    private Response httpsGet(URI uri, List<InetAddress> approved) throws IOException {
        long deadline = clock.nanoTime() + REQUEST_TIMEOUT.toNanos();
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        String host = uri.getHost();
        IOException lastFailure = null;
        for (InetAddress address : approved) {
            try {
                return tlsExchange(uri, host, address, port, deadline);
            } catch (IOException failure) {
                lastFailure = failure;
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("no approved address for "
                + host);
    }

    /** One HTTPS exchange over a socket connected to the pinned address with TLS identity. */
    private Response tlsExchange(URI uri, String host, InetAddress address, int port,
            long deadline) throws IOException {
        SSLContext context;
        try {
            context = SSLContext.getDefault();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("default TLS context unavailable", impossible);
        }
        Socket socket = new Socket();
        try {
            long remaining = remainingMillis(deadline);
            socket.connect(new InetSocketAddress(address, port),
                    (int) Math.min(CONNECT_TIMEOUT.toMillis(), remaining));
            socket.setSoTimeout(remainingMillis(deadline));
            SSLSocket ssl = (SSLSocket) context.getSocketFactory()
                    .createSocket(socket, host, port, true);
            try {
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (!isIpLiteral(host)) {
                    parameters.setServerNames(List.of(new SNIHostName(host)));
                }
                ssl.setSSLParameters(parameters);
                runHandshakeUnderDeadline(() -> ssl.startHandshake(), socket, clock, deadline);
                return exchange(ssl, uri, host, port, deadline);
            } finally {
                ssl.close();
            }
        } finally {
            socket.close();
        }
    }

    private int remainingMillis(long deadline) throws IOException {
        long remaining = deadline - clock.nanoTime();
        if (remaining <= 0) {
            throw new IOException("exchange deadline exceeded");
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, remaining / 1_000_000));
    }

    /**
     * Runs the TLS handshake under the same absolute deadline as the rest of the exchange. A
     * per-exchange daemon watchdog closes the socket at the deadline, so a handshake that
     * trickles across many reads (each individually below SO_TIMEOUT) still cannot extend the
     * exchange; the watchdog scheduler is shut down as soon as the handshake completes, so no
     * executor or thread leaks. Package-private so the deadline owner is testable without TLS.
     */
    static void runHandshakeUnderDeadline(Handshake handshake, Socket socket, Clock clock,
            long deadlineNanos) throws IOException {
        long remaining = deadlineNanos - clock.nanoTime();
        if (remaining <= 0) {
            throw new IOException("exchange deadline exceeded before handshake");
        }
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "qualification-handshake-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        try {
            watchdog.schedule(() -> {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // the handshake is already observing a closed socket
                }
            }, Math.max(1, remaining / 1_000_000), TimeUnit.MILLISECONDS);
            handshake.run();
        } finally {
            watchdog.shutdownNow();
        }
    }

    /** Writes a bounded HTTP/1.1 request and parses the bounded response under the deadline. */
    private Response exchange(SSLSocket ssl, URI uri, String host, int port, long deadline)
            throws IOException {
        String request = "GET " + rawPathAndQuery(uri) + " HTTP/1.1\r\n"
                + "Host: " + host + (port == 443 ? "" : ":" + port) + "\r\n"
                + "Connection: close\r\n"
                + "User-Agent: libgdx-ui-markup-qualification/0.1\r\n"
                + "Accept: image/jpeg, image/png\r\n\r\n";
        OutputStream out = ssl.getOutputStream();
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = new DeadlineInputStream(
                new BufferedInputStream(ssl.getInputStream()), ssl, clock, deadline);
        return parseResponse(in);
    }

    /**
     * Parses one HTTP/1.1 response: status line, case-insensitive headers, and a body that is
     * either Content-Length bounded, read to EOF, or a compliant chunked stream. Conflicting or
     * malformed Content-Length values, Transfer-Encoding combined with Content-Length, and any
     * transfer-coding chain other than a single terminal {@code chunked} are rejected, so
     * request-smuggling framing cannot pass. Package-private for deterministic framing tests.
     */
    static Response parseResponse(InputStream in) throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        String statusLine = readHeaderLine(in, headerBytes);
        if (statusLine == null) {
            throw new IOException("empty HTTP response");
        }
        int status = parseStatus(statusLine);
        String contentType = "";
        String location = "";
        List<String> contentLengths = new ArrayList<>();
        List<String> transferEncodings = new ArrayList<>();
        String line;
        while ((line = readHeaderLine(in, headerBytes)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (name) {
                case "content-type" -> contentType = value;
                case "location" -> location = value;
                case "content-length" -> contentLengths.add(line.substring(colon + 1));
                case "transfer-encoding" -> transferEncodings.add(value);
                default -> { }
            }
        }
        byte[] body;
        if (!transferEncodings.isEmpty()) {
            if (!contentLengths.isEmpty()) {
                throw new IOException("Transfer-Encoding and Content-Length both present");
            }
            // Accept only a single terminal "chunked" coding with no other codings; anything
            // else (gzip chains, duplicate TE fields, non-terminal chunked) is unsupported.
            String combined = String.join(",", transferEncodings);
            if (!combined.trim().equalsIgnoreCase("chunked")) {
                throw new IOException("unsupported Transfer-Encoding: " + combined);
            }
            body = readChunkedBody(in);
        } else {
            Long contentLength = null;
            for (String raw : contentLengths) {
                // Content-Length is 1*DIGIT: OWS after the colon is the field delimiter, but
                // the value itself must be pure ASCII digits with no sign, spaces, or overflow.
                String candidate = raw.stripLeading();
                if (candidate.isEmpty() || !CONTENT_LENGTH.matcher(candidate).matches()
                        || candidate.length() > 19) {
                    throw new IOException("malformed Content-Length: '" + raw + "'");
                }
                long parsed;
                try {
                    parsed = Long.parseLong(candidate);
                } catch (NumberFormatException overflow) {
                    throw new IOException("Content-Length overflow: '" + raw + "'", overflow);
                }
                if (contentLength != null && parsed != contentLength) {
                    throw new IOException("conflicting Content-Length values");
                }
                contentLength = parsed;
            }
            if (contentLength != null) {
                if (contentLength > MAX_BYTES) {
                    throw new IOException("declared content-length " + contentLength
                            + " exceeds the " + MAX_BYTES + " byte cap");
                }
                body = readExactly(in, contentLength.intValue());
            } else {
                body = readBounded(in);
            }
        }
        return new Response(status, contentType, location, body);
    }

    /**
     * Reads a chunked transfer body: hex chunk sizes (with optional chunk extensions), data,
     * CRLF terminators, and a bounded trailer section, with a total size cap of
     * {@code MAX_BYTES}. Canonical references never use chunked; the decoder exists so an
     * unexpected chunked response is still parsed under the same framing bounds instead of
     * being misread.
     */
    private static byte[] readChunkedBody(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readBoundedLine(in, MAX_HEADER_LINE);
            if (sizeLine == null) {
                throw new IOException("truncated chunked body");
            }
            int semicolon = sizeLine.indexOf(';');
            String sizeToken = (semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon))
                    .trim();
            long chunkSize;
            try {
                chunkSize = Long.parseLong(sizeToken, 16);
            } catch (NumberFormatException failure) {
                throw new IOException("malformed chunk size: " + sizeLine, failure);
            }
            if (chunkSize < 0 || body.size() + chunkSize > MAX_BYTES) {
                throw new IOException("chunked body exceeds the " + MAX_BYTES + " byte cap");
            }
            if (chunkSize == 0) {
                int trailerBytes = 0;
                while (true) {
                    String trailer = readBoundedLine(in, MAX_HEADER_LINE);
                    if (trailer == null) {
                        throw new IOException("truncated chunked trailers");
                    }
                    if (trailer.isEmpty()) {
                        return body.toByteArray(); // terminator: never counted against the cap
                    }
                    // Every raw octet including the CRLF counts toward the trailer bound.
                    trailerBytes += trailer.length() + 2;
                    if (trailerBytes > MAX_HEADER_BYTES) {
                        throw new IOException("chunked trailers exceed the " + MAX_HEADER_BYTES
                                + " byte bound");
                    }
                }
            }
            body.write(readExactly(in, (int) chunkSize));
            expectChunkTerminator(in);
        }
    }

    private static void expectChunkTerminator(InputStream in) throws IOException {
        int first = in.read();
        int second = in.read();
        if (first != '\r' || second != '\n') {
            throw new IOException("malformed chunk terminator (CRLF required)");
        }
    }

    private static String rawPathAndQuery(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getRawQuery();
        return query == null ? path : path + "?" + query;
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true; // IPv6 literal
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int parseStatus(String statusLine) throws IOException {
        int firstSpace = statusLine.indexOf(' ');
        if (firstSpace < 0) {
            throw new IOException("malformed HTTP status line: " + statusLine);
        }
        int secondSpace = statusLine.indexOf(' ', firstSpace + 1);
        String code = secondSpace < 0
                ? statusLine.substring(firstSpace + 1)
                : statusLine.substring(firstSpace + 1, secondSpace);
        try {
            return Integer.parseInt(code);
        } catch (NumberFormatException failure) {
            throw new IOException("malformed HTTP status code: " + statusLine, failure);
        }
    }

    private static String readHeaderLine(InputStream in, ByteArrayOutputStream headerBytes)
            throws IOException {
        String line = readBoundedLine(in, MAX_HEADER_LINE);
        if (line == null) {
            if (headerBytes.size() == 0) {
                return null; // clean EOF before any header
            }
            throw new IOException("truncated HTTP response headers");
        }
        // Every raw octet — including the CRLF terminator — counts toward the total header
        // bound, before any normalization.
        headerBytes.write(line.getBytes(StandardCharsets.ISO_8859_1));
        headerBytes.write('\r');
        headerBytes.write('\n');
        if (headerBytes.size() > MAX_HEADER_BYTES) {
            throw new IOException("response headers exceed the " + MAX_HEADER_BYTES
                    + " byte bound");
        }
        return line;
    }

    /**
     * Reads one HTTP line terminated by CRLF. Every raw octet — including the CR and LF of the
     * terminator — counts toward {@code maxLine}, so a line of exactly {@code maxLine} raw
     * octets passes and {@code maxLine + 1} fails. Returns null only on a clean EOF before the
     * first octet. Bare CR or LF anywhere else and other control octets (except HTAB) are
     * rejected, so injected line breaks cannot smuggle framing.
     */
    private static String readBoundedLine(InputStream in, int maxLine) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean sawCr = false;
        while (true) {
            int next = in.read();
            if (next < 0) {
                if (line.size() == 0) {
                    return null;
                }
                throw new IOException(sawCr ? "bare CR at end of HTTP line"
                        : "truncated HTTP line");
            }
            if (sawCr) {
                if (next == '\n') {
                    line.write(next); // the LF also counts toward the cap
                    if (line.size() > maxLine) {
                        throw new IOException("HTTP line exceeds the " + maxLine
                                + " byte bound");
                    }
                    byte[] raw = line.toByteArray();
                    return new String(raw, 0, raw.length - 2, StandardCharsets.ISO_8859_1);
                }
                throw new IOException("bare CR in HTTP line");
            }
            line.write(next);
            if (line.size() > maxLine) {
                throw new IOException("HTTP line exceeds the " + maxLine + " byte bound");
            }
            if (next == '\n') {
                throw new IOException("bare LF in HTTP line");
            }
            if (next == '\r') {
                sawCr = true;
                continue;
            }
            if ((next < 0x20 && next != '\t') || next == 0x7F) {
                throw new IOException("control octet 0x" + Integer.toHexString(next)
                        + " in HTTP line");
            }
        }
    }

    /** Reads exactly {@code length} bytes; a shorter body is a truncation failure. */
    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int total = 0;
        while (total < length) {
            int read = in.read(body, total, length - total);
            if (read < 0) {
                throw new IOException("truncated HTTP response body");
            }
            total += read;
        }
        return body;
    }

    /** Reads at most {@code MAX_BYTES + 1} bytes; larger responses are rejected as oversized. */
    private static byte[] readBounded(InputStream in) throws IOException {
        try (in) {
            byte[] buffer = new byte[(int) MAX_BYTES + 1];
            int total = 0;
            while (total < buffer.length) {
                int read = in.read(buffer, total, buffer.length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            if (total > MAX_BYTES) {
                throw new IOException("response exceeds the " + MAX_BYTES + " byte cap");
            }
            return Arrays.copyOf(buffer, total);
        }
    }

    private InetAddress[] resolveAll(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    /**
     * Creates a fresh, owner-only, randomly named session directory anchored under a parent
     * whose whole trust chain is proven (see {@link #secureParent}). The chain is revalidated
     * immediately before and immediately after the directory-relative creation, the session's
     * real-path identity is captured, and a {@link SecureDirectoryStream} is retained over the
     * session when the provider supports one so all later cache I/O is relative to the
     * anchored directory handle.
     */
    private static SessionAnchor createSessionDir(Path configuredRoot) {
        Path parent = secureParent(configuredRoot,
                Path.of(System.getProperty("java.io.tmpdir")));
        FilesystemPolicy policy = FilesystemPolicy.detect(parent);
        if (policy == null) {
            throw new ReferenceException(ReferenceException.Kind.IO,
                    "no filesystem policy can prove the cache parent security under " + parent);
        }
        // Revalidate the whole chain immediately before the directory-relative creation, so a
        // rename/replace of any component between selection and use cannot redirect the session.
        if (!anchoredChainSecure(parent, policy)) {
            throw new ReferenceException(ReferenceException.Kind.IO,
                    "cache parent trust chain changed between validation and use: " + parent);
        }
        Path session;
        try {
            session = policy.createPrivateDirectory(parent, "libgdx-qualification-");
        } catch (IOException | UnsupportedOperationException failure) {
            // PosixFilePermissions.asFileAttribute and ACL views can be unsupported: convert
            // to a typed failure instead of leaking a raw exception.
            throw new ReferenceException(ReferenceException.Kind.IO,
                    "cannot create private cache session under " + parent, failure);
        }
        try {
            // Revalidate identity immediately after creation: the session is a real directory
            // and its whole parent chain is still anchored (NOFOLLOW), so a swap during
            // creation is detected before the session is ever used.
            if (!Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS)
                    || !anchoredChainSecure(session.getParent(), policy)) {
                throw new IOException("session dir identity revalidation failed under " + parent);
            }
            Path realSession = session.toRealPath();
            Object fileKey = fileKeyOf(session);
            if (fileKey == null) {
                // A missing file key means the directory identity is unprovable; a store that
                // cannot prove its own session identity fails closed instead of operating.
                throw new IOException("cannot establish the session directory identity "
                        + "(no file key) under " + parent);
            }
            return new SessionAnchor(session, realSession, fileKey,
                    openSecureDirectoryStream(parent), openSecureDirectoryStream(session));
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.IO,
                    "cannot verify private cache session under " + parent, failure);
        }
    }

    /**
     * Chooses a provably secure session parent: the configured root when its whole trust chain
     * is secure, otherwise the OS temp when ITS chain is secure; if neither can be proven, the
     * store fails closed with a typed {@link ReferenceException} instead of falling back to an
     * unvalidated location. Package-private for deterministic policy seams.
     */
    static Path secureParent(Path configuredRoot, Path osTemp) {
        try {
            Files.createDirectories(configuredRoot);
        } catch (IOException ignored) {
            // leave the directory missing; the trust-chain validation decides
        }
        FilesystemPolicy policy = FilesystemPolicy.detect(configuredRoot);
        if (policy == null) {
            policy = FilesystemPolicy.detect(osTemp);
        }
        if (policy == null) {
            throw new ReferenceException(ReferenceException.Kind.IO,
                    "no filesystem policy (POSIX attributes or ACLs) can prove the cache "
                            + "parent security");
        }
        if (anchoredChainSecure(configuredRoot, policy)) {
            return configuredRoot;
        }
        if (anchoredChainSecure(osTemp, policy)) {
            return osTemp;
        }
        throw new ReferenceException(ReferenceException.Kind.IO,
                "no provably secure cache parent: " + configuredRoot + " and " + osTemp
                        + " both fail the trust-chain policy");
    }

    /**
     * Proves every component from the filesystem root down to {@code root}: each ancestor must
     * be a real directory (NOFOLLOW) that cannot be used to replace its next component —
     * sticky shared root or writable only by a trusted owner — and the final component must be
     * a provably secure session parent. A symlink or unprovable component anywhere in the
     * chain breaks the anchor.
     */
    static boolean anchoredChainSecure(Path root, FilesystemPolicy policy) {
        List<Path> components = new ArrayList<>();
        for (Path current = root.toAbsolutePath().normalize();
                current != null; current = current.getParent()) {
            components.add(current);
        }
        Collections.reverse(components);
        for (int i = 0; i < components.size(); i++) {
            Path component = components.get(i);
            boolean secure = (i == components.size() - 1)
                    ? policy.isSecureParent(component)
                    : policy.isSecureAncestor(component);
            if (!secure) {
                return false;
            }
        }
        return true;
    }

    /**
     * Proves the parent is secure under the best available policy (POSIX mode bits, then ACL);
     * when no policy can probe the filesystem the parent is refused (fail closed).
     * Package-private for tests.
     */
    static boolean isSecureParent(Path parent) {
        FilesystemPolicy policy = FilesystemPolicy.detect(parent);
        return policy != null && policy.isSecureParent(parent);
    }

    private static SecureDirectoryStream<Path> openSecureDirectoryStream(Path dir) {
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(dir);
            if (stream instanceof SecureDirectoryStream<Path> secure) {
                return secure;
            }
            stream.close();
            return null;
        } catch (IOException | UnsupportedOperationException unavailable) {
            return null; // fall back to path-based (revalidated) cache operations
        }
    }

    private static UserPrincipal currentUser() {
        try {
            return FileSystems.getDefault().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.IO,
                    "cannot resolve the current user for the cache policy", failure);
        }
    }

    @Override
    public void close() {
        List<IOException> failures = new ArrayList<>();
        if (sessionStream != null && parentStream != null) {
            closeAnchored(failures);
        } else if (sessionPathAnchored()) {
            deleteRecursively(cacheDir, failures);
            try {
                Files.deleteIfExists(cacheDir);
            } catch (IOException failure) {
                failures.add(failure);
            }
        }
        if (failures.isEmpty()) {
            return;
        }
        IOException first = failures.get(0);
        for (int i = 1; i < failures.size(); i++) {
            first.addSuppressed(failures.get(i));
        }
        throw new ReferenceException(ReferenceException.Kind.IO,
                "failed to clean the session cache " + cacheDir, first);
    }

    /**
     * Anchored cleanup. The retained stream's directory iterator is stale on some filesystems
     * (for example btrfs) once the directory has been modified, so contents are deleted
     * through a FRESH stream that is proven — by two non-null equal file keys — to anchor the
     * same directory as the retained fd. A replaced path is never opened or deleted
     * recursively: the replacement tree stays untouched, only the retained fd's own contents
     * may be cleaned, and the session entry is removed relative to the verified parent only
     * when the entry still carries the anchored identity. Anything unprovable is reported as a
     * typed cleanup failure instead of being deleted.
     */
    private void closeAnchored(List<IOException> failures) {
        try (DirectoryStream<Path> fresh = Files.newDirectoryStream(cacheDir)) {
            if (fresh instanceof SecureDirectoryStream<Path> freshSds) {
                if (sameDirectory(freshSds, sessionStream)) {
                    deleteThroughStream(freshSds, failures);
                } else {
                    failures.add(new IOException("session cache path no longer anchors the "
                            + "session directory; the replacement tree is untouched: "
                            + cacheDir));
                    cleanupRetainedContents(failures);
                }
            } else if (sessionPathAnchored()) {
                deleteRecursively(cacheDir, failures);
            } else {
                failures.add(new IOException("session cache path is no longer anchored: "
                        + cacheDir));
                cleanupRetainedContents(failures);
            }
        } catch (IOException failure) {
            failures.add(failure);
        }
        // Remove the session entry only relative to the verified parent and only when the
        // entry still carries the anchored identity; otherwise the entry leaks (reported).
        if (sessionEntryMatchesAnchor()) {
            try {
                parentStream.deleteDirectory(Path.of(sessionName()));
            } catch (IOException failure) {
                failures.add(failure);
            }
        } else {
            failures.add(new IOException("session entry lost the anchored identity; not "
                    + "deleting through the parent: " + cacheDir));
        }
        try {
            sessionStream.close();
        } catch (IOException failure) {
            failures.add(failure);
        }
        try {
            parentStream.close();
        } catch (IOException failure) {
            failures.add(failure);
        }
    }

    /** Best-effort cleanup of the original session contents through the retained fd. */
    private void cleanupRetainedContents(List<IOException> failures) {
        try {
            deleteThroughStream(sessionStream, failures);
        } catch (RuntimeException staleIterator) {
            failures.add(new IOException("cannot enumerate the retained session contents for "
                    + "cleanup: " + staleIterator));
        }
    }

    private String sessionName() {
        return cacheDir.getFileName().toString();
    }

    /**
     * Proves the session entry inside the verified parent still has the anchored identity:
     * a non-null file key equal to the anchor key. A missing, replaced, or unprovable entry
     * is never deleted.
     */
    private boolean sessionEntryMatchesAnchor() {
        try {
            BasicFileAttributeView view = parentStream.getFileAttributeView(Path.of(sessionName()),
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            return view != null
                    && sameFileKey(view.readAttributes().fileKey(), anchorFileKey);
        } catch (IOException | UnsupportedOperationException unprovable) {
            return false;
        }
    }

    /**
     * Proves two secure directory streams reference the same directory: both must expose a
     * non-null file key and the keys must be equal. Null on either side is unprovable, never
     * the same directory.
     */
    private static boolean sameDirectory(SecureDirectoryStream<Path> first,
            SecureDirectoryStream<Path> second) {
        try {
            Object firstKey = first.getFileAttributeView(
                    BasicFileAttributeView.class).readAttributes().fileKey();
            Object secondKey = second.getFileAttributeView(
                    BasicFileAttributeView.class).readAttributes().fileKey();
            return sameFileKey(firstKey, secondKey);
        } catch (IOException | UnsupportedOperationException unprovable) {
            return false;
        }
    }

    /** Identity check for path-based cleanup: the session path still names the anchor. */
    private boolean sessionPathAnchored() {
        if (!Files.isDirectory(cacheDir, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return sameFileKey(fileKeyOf(cacheDir), anchorFileKey)
                    && cacheDir.toRealPath().equals(anchorRealPath);
        } catch (IOException | UnsupportedOperationException failure) {
            return false;
        }
    }

    /** Deletes the session tree through a secure stream without following symlinks. */
    private static void deleteThroughStream(SecureDirectoryStream<Path> stream,
            List<IOException> failures) {
        List<Path> names = new ArrayList<>();
        try {
            for (Path entry : stream) {
                names.add(entry.getFileName());
            }
        } catch (DirectoryIteratorException failure) {
            failures.add(failure.getCause());
            return;
        }
        for (Path name : names) {
            try {
                if (isDirectoryEntry(stream, name)) {
                    try (SecureDirectoryStream<Path> child = stream.newDirectoryStream(name)) {
                        deleteThroughStream(child, failures);
                    }
                    stream.deleteDirectory(name);
                } else {
                    stream.deleteFile(name);
                }
            } catch (IOException failure) {
                failures.add(failure);
            }
        }
    }

    private static boolean isDirectoryEntry(SecureDirectoryStream<Path> stream, Path name) {
        try {
            BasicFileAttributeView view = stream.getFileAttributeView(name,
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            return view != null && view.readAttributes().isDirectory();
        } catch (IOException | UnsupportedOperationException unprovable) {
            return false; // treat as a file; deleteFile surfaces the real error
        }
    }

    /** Deletes the session tree without following symlinks, aggregating every failure. */
    private static void deleteRecursively(Path root, List<IOException> failures) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    failures.add(failure);
                }
            }
        } catch (IOException failure) {
            failures.add(failure);
        }
    }

    /**
     * Enforces the per-exchange deadline on every blocking read by re-deriving the remaining
     * budget and re-arming the socket read timeout, so a slow trickle cannot extend an exchange
     * past its absolute deadline. Package-private for deterministic tests.
     */
    static final class DeadlineInputStream extends InputStream {
        private final InputStream delegate;
        private final Socket socket;
        private final Clock clock;
        private final long deadlineNanos;

        DeadlineInputStream(InputStream delegate, Socket socket, Clock clock, long deadlineNanos) {
            this.delegate = delegate;
            this.socket = socket;
            this.clock = clock;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int read() throws IOException {
            armTimeout();
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            armTimeout();
            return delegate.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private void armTimeout() throws IOException {
            long remaining = deadlineNanos - clock.nanoTime();
            if (remaining <= 0) {
                throw new IOException("exchange deadline exceeded");
            }
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE,
                    Math.max(1, remaining / 1_000_000)));
        }
    }
}
