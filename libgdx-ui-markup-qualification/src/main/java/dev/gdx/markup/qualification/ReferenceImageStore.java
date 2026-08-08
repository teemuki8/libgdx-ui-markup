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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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

    /** A reference whose bytes were authenticated and decoded once into a bounded image. */
    public record ReferenceImage(BufferedImage image) {
        public ReferenceImage {
            Objects.requireNonNull(image, "image");
        }
    }

    private final Path cacheDir;
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
        this.cacheDir = createSessionDir(cacheDir);
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
        Path cached = cachePath(entry);
        if (Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(decodedFromCache(cached, entry));
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
        writeCache(cached, body);
        return Optional.of(image);
    }

    /** Returns the private session cache directory (package-private for tests). */
    Path sessionDir() {
        return cacheDir;
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

    /**
     * Re-authenticates a cache hit from a single NOFOLLOW handle: reads the bytes once, then
     * checks length, digest, and header dimensions before decoding. A forged or tampered cache
     * entry fails the qualification with a typed {@code CACHE} error.
     */
    private ReferenceImage decodedFromCache(Path cached, CorpusEntry entry) {
        byte[] bytes;
        try {
            bytes = readBounded(cached);
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.CACHE,
                    "cannot read cache file " + cached, failure);
        }
        if (bytes.length != entry.bytes()) {
            throw new ReferenceException(ReferenceException.Kind.CACHE,
                    "cache file " + cached + " is " + bytes.length + " bytes, declared "
                            + entry.bytes());
        }
        if (!digestMatches(bytes, entry.sha256())) {
            throw new ReferenceException(ReferenceException.Kind.CACHE,
                    "cache file " + cached + " fails the declared SHA-256 identity");
        }
        return decodeVerified(bytes, entry, "cache " + cached);
    }

    /**
     * Writes the verified bytes with CREATE_NEW and NOFOLLOW, so a pre-planted regular file or
     * symlink at the cache path is never replaced or followed.
     */
    private static void writeCache(Path cached, byte[] body) {
        try (SeekableByteChannel channel = Files.newByteChannel(cached,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.wrap(body);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.CACHE,
                    "cannot write cache file " + cached, failure);
        }
    }

    private static byte[] readBounded(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            long size = channel.size();
            if (size > MAX_BYTES) {
                throw new IOException("cache file exceeds the " + MAX_BYTES + " byte cap");
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new IOException("truncated cache file " + file);
                }
            }
            return buffer.array();
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

    private Path cachePath(CorpusEntry entry) {
        return cacheDir.resolve(entry.id() + extension(entry.mediaType()));
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
                ByteArrayOutputStream trailerBytes = new ByteArrayOutputStream();
                while (true) {
                    String trailer = readBoundedLine(in, MAX_HEADER_LINE);
                    if (trailer == null) {
                        throw new IOException("truncated chunked trailers");
                    }
                    if (trailer.isEmpty()) {
                        return body.toByteArray(); // terminator: never counted against the cap
                    }
                    trailerBytes.write(trailer.getBytes(StandardCharsets.ISO_8859_1));
                    trailerBytes.write('\n');
                    if (trailerBytes.size() > MAX_HEADER_BYTES) {
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
        if (first == '\n') {
            return; // bare LF tolerated
        }
        if (first != '\r' || second != '\n') {
            throw new IOException("malformed chunk terminator");
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
        if (headerBytes.size() > MAX_HEADER_BYTES) {
            throw new IOException("response headers exceed the " + MAX_HEADER_BYTES
                    + " byte bound");
        }
        String line = readBoundedLine(in, MAX_HEADER_LINE);
        if (line == null) {
            if (headerBytes.size() == 0) {
                return null; // clean EOF before any header
            }
            throw new IOException("truncated HTTP response headers");
        }
        headerBytes.write(line.getBytes(StandardCharsets.ISO_8859_1));
        headerBytes.write('\n');
        return line;
    }

    /** Reads one CRLF/LF-terminated line of at most {@code maxLine} bytes; null on clean EOF. */
    private static String readBoundedLine(InputStream in, int maxLine) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int next = in.read();
            if (next < 0) {
                if (line.size() == 0) {
                    return null;
                }
                throw new IOException("truncated HTTP line");
            }
            line.write(next);
            if (line.size() > maxLine) {
                throw new IOException("HTTP line exceeds the " + maxLine + " byte bound");
            }
            if (next == '\n') {
                String value = line.toString(StandardCharsets.ISO_8859_1);
                return value.substring(0, value.length() - 1).replace("\r", "");
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
     * Creates a fresh, owner-only, randomly named session directory anchored directly under a
     * parent whose security policy is proven (see {@link #secureParent}). The session's real
     * path is verified to stay inside the parent and to be a real directory (no-follow
     * identity), and the parent is re-resolved so a rename/replace of an intermediate cannot
     * redirect the session.
     */
    private static Path createSessionDir(Path configuredRoot) {
        Path parent = secureParent(configuredRoot,
                Path.of(System.getProperty("java.io.tmpdir")));
        try {
            Path session = Files.createTempDirectory(parent, "libgdx-qualification-",
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            Path realParent = parent.toRealPath();
            if (!Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS)
                    || !session.toRealPath().startsWith(realParent)) {
                throw new IOException("session dir identity check failed under " + parent);
            }
            return session;
        } catch (IOException failure) {
            throw new ReferenceException(ReferenceException.Kind.IO,
                    "cannot create private cache session under " + parent, failure);
        }
    }

    /**
     * Chooses a provably secure session parent: the configured root when it is a real
     * directory chain with secure ownership/writability, otherwise the OS temp when IT is
     * secure; if neither can be proven, the store fails closed instead of falling back to an
     * unvalidated location. Package-private for deterministic policy seams.
     */
    static Path secureParent(Path configuredRoot, Path osTemp) {
        try {
            Files.createDirectories(configuredRoot);
        } catch (IOException ignored) {
            // leave the directory missing; validation below decides
        }
        if (componentsAreRealDirectories(configuredRoot)
                && isSecureParent(configuredRoot)) {
            return configuredRoot;
        }
        if (componentsAreRealDirectories(osTemp) && isSecureParent(osTemp)) {
            return osTemp;
        }
        throw new ReferenceException(ReferenceException.Kind.IO,
                "no provably secure cache parent: " + configuredRoot + " and " + osTemp
                        + " both fail the ownership/writability policy");
    }

    /**
     * Proves that every component from the filesystem root down to {@code root} is a real
     * directory (no symlink at any level), so an attacker cannot redirect the session by
     * renaming or replacing an intermediate path element.
     */
    static boolean componentsAreRealDirectories(Path root) {
        Path current = root.toAbsolutePath().normalize();
        while (current != null) {
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            current = current.getParent();
        }
        return true;
    }

    /**
     * Proves the parent is secure: either it is owned by the current user and not writable by
     * group or others (private directory), or it has the sticky bit (shared-temp semantics, so
     * other principals cannot delete or rename our child). On ACL or other systems where the
     * policy cannot be proven, the parent is refused (fail closed). Package-private for tests.
     */
    static boolean isSecureParent(Path parent) {
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            int mode = (Integer) Files.getAttribute(parent, "unix:mode",
                    LinkOption.NOFOLLOW_LINKS);
            boolean sticky = (mode & 01000) != 0;
            boolean groupOrOtherWritable = (mode & 0022) != 0;
            boolean ownedByCurrentUser = Files.getOwner(parent).equals(currentUser());
            return (ownedByCurrentUser && !groupOrOtherWritable) || sticky;
        } catch (IOException | UnsupportedOperationException unprovable) {
            return false; // policy cannot be proven: fail closed
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
        deleteRecursively(cacheDir, failures);
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
