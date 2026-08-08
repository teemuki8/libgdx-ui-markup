package dev.gdx.markup.qualification;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Fetches copyrighted reference images at test time into a gitignored cache and never
 * redistributes them. Every remote entry declares its exact identity (HTTPS URL, SHA-256,
 * byte length, media type, dimensions) and the store refuses anything that does not match:
 * the URL must be https with a host and no user info or fragment, the host must be in the
 * allowlist and resolve only to public addresses, redirects are followed manually with the
 * same policy applied to every target (bounded to {@link #MAX_REDIRECTS}), and the payload
 * must match the declared digest, byte length, media type, and decoded dimensions. Cache hits
 * are re-verified; a forged cache file is discarded and refetched. Entries that cannot be
 * fetched or verified report empty so the qualification marks them skipped instead of failing.
 *
 * <p>The transport and host resolver are injectable so deterministic tests never touch the
 * network or DNS.
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
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> DEFAULT_ALLOWED_HOSTS =
            Set.of("shared.akamai.steamstatic.com");

    /** One GET response: status, Content-Type, Location, and the bounded body bytes. */
    public record Response(int statusCode, String contentType, String location, byte[] body) {
        public Response {
            Objects.requireNonNull(body, "body");
            contentType = contentType == null ? "" : contentType;
            location = location == null ? "" : location;
        }
    }

    /** Transport seam: performs one GET and returns the full response. */
    @FunctionalInterface
    public interface Transport {
        Response get(URI uri) throws IOException;
    }

    /** Resolves a hostname to addresses; injectable so tests avoid real DNS. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Path cacheDir;
    private final Transport transport;
    private final HostResolver resolver;
    private final Set<String> allowedHosts;
    private final HttpClient http;

    /** Creates a store with the default HTTP transport, DNS resolution, and host allowlist. */
    public ReferenceImageStore(Path cacheDir) {
        this(cacheDir, null, null, null);
    }

    /**
     * Creates a store over injected seams (transport, resolver, host allowlist); package-private
     * so deterministic tests never touch the network.
     */
    ReferenceImageStore(Path cacheDir, Transport transport, HostResolver resolver,
            Set<String> allowedHosts) {
        this.cacheDir = cacheDir;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.transport = transport != null ? transport : this::httpGet;
        this.resolver = resolver != null ? resolver : this::resolveAll;
        this.allowedHosts = allowedHosts != null ? allowedHosts : DEFAULT_ALLOWED_HOSTS;
    }

    /**
     * Returns the verified cached reference image for the entry, fetching it when absent. Empty
     * when the image is unavailable, refused by policy, or does not match the declared identity.
     */
    public Optional<Path> reference(CorpusEntry entry) {
        Path cached = cachePath(entry);
        if (Files.isRegularFile(cached)) {
            try {
                if (verified(cached, entry)) {
                    return Optional.of(cached);
                }
                // A forged or corrupt cache entry is discarded and refetched.
                Files.deleteIfExists(cached);
            } catch (IOException failure) {
                // An unreadable cache entry is treated as absent and refetched.
            }
        }
        Path download = cacheDir.resolve(entry.id() + ".download");
        try {
            Files.createDirectories(cacheDir);
            Files.deleteIfExists(download);
            byte[] body = fetchVerified(entry);
            if (body == null) {
                return Optional.empty();
            }
            Files.write(download, body);
            Files.move(download, cached, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(cached);
        } catch (IOException | RejectedException failure) {
            try {
                Files.deleteIfExists(download);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
            return Optional.empty();
        }
    }

    /**
     * Fetches the entry with manual bounded redirect handling, validating every target against
     * the URL shape, host allowlist, and resolved address policy, then verifying the payload
     * against the declared identity. Returns null when the final response is not 200.
     */
    private byte[] fetchVerified(CorpusEntry entry) throws IOException {
        URI target;
        try {
            target = URI.create(entry.sourceUrl());
        } catch (IllegalArgumentException failure) {
            throw new RejectedException("malformed source URL: " + entry.sourceUrl(), failure);
        }
        int redirects = 0;
        while (true) {
            validateTarget(target);
            Response response = transport.get(target);
            if (REDIRECT_STATUSES.contains(response.statusCode())) {
                if (response.location().isEmpty()) {
                    throw new RejectedException("redirect without a Location header from " + target);
                }
                if (redirects >= MAX_REDIRECTS) {
                    throw new RejectedException("more than " + MAX_REDIRECTS + " redirects from "
                            + entry.sourceUrl());
                }
                redirects++;
                try {
                    target = target.resolve(response.location());
                } catch (IllegalArgumentException failure) {
                    throw new RejectedException("invalid redirect Location from " + target
                            + ": " + response.location(), failure);
                }
                continue;
            }
            if (response.statusCode() != 200) {
                return null; // unavailable: report the reference as skipped
            }
            verifyIdentity(response, entry, target);
            return response.body();
        }
    }

    /** Validates one request target (initial or redirect hop) before any request is issued. */
    private void validateTarget(URI target) throws RejectedException {
        try {
            CorpusEntry.validateSourceUrl(target.toString());
        } catch (ManifestException failure) {
            throw new RejectedException("refusing target " + target + ": " + failure.getMessage());
        }
        if (!allowedHosts.contains(normalizeHost(target.getHost()))) {
            throw new RejectedException("host " + target.getHost() + " is not in the allowlist");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(target.getHost());
        } catch (UnknownHostException failure) {
            throw new RejectedException("cannot resolve host " + target.getHost(), failure);
        }
        for (InetAddress address : addresses) {
            if (isProhibited(address)) {
                throw new RejectedException("host " + target.getHost() + " resolves to prohibited "
                        + "address class " + address.getHostAddress());
            }
        }
    }

    /** Refuses the payload unless media type, length, digest, and dimensions match the entry. */
    private static void verifyIdentity(Response response, CorpusEntry entry, URI target)
            throws RejectedException {
        String mediaType = baseMediaType(response.contentType());
        if (mediaType.isEmpty() || !ALLOWED_MEDIA_TYPES.contains(mediaType)
                || !mediaType.equals(entry.mediaType())) {
            throw new RejectedException("Content-Type '" + response.contentType()
                    + "' does not match the declared media type '" + entry.mediaType()
                    + "' from " + target);
        }
        if (response.body().length > MAX_BYTES) {
            throw new RejectedException("payload exceeds the " + MAX_BYTES + " byte cap from "
                    + target);
        }
        if (response.body().length != entry.bytes()) {
            throw new RejectedException("payload is " + response.body().length + " bytes, declared "
                    + entry.bytes() + " from " + target);
        }
        if (!digestMatches(response.body(), entry.sha256())) {
            throw new RejectedException("payload SHA-256 does not match the declared identity "
                    + "from " + target);
        }
        verifyDecoded(response.body(), entry);
    }

    /**
     * Re-verifies a cache hit against the declared identity (length, digest, decoded dimensions
     * and format). The digest binds the bytes, so a matching file cannot be attacker-substituted.
     */
    private static boolean verified(Path cached, CorpusEntry entry) throws IOException {
        if (Files.size(cached) != entry.bytes()) {
            return false;
        }
        if (!digestMatches(cached, entry.sha256())) {
            return false;
        }
        try (InputStream raw = Files.newInputStream(cached);
                ImageInputStream image = ImageIO.createImageInputStream(raw)) {
            return image != null && decodedMatches(image, entry);
        }
    }

    /**
     * Reads the image header (never the pixel data) and checks the declared dimensions and the
     * reader format against the entry, so a decompression-bomb header is refused without
     * allocating decoded pixels.
     */
    private static void verifyDecoded(byte[] body, CorpusEntry entry) throws RejectedException {
        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(body))) {
            if (input == null || !decodedMatches(input, entry)) {
                throw new RejectedException("decoded dimensions or format do not match the "
                        + "declared identity (" + entry.referenceWidth() + "x"
                        + entry.referenceHeight() + ", " + entry.mediaType() + ")");
            }
        } catch (IOException failure) {
            throw new RejectedException("cannot read image payload: " + failure.getMessage(),
                    failure);
        }
    }

    private static boolean decodedMatches(ImageInputStream input, CorpusEntry entry)
            throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            return false;
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(input);
            if (reader.getWidth(0) != entry.referenceWidth()
                    || reader.getHeight(0) != entry.referenceHeight()) {
                return false;
            }
            return expectedFormat(entry.mediaType()).equalsIgnoreCase(reader.getFormatName());
        } finally {
            reader.dispose();
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

    /** Rejects loopback, private, link-local, multicast, and unspecified address classes. */
    private static boolean isProhibited(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress();
    }

    private static boolean digestMatches(byte[] body, String declared) {
        return hex(sha256(body)).equals(declared);
    }

    private static boolean digestMatches(Path file, String declared) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = sha256();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest()).equals(declared);
        }
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

    private Response httpGet(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] body = readBounded(response.body());
            String contentType = response.headers().firstValue("content-type").orElse("");
            String location = response.headers().firstValue("location").orElse("");
            return new Response(response.statusCode(), contentType, location, body);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching " + uri, failure);
        }
    }

    private InetAddress[] resolveAll(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    @Override
    public void close() {
        http.close();
    }

    /** Deterministic policy or identity violation; the reference is reported as skipped. */
    private static final class RejectedException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RejectedException(String message) {
            super(message);
        }

        RejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
