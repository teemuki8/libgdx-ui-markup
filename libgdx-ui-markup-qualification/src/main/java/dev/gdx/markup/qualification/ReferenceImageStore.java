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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * Fetches copyrighted reference images at test time into a per-run, bounded, in-memory cache
 * and never writes any remote bytes to disk. Every remote entry declares its exact identity
 * (HTTPS URL, SHA-256, byte length, media type, dimensions) and the store refuses anything
 * that does not match: the URL must be https on port 443 with a host and no user info or
 * fragment, the host must be in the allowlist, and every fetch connects over TLS to one
 * approved, globally-routable resolved address (the transport never re-resolves, so a
 * rebinding attack cannot reach a different peer). Redirects are followed manually with the
 * same policy applied to a fresh approval per target, bounded to {@link #MAX_REDIRECTS}. The
 * payload must match the declared digest, byte length, media type, and header dimensions, and
 * is decoded once (via {@code ImageReader} source subsampling) into an immutable
 * {@link ReferenceImage} that exposes no mutable {@code BufferedImage} or array.
 *
 * <p>Verified references are retained in a bounded content-addressed cache keyed by the
 * complete expected identity ({@link CacheKey}: canonical declared source URL, digest, byte
 * length, media type, and header dimensions), so entries at different URLs can never alias or
 * share a slot even with identical content identity. The cache is limited by an explicit
 * maximum entry count plus cumulative encoded bytes and cumulative decoded pixels, all with
 * overflow-safe admission; when admitting would exceed a budget the least recently used entry
 * is evicted first, and an entry that alone exceeds every budget is still served but never
 * retained. Cache hits re-check the requested identity against the cached key and image
 * invariants and return the same immutable image, so a hit can never be confused with another
 * identity or mutated. Concurrent requests for the same identity share one verified fetch.
 *
 * <p>The cache lives only for the store's lifetime and {@link #close()} owns every in-flight
 * fetch: it marks the store closed, terminally completes all pending waiters with a typed
 * {@link ReferenceException.Kind#CLOSED} failure, aborts the transport (the default pinned
 * transport tracks and closes its active sockets), and waits on a monotonic bounded condition
 * until no active fetch owner remains — no post-close decode, admission, or result can be
 * delivered. The owning {@link QualificationRunner} always closes the store.
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
    /** Explicit maximum number of verified references retained per store. */
    public static final int MAX_CACHE_ENTRIES = 8;
    /** Cumulative encoded-byte budget for the retained cache (4 payloads at the cap). */
    public static final long MAX_CACHE_ENCODED_BYTES = 4 * MAX_BYTES;
    /** Cumulative decoded-pixel budget (4 analysis-resolution frames). */
    public static final long MAX_CACHE_DECODED_PIXELS =
            4L * BoundedDecode.MAX_ANALYSIS_DIMENSION * BoundedDecode.MAX_ANALYSIS_DIMENSION;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    /** Monotonic bound for close() to wait for in-flight fetch owners to drain. */
    private static final Duration CLOSE_DRAIN_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_HEADER_LINE = 16 * 1024;
    static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<Integer> ABSENT_STATUSES = Set.of(404, 410);
    private static final java.util.regex.Pattern CONTENT_LENGTH =
            java.util.regex.Pattern.compile("[0-9]+");
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

    /**
     * Transport seam: performs one GET against an already-approved resolved address. The
     * transport must connect only to {@code approved} addresses and must never resolve the
     * hostname itself, and it must abort every in-flight exchange when {@link #close()} is
     * called so the store's close can drain active fetches promptly.
     */
    public interface Transport {
        Response get(URI uri, List<InetAddress> approved) throws IOException;

        /**
         * GET under an absolute monotonic exchange deadline on the store's clock, so the
         * runner's one total run deadline also bounds the network step. Implementations that
         * already carry their own deadline cap may ignore the parameter; the default transport
         * uses the earlier of its own per-exchange timeout and this deadline.
         */
        default Response get(URI uri, List<InetAddress> approved, long deadlineNanos)
                throws IOException {
            return get(uri, approved);
        }

        /** Aborts every in-flight exchange; idempotent and never throws. */
        void close();
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
     * The complete expected identity of a remote reference: the canonical declared source URL
     * plus the digest, byte length, media type, and header dimensions declared by the
     * manifest. The cache is keyed by this whole identity — never by content alone — so two
     * entries with the same authenticated bytes at different URLs can never alias or join
     * each other's slot or fetch, and an unsafe URL can never hit another URL's cached entry.
     */
    record CacheKey(String sourceUrl, String sha256, long bytes, String mediaType, int width,
            int height) {

        static CacheKey of(CorpusEntry entry) {
            return new CacheKey(entry.sourceUrl(), entry.sha256(), entry.bytes(),
                    entry.mediaType(), entry.referenceWidth(), entry.referenceHeight());
        }
    }

    /**
     * Bounded in-memory cache budgets: explicit maximum entries, cumulative encoded bytes, and
     * cumulative decoded pixels. Injectable so adversarial limit tests are deterministic.
     */
    record CacheLimits(int maxEntries, long maxEncodedBytes, long maxDecodedPixels) {

        static final CacheLimits DEFAULT = new CacheLimits(MAX_CACHE_ENTRIES,
                MAX_CACHE_ENCODED_BYTES, MAX_CACHE_DECODED_PIXELS);
    }

    /**
     * Immutable decoded reference: the subsampled pixels are copied into a private array that
     * is never exposed, so a caller can read {@link #rgb(int, int)} but cannot mutate the
     * cached value. Identical instances are shared freely across cache hits and callers.
     */
    public static final class ReferenceImage {
        private final int width;
        private final int height;
        private final int[] argb;

        /** Copies the bounded decoded pixels out of the transient {@code BufferedImage}. */
        ReferenceImage(BufferedImage source) {
            Objects.requireNonNull(source, "source");
            this.width = source.getWidth();
            this.height = source.getHeight();
            long pixels = (long) width * height;
            if (pixels > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("image too large for the immutable pixel "
                        + "store: " + width + "x" + height);
            }
            this.argb = source.getRGB(0, 0, width, height, new int[(int) pixels], 0, width);
        }

        /** Decoded width. */
        public int width() {
            return width;
        }

        /** Decoded height. */
        public int height() {
            return height;
        }

        /** ARGB pixel value at ({@code x}, {@code y}); read-only, bounds-checked per coordinate. */
        public int rgb(int x, int y) {
            Objects.checkIndex(x, width);
            Objects.checkIndex(y, height);
            return argb[y * width + x];
        }
    }

    /** One admitted cache entry: the identity key and the immutable decoded image. */
    private static final class CachedEntry {
        final CacheKey key;
        final long encodedBytes;
        final long decodedPixels;
        final ReferenceImage image;

        CachedEntry(CacheKey key, ReferenceImage image) {
            this.key = key;
            this.encodedBytes = key.bytes();
            this.decodedPixels = (long) image.width() * image.height();
            this.image = image;
        }
    }

    private final Transport transport;
    private final HostResolver resolver;
    private final Set<String> allowedHosts;
    private final Clock clock;
    private final CacheLimits limits;
    /** Guards every cache and lifecycle field; access-order map gives least-recently-used order. */
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ownersIdle = lock.newCondition();
    private final Map<CacheKey, CachedEntry> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<CacheKey, CompletableFuture<Optional<ReferenceImage>>> inFlight =
            new java.util.HashMap<>();
    private long cacheEncodedBytes;
    private long cacheDecodedPixels;
    /** Number of threads currently executing a fetch (owner path); drained by close(). */
    private int activeOwners;
    private volatile boolean closed;

    /** Creates a store with the default pinned-TLS transport, DNS resolution, and host allowlist. */
    public ReferenceImageStore() {
        this(null, null, null, System::nanoTime, CacheLimits.DEFAULT);
    }

    /**
     * Creates a store over injected seams (transport, resolver, host allowlist); package-private
     * so deterministic tests never touch the network.
     */
    ReferenceImageStore(Transport transport, HostResolver resolver, Set<String> allowedHosts) {
        this(transport, resolver, allowedHosts, System::nanoTime, CacheLimits.DEFAULT);
    }

    /** Package-private seam with injected clock and cache budgets for deterministic tests. */
    ReferenceImageStore(Transport transport, HostResolver resolver, Set<String> allowedHosts,
            Clock clock, CacheLimits limits) {
        this.transport = transport != null ? transport : new PinnedTlsTransport();
        this.resolver = resolver != null ? resolver : this::resolveAll;
        this.allowedHosts = allowedHosts != null ? allowedHosts : DEFAULT_ALLOWED_HOSTS;
        this.clock = clock;
        this.limits = limits != null ? limits : CacheLimits.DEFAULT;
    }

    /**
     * Returns the authenticated reference image for the entry, fetching it when absent and
     * serving verified hits from the bounded in-memory cache. Empty only when the reference is
     * explicitly absent (HTTP 404/410). Policy, identity, cache, decode, and transport
     * failures raise {@link ReferenceException}; using the store after {@link #close()}
     * raises {@link IllegalStateException}, and a fetch in flight when {@code close()} runs
     * fails with {@link ReferenceException.Kind#CLOSED} instead of delivering a result.
     */
    public Optional<ReferenceImage> reference(CorpusEntry entry) {
        return reference(entry, Long.MAX_VALUE);
    }

    /**
     * Package-private overload that bounds the fetch by the caller's remaining monotonic
     * budget: the exchange deadline becomes the earlier of the store's own per-exchange
     * timeout and {@code remainingNanos} from now, so the runner's one total run deadline
     * also caps network work. {@code Long.MAX_VALUE} means no external bound. Shared
     * in-flight fetches keep their original deadline; only the owning caller's exchange is
     * re-bounded.
     */
    Optional<ReferenceImage> reference(CorpusEntry entry, long remainingNanos) {
        Objects.requireNonNull(entry, "entry");
        CacheKey key = CacheKey.of(entry);
        CompletableFuture<Optional<ReferenceImage>> pending;
        boolean shared = false;
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("reference store is closed");
            }
            CachedEntry hit = cache.get(key);
            if (hit != null) {
                recheckInvariants(hit, key);
                return Optional.of(hit.image);
            }
            pending = inFlight.get(key);
            if (pending != null) {
                // Another caller is already fetching this identity: share the verified result.
                shared = true;
            } else {
                pending = new CompletableFuture<>();
                inFlight.put(key, pending);
                activeOwners++;
            }
        } finally {
            lock.unlock();
        }
        if (shared) {
            return await(pending);
        }
        long deadlineNanos = saturatingAdd(clock.nanoTime(), remainingNanos);
        return fetchAndComplete(key, entry, pending, deadlineNanos);
    }

    /** Adds a positive duration to a monotonic timestamp without wrapping to the past. */
    private static long saturatingAdd(long now, long durationNanos) {
        if (durationNanos == Long.MAX_VALUE
                || now > Long.MAX_VALUE - durationNanos) {
            return Long.MAX_VALUE;
        }
        return now + durationNanos;
    }

    private Optional<ReferenceImage> fetchAndComplete(CacheKey key, CorpusEntry entry,
            CompletableFuture<Optional<ReferenceImage>> pending, long deadlineNanos) {
        try {
            Optional<ReferenceImage> result;
            try {
                result = fetchVerifiedEntry(entry, deadlineNanos);
            } catch (RuntimeException | Error failure) {
                // Complete the pending future BEFORE removing it from the in-flight map, so a
                // concurrent caller that already grabbed it joins this completed outcome
                // instead of starting a second fetch. close() owns the lock, so it cannot
                // interleave between the completion and the removal.
                lock.lock();
                try {
                    pending.completeExceptionally(failure);
                    inFlight.remove(key);
                } finally {
                    lock.unlock();
                }
                throw failure;
            }
            lock.lock();
            try {
                if (closed) {
                    // close() already terminally completed this pending with CLOSED; do not
                    // deliver a post-close result and do not admit anything.
                    inFlight.remove(key);
                    throw new ReferenceException(ReferenceException.Kind.CLOSED,
                            "reference store closed before the result was delivered");
                }
                if (result.isPresent()) {
                    admit(key, result.orElseThrow());
                }
                // Complete the pending future BEFORE removing it from the in-flight map, so a
                // concurrent caller that already grabbed it joins this completed outcome
                // instead of starting a second fetch. close() owns the lock, so it cannot
                // interleave between the completion and the removal.
                pending.complete(result);
                inFlight.remove(key);
            } finally {
                lock.unlock();
            }
            return result;
        } finally {
            endActiveOwner();
        }
    }

    /** Joins a shared in-flight fetch, unwrapping the original typed failure. */
    private static Optional<ReferenceImage> await(
            CompletableFuture<Optional<ReferenceImage>> pending) {
        try {
            return pending.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    /**
     * Re-checks the cached entry against the requested identity and its own invariants before
     * serving a hit, so a corrupted or mis-keyed entry can never be returned as another
     * identity.
     */
    private static void recheckInvariants(CachedEntry entry, CacheKey requested) {
        if (!entry.key.equals(requested) || entry.encodedBytes != requested.bytes()) {
            throw new ReferenceException(ReferenceException.Kind.CACHE,
                    "cached entry identity does not match the requested identity");
        }
        if (entry.image.width() != requested.width()
                || entry.image.height() != requested.height()) {
            throw new ReferenceException(ReferenceException.Kind.CACHE,
                    "cached image dimensions do not match the requested identity");
        }
        if (entry.decodedPixels != (long) entry.image.width() * entry.image.height()) {
            throw new ReferenceException(ReferenceException.Kind.CACHE,
                    "cached image pixel accounting is inconsistent");
        }
    }

    /**
     * Admits a verified image under every budget. While the new entry would exceed a budget
     * and the cache is non-empty, the least recently used entry is evicted; an entry that
     * alone exceeds every budget is served but never retained.
     */
    private void admit(CacheKey key, ReferenceImage image) {
        CachedEntry entry = new CachedEntry(key, image);
        while (!cache.isEmpty() && !fitsWithinLimits(entry)) {
            evictLeastRecentlyUsed();
        }
        if (!fitsWithinLimits(entry)) {
            return;
        }
        cache.put(key, entry);
        cacheEncodedBytes += entry.encodedBytes;
        cacheDecodedPixels += entry.decodedPixels;
    }

    private boolean fitsWithinLimits(CachedEntry entry) {
        return cache.size() + 1 <= limits.maxEntries
                && !exceedsBudget(cacheEncodedBytes, entry.encodedBytes, limits.maxEncodedBytes)
                && !exceedsBudget(cacheDecodedPixels, entry.decodedPixels,
                        limits.maxDecodedPixels);
    }

    private void evictLeastRecentlyUsed() {
        java.util.Iterator<Map.Entry<CacheKey, CachedEntry>> iterator = cache.entrySet().iterator();
        Map.Entry<CacheKey, CachedEntry> eldest = iterator.next();
        iterator.remove();
        cacheEncodedBytes -= eldest.getValue().encodedBytes;
        cacheDecodedPixels -= eldest.getValue().decodedPixels;
    }

    /**
     * Pure overflow-safe budget check: true when {@code current + added} would exceed
     * {@code budget}. Never performs the addition, so extreme values cannot overflow.
     */
    static boolean exceedsBudget(long current, long added, long budget) {
        return added > budget - current;
    }

    /** Number of retained cache entries (package-private for tests). */
    int cachedEntryCount() {
        lock.lock();
        try {
            return cache.size();
        } finally {
            lock.unlock();
        }
    }

    /** Cumulative encoded bytes of the retained cache (package-private for tests). */
    long cachedEncodedBytes() {
        lock.lock();
        try {
            return cacheEncodedBytes;
        } finally {
            lock.unlock();
        }
    }

    /** Cumulative decoded pixels of the retained cache (package-private for tests). */
    long cachedDecodedPixels() {
        lock.lock();
        try {
            return cacheDecodedPixels;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears every owned reference and owns every in-flight fetch. Marks the store closed,
     * terminally completes all pending waiters with a typed {@link ReferenceException.Kind#CLOSED}
     * failure, aborts the transport (the default pinned transport closes its active sockets),
     * and waits on a monotonic bounded condition until no active fetch owner remains, so no
     * post-close decode, admission, or result can be delivered. Idempotent; close failures
     * (transport abort, drain timeout, interrupt) are aggregated into a single typed
     * {@code ReferenceException(IO)} with suppressed causes. Images already handed out remain
     * readable because they are immutable and detached from the cache.
     */
    @Override
    public void close() {
        List<IOException> failures = new ArrayList<>();
        List<CompletableFuture<Optional<ReferenceImage>>> pending;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            pending = new ArrayList<>(inFlight.values());
            inFlight.clear();
            cache.clear();
            cacheEncodedBytes = 0;
            cacheDecodedPixels = 0;
        } finally {
            lock.unlock();
        }
        ReferenceException closedFailure = new ReferenceException(ReferenceException.Kind.CLOSED,
                "reference store closed; in-flight fetches are terminated");
        for (CompletableFuture<Optional<ReferenceImage>> future : pending) {
            future.completeExceptionally(closedFailure);
        }
        try {
            transport.close();
        } catch (RuntimeException failure) {
            failures.add(new IOException("transport abort failed", failure));
        }
        lock.lock();
        try {
            long deadline = clock.nanoTime() + CLOSE_DRAIN_TIMEOUT.toNanos();
            while (activeOwners > 0) {
                long remaining = deadline - clock.nanoTime();
                if (remaining <= 0) {
                    failures.add(new IOException(activeOwners
                            + " active fetch(es) did not drain within the "
                            + CLOSE_DRAIN_TIMEOUT.getSeconds() + "s close deadline"));
                    break;
                }
                try {
                    ownersIdle.awaitNanos(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failures.add(new IOException("interrupted while draining active fetches",
                            interrupted));
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
        if (failures.isEmpty()) {
            return;
        }
        IOException first = failures.get(0);
        for (int i = 1; i < failures.size(); i++) {
            first.addSuppressed(failures.get(i));
        }
        throw new ReferenceException(ReferenceException.Kind.IO,
                "failed to close the reference store", first);
    }

    private void endActiveOwner() {
        lock.lock();
        try {
            activeOwners--;
            if (activeOwners == 0) {
                ownersIdle.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    private Optional<ReferenceImage> fetchVerifiedEntry(CorpusEntry entry, long deadlineNanos) {
        byte[] body;
        try {
            body = fetchVerified(entry, deadlineNanos);
        } catch (IOException failure) {
            if (closed) {
                throw new ReferenceException(ReferenceException.Kind.CLOSED,
                        "reference store closed during the fetch of " + entry.sourceUrl(),
                        failure);
            }
            throw new ReferenceException(ReferenceException.Kind.IO,
                    "cannot fetch " + entry.sourceUrl(), failure);
        }
        // The network phase is over: refuse to allocate or verify anything after close.
        ensureOpen("after the network phase, before verify and decode");
        if (body == null) {
            return Optional.empty();
        }
        ReferenceImage image = decodeVerified(body, entry, entry.sourceUrl());
        ensureOpen("after decode, before returning the result");
        return Optional.of(image);
    }

    private void ensureOpen(String context) {
        if (closed) {
            throw new ReferenceException(ReferenceException.Kind.CLOSED,
                    "reference store closed " + context);
        }
    }

    /**
     * Fetches the entry with manual bounded redirect handling, approving a fresh set of
     * globally-routable addresses for every target, then verifying the payload against the
     * declared identity. Returns null when the reference is explicitly absent.
     */
    private byte[] fetchVerified(CorpusEntry entry, long deadlineNanos) throws IOException {
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
            Response response = transport.get(target, approved, deadlineNanos);
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
     * Default cancellation-capable transport. Connects to the approved addresses in order,
     * never resolving the host itself; one absolute monotonic deadline covers connect, TLS
     * handshake, headers, and body across address retries, and each blocking read re-derives
     * the remaining budget. Every socket is tracked so {@link #close()} can abort in-flight
     * exchanges promptly, which lets the store's close drain active fetches within its
     * bounded deadline.
     */
    private final class PinnedTlsTransport implements Transport {
        private final Object transportLock = new Object();
        private final Set<Socket> activeSockets = new HashSet<>();
        private boolean aborted;

        @Override
        public Response get(URI uri, List<InetAddress> approved) throws IOException {
            return get(uri, approved, clock.nanoTime() + REQUEST_TIMEOUT.toNanos());
        }

        @Override
        public Response get(URI uri, List<InetAddress> approved, long externalDeadline)
                throws IOException {
            // The earlier of the per-exchange timeout and the runner's remaining run budget
            // covers every connect, TLS handshake, header, and body read in this exchange.
            long deadline = Math.min(clock.nanoTime() + REQUEST_TIMEOUT.toNanos(),
                    externalDeadline);
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

        @Override
        public void close() {
            synchronized (transportLock) {
                aborted = true;
                for (Socket socket : activeSockets) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // the exchange is already observing a closed socket
                    }
                }
            }
        }

        /** Tracks a new socket unless the transport has already been aborted by close. */
        void register(Socket socket) throws IOException {
            synchronized (transportLock) {
                if (aborted) {
                    throw new IOException("transport aborted by store close");
                }
                activeSockets.add(socket);
            }
        }

        void unregister(Socket socket) {
            synchronized (transportLock) {
                activeSockets.remove(socket);
            }
        }
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
        ((PinnedTlsTransport) transport).register(socket);
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
            ((PinnedTlsTransport) transport).unregister(socket);
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
            // Subtraction-before-conversion bound: body.size() is int and the accumulated body
            // is already capped, so MAX_BYTES - body.size() cannot overflow, while the old
            // body.size() + chunkSize sum wraps negative for chunk sizes near Long.MAX_VALUE and
            // let the (int) cast below allocate a negative array instead of failing as framing.
            if (chunkSize < 0 || chunkSize > MAX_BYTES - body.size()) {
                throw new IOException("invalid chunked size (negative or exceeding the "
                        + MAX_BYTES + " byte cap)");
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
