package dev.gdx.markup.qualification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Authenticated remote reference pipeline. The store refuses non-https, user-info, fragment,
 * wrong-host, and non-443 targets, pins every fetch to freshly approved globally-routable
 * resolved addresses (never re-resolving), validates each redirect hop against the same policy,
 * and verifies digest, media type, length, and header dimensions before decoding into an
 * immutable {@link ReferenceImageStore.ReferenceImage}. Verified references are retained in a
 * bounded in-memory cache keyed by the complete expected identity with overflow-safe
 * admission; hits re-check the identity, cannot be mutated, and share one fetch across
 * concurrent callers. Policy, identity, cache, and decode failures raise typed
 * {@link ReferenceException}s; empty is reserved for explicitly absent references (404/410).
 * Transport and DNS are injected, so the suite never touches the network.
 */
final class ReferenceImageStoreTest {
    private static final String ALLOWED_HOST = "shared.akamai.steamstatic.com";
    private static final InetAddress PUBLIC_ADDRESS = ipv4(93, 184, 216, 34);

    /** Deterministic 2x2 PNG used as the canonical verified payload. */
    private static final byte[] PNG_2X2 = pngOrFail(2, 2);
    private static final String PNG_2X2_SHA256 = sha256(PNG_2X2);

    // ---------------------------------------------------------------- helpers

    private static byte[] pngOrFail(int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** A deterministic PNG with a distinctive digest per (size, seed). */
    private static byte[] png(int width, int height, int seed) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    image.setRGB(x, y, (0xFF << 24) | ((seed * 31 + x * 7 + y * 13) & 0xFFFFFF));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static InetAddress ipv4(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (UnknownHostException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static InetAddress literal(String value) {
        try {
            if (value.equals("::ffff:93.184.216.34")) {
                byte[] mapped = new byte[16];
                mapped[10] = (byte) 0xFF;
                mapped[11] = (byte) 0xFF;
                mapped[12] = 93;
                mapped[13] = (byte) 184;
                mapped[14] = (byte) 216;
                mapped[15] = 34;
                return java.net.Inet6Address.getByAddress(null, mapped, 0);
            }
            return InetAddress.getByName(value);
        } catch (UnknownHostException impossible) {
            throw new AssertionError("literal must parse without DNS: " + value, impossible);
        }
    }

    /** Valid per-component thresholds shared by every fabricated corpus entry. */
    private static final FidelityThresholds CORPUS_THRESHOLDS =
            new FidelityThresholds(0.2, 0.2, 0.2, 0.2);

    /** A remote entry whose declared identity exactly matches {@code body}. */
    private static CorpusEntry verifiedEntry(String url, byte[] body, String mediaType,
            int width, int height) {
        return new CorpusEntry("ref", url, null, "MIT", "ref.xml", CORPUS_THRESHOLDS, width, height,
                sha256(body), body.length, mediaType);
    }

    private static CorpusEntry canonicalEntry(String url) {
        return verifiedEntry(url, PNG_2X2, "image/png", 2, 2);
    }

    private static ReferenceImageStore.Response ok(byte[] body) {
        return new ReferenceImageStore.Response(200, "image/png", "", body);
    }

    private static ReferenceImageStore.Response redirect(String location) {
        return new ReferenceImageStore.Response(302, "", location, new byte[0]);
    }

    private ReferenceImageStore store(ReferenceImageStore.Transport transport,
            FakeResolver resolver, String... allowedHosts) {
        return store(transport, resolver, ReferenceImageStore.CacheLimits.DEFAULT, allowedHosts);
    }

    private ReferenceImageStore store(ReferenceImageStore.Transport transport,
            FakeResolver resolver, ReferenceImageStore.CacheLimits limits,
            String... allowedHosts) {
        return new ReferenceImageStore(transport, resolver, Set.of(allowedHosts),
                System::nanoTime, limits);
    }

    private FakeResolver publicResolver() {
        return new FakeResolver().with(ALLOWED_HOST, PUBLIC_ADDRESS);
    }

    private static ReferenceException reject(StoreCall call) {
        return assertThrows(ReferenceException.class, call::call);
    }

    @FunctionalInterface
    private interface StoreCall {
        Object call();
    }

    /** Serves nothing: any request is an unexpected network touch and fails the test. */
    private static FakeTransport silentTransport() {
        return new FakeTransport();
    }

    /**
     * Scripted transport whose exchanges block until {@link #release()} (or {@link #close()},
     * which aborts in-flight exchanges the same way the store's close does). Concurrent tests
     * use it to deterministically park every caller before the fetch completes.
     */
    private static final class LatchedTransport implements ReferenceImageStore.Transport {
        private final Deque<ReferenceImageStore.Response> script;
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<URI> requested = Collections.synchronizedList(new ArrayList<>());

        LatchedTransport(ReferenceImageStore.Response... responses) {
            this.script = new ArrayDeque<>(List.of(responses));
        }

        List<URI> requested() {
            return requested;
        }

        int requestCount() {
            return requested.size();
        }

        void release() {
            release.countDown();
        }

        @Override
        public ReferenceImageStore.Response get(URI uri, List<InetAddress> approved)
                throws IOException {
            requested.add(uri);
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", interrupted);
            }
            ReferenceImageStore.Response response;
            synchronized (script) {
                response = script.pollFirst();
            }
            if (response == null) {
                throw new AssertionError("unexpected request to " + uri);
            }
            return response;
        }

        @Override
        public void close() {
            release.countDown(); // abort in-flight exchanges
        }
    }

    private static final class FakeTransport implements ReferenceImageStore.Transport {
        private final List<URI> requested = new ArrayList<>();
        private final List<List<InetAddress>> approved = new ArrayList<>();
        private final Deque<ReferenceImageStore.Response> script = new ArrayDeque<>();
        private boolean closed;

        FakeTransport(ReferenceImageStore.Response... responses) {
            for (ReferenceImageStore.Response response : responses) {
                script.add(response);
            }
        }

        List<URI> requested() {
            return requested;
        }

        List<InetAddress> approved(int request) {
            return approved.get(request);
        }

        boolean closed() {
            return closed;
        }

        @Override
        public synchronized ReferenceImageStore.Response get(URI uri,
                List<InetAddress> pinned) {
            requested.add(uri);
            approved.add(List.copyOf(pinned));
            ReferenceImageStore.Response response = script.pollFirst();
            if (response == null) {
                throw new AssertionError("unexpected request to " + uri);
            }
            return response;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeResolver implements ReferenceImageStore.HostResolver {
        private final Map<String, InetAddress[]> byHost = new HashMap<>();
        private final Map<String, Integer> lookups = new HashMap<>();

        FakeResolver with(String host, InetAddress... addresses) {
            byHost.put(host, addresses);
            return this;
        }

        int lookups(String host) {
            return lookups.getOrDefault(host, 0);
        }

        @Override
        public InetAddress[] resolve(String host) {
            lookups.merge(host, 1, Integer::sum);
            InetAddress[] addresses = byHost.get(host);
            if (addresses == null) {
                throw new AssertionError("unexpected DNS lookup for " + host);
            }
            return addresses;
        }
    }

    /** Rewrites a PNG's IHDR width/height and fixes the chunk CRC (bytes 16-32 of the file). */
    private static byte[] patchPngDimensions(byte[] png, int width, int height) {
        byte[] patched = Arrays.copyOf(png, png.length);
        patched[16] = (byte) (width >>> 24);
        patched[17] = (byte) (width >>> 16);
        patched[18] = (byte) (width >>> 8);
        patched[19] = (byte) width;
        patched[20] = (byte) (height >>> 24);
        patched[21] = (byte) (height >>> 16);
        patched[22] = (byte) (height >>> 8);
        patched[23] = (byte) height;
        CRC32 crc = new CRC32();
        crc.update(patched, 12, 17); // "IHDR" type + 13 data bytes
        long value = crc.getValue();
        patched[29] = (byte) (value >>> 24);
        patched[30] = (byte) (value >>> 16);
        patched[31] = (byte) (value >>> 8);
        patched[32] = (byte) value;
        return patched;
    }

    // ---------------------------------------------------------------- happy path and cache

    @Test
    void fetchesVerifiedReferenceAndSecondCallHitsCache() throws IOException {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceImageStore.ReferenceImage first =
                    store.reference(canonicalEntry(url)).orElseThrow();
            assertEquals(2, first.width());
            assertEquals(2, first.height());
            assertEquals(1, transport.requested().size());
            assertEquals(List.of(PUBLIC_ADDRESS), transport.approved(0),
                    "the fetch must be pinned to the approved address");
            assertEquals(1, store.cachedEntryCount());
            assertEquals(PNG_2X2.length, store.cachedEncodedBytes());
            assertEquals(4, store.cachedDecodedPixels());
            ReferenceImageStore.ReferenceImage second =
                    store.reference(canonicalEntry(url)).orElseThrow();
            assertEquals(1, transport.requested().size(),
                    "a verified in-memory hit must not touch the network again");
            assertSame(first, second,
                    "cache hits must return the same immutable image");
        }
    }

    @Test
    void sameIdentityAtDifferentUrlsDoesNotShare() {
        String urlA = "https://" + ALLOWED_HOST + "/a.png";
        String urlB = "https://" + ALLOWED_HOST + "/b.png";
        FakeTransport transport = new FakeTransport(ok(PNG_2X2), ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceImageStore.ReferenceImage imageA =
                    store.reference(canonicalEntry(urlA)).orElseThrow();
            ReferenceImageStore.ReferenceImage imageB =
                    store.reference(canonicalEntry(urlB)).orElseThrow();
            assertEquals(2, transport.requested().size(),
                    "different URLs must never share a cache slot, even with identical "
                            + "content identity");
            assertTrue(imageA != imageB,
                    "each URL must be fetched, verified, and decoded independently");
            assertEquals(2, store.cachedEntryCount(),
                    "each URL's identity is its own cache key");
        }
    }

    @Test
    void unsafeUrlCannotHitAnotherUrlsCache() {
        String safeUrl = "https://" + ALLOWED_HOST + "/safe.png";
        String unsafeUrl = "http://evil.example.com/unsafe.png";
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            assertTrue(store.reference(canonicalEntry(safeUrl)).isPresent());
            assertEquals(1, transport.requested().size());
            // An entry that reuses the cached content identity but declares an unsafe URL
            // must not hit the safe entry's slot and must be refused before any request.
            ReferenceException failure = reject(() -> store.reference(canonicalEntry(unsafeUrl)));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind(),
                    "an unsafe source URL must be refused, never served from another URL's "
                            + "cached entry even with identical content identity");
            assertEquals(1, transport.requested().size(),
                    "the unsafe URL must be rejected before any request is issued");
            assertEquals(1, store.cachedEntryCount(),
                    "the safe entry stays cached and untouched");
        }
    }

    @Test
    void sameUrlDifferentIdentityDoesNotAlias() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        CorpusEntry real = canonicalEntry(url);
        CorpusEntry forged = new CorpusEntry("ref", url, null, "MIT", "ref.xml", CORPUS_THRESHOLDS, 2, 2,
                "0".repeat(64), PNG_2X2.length, "image/png");
        FakeTransport transport = new FakeTransport(ok(PNG_2X2), ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            assertTrue(store.reference(real).isPresent());
            assertEquals(1, transport.requested().size());
            ReferenceException failure = reject(() -> store.reference(forged));
            assertEquals(ReferenceException.Kind.IDENTITY_MISMATCH, failure.kind(),
                    "an entry with the same URL but a different declared identity must never "
                            + "be served from the verified entry's cache slot");
            assertEquals(2, transport.requested().size(),
                    "the aliased identity must be fetched and verified independently");
            assertEquals(1, store.cachedEntryCount(),
                    "only the verified identity may remain cached");
        }
    }

    @Test
    void concurrentDifferentUrlsDoNotJoin() throws Exception {
        String urlA = "https://" + ALLOWED_HOST + "/a.png";
        String urlB = "https://" + ALLOWED_HOST + "/b.png";
        FakeTransport transport = new FakeTransport(ok(PNG_2X2), ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            List<ReferenceImageStore.ReferenceImage> results =
                    Collections.synchronizedList(new ArrayList<>());
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            for (String url : List.of(urlA, urlB)) {
                CorpusEntry entry = canonicalEntry(url);
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        results.add(store.reference(entry).orElseThrow());
                    } catch (Throwable failure) {
                        failures.add(failure);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertTrue(failures.isEmpty(), "concurrent fetches must not fail: " + failures);
            assertEquals(2, transport.requested().size(),
                    "different URLs must never join each other's in-flight fetch");
            assertEquals(2, results.size());
            assertEquals(2, store.cachedEntryCount());
        }
    }

    @Test
    void followsBoundedRedirectToVerifiedBody() {
        String url = "https://" + ALLOWED_HOST + "/start.png";
        FakeTransport transport = new FakeTransport(redirect("/final.png"), ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceImageStore.ReferenceImage image =
                    store.reference(canonicalEntry(url)).orElseThrow();
            assertEquals(2, image.width());
            assertEquals(2, transport.requested().size());
            assertEquals(URI.create("https://" + ALLOWED_HOST + "/final.png"),
                    transport.requested().get(1),
                    "the relative redirect must be resolved against the request target");
        }
    }

    @Test
    void acceptsExplicitDefaultPort() {
        String url = "https://" + ALLOWED_HOST + ":443/ref.png";
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            assertTrue(store.reference(canonicalEntry(url)).isPresent());
        }
    }

    // ---------------------------------------------------------------- pinning

    @Test
    void resolvesEachHostExactlyOnce() {
        FakeResolver resolver = publicResolver();
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, resolver, ALLOWED_HOST)) {
            store.reference(canonicalEntry("https://" + ALLOWED_HOST + "/ref.png"));
            assertEquals(1, resolver.lookups(ALLOWED_HOST),
                    "the host must be resolved exactly once and the address pinned");
        }
    }

    @Test
    void carriesFreshApprovedAddressesAcrossRedirects() {
        InetAddress second = ipv4(1, 1, 1, 1);
        FakeResolver resolver = publicResolver().with("host2", second);
        FakeTransport transport = new FakeTransport(redirect("https://host2/final.png"),
                ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, resolver, ALLOWED_HOST, "host2")) {
            store.reference(canonicalEntry("https://" + ALLOWED_HOST + "/start.png"))
                    .orElseThrow();
            assertEquals(2, transport.requested().size());
            assertEquals(List.of(PUBLIC_ADDRESS), transport.approved(0));
            assertEquals(List.of(second), transport.approved(1),
                    "each redirect hop must carry a freshly approved address set");
            assertEquals(1, resolver.lookups(ALLOWED_HOST));
            assertEquals(1, resolver.lookups("host2"));
        }
    }

    // ---------------------------------------------------------------- target shape

    @Test
    void rejectsHttpSchemeUrl() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(
                    () -> store.reference(canonicalEntry("http://" + ALLOWED_HOST + "/ref.png")));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind());
            assertTrue(transport.requested().isEmpty(),
                    "no request may be issued for a non-https target");
        }
    }

    @Test
    void rejectsUrlWithUserInfo() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(canonicalEntry(
                    "https://attacker@" + ALLOWED_HOST + "/ref.png")));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind());
            assertTrue(transport.requested().isEmpty());
        }
    }

    @Test
    void rejectsUrlWithFragment() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(canonicalEntry(
                    "https://" + ALLOWED_HOST + "/ref.png#fragment")));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind());
            assertTrue(transport.requested().isEmpty());
        }
    }

    @Test
    void rejectsWrongHost() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(
                    () -> store.reference(canonicalEntry("https://example.com/ref.png")));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind());
            assertTrue(transport.requested().isEmpty(),
                    "a host outside the allowlist must be refused before any request");
        }
    }

    @Test
    void rejectsNonDefaultPort() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(canonicalEntry(
                    "https://" + ALLOWED_HOST + ":8443/ref.png")));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind());
            assertTrue(transport.requested().isEmpty());
        }
    }

    // ---------------------------------------------------------------- redirects

    @Test
    void rejectsRedirectToDisallowedHost() {
        String url = "https://" + ALLOWED_HOST + "/start.png";
        FakeTransport transport = new FakeTransport(redirect("https://evil.example.com/steal.png"));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(canonicalEntry(url)));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind());
            assertEquals(1, transport.requested().size(),
                    "the redirect target must be validated before the follow-up request");
        }
    }

    @Test
    void rejectsRedirectToHttpScheme() {
        String url = "https://" + ALLOWED_HOST + "/start.png";
        FakeTransport transport = new FakeTransport(redirect("http://evil.example.com/steal.png"));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(canonicalEntry(url)));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind());
            assertEquals(1, transport.requested().size());
        }
    }

    @Test
    void rejectsRedirectToNonDefaultPort() {
        String url = "https://" + ALLOWED_HOST + "/start.png";
        FakeTransport transport = new FakeTransport(redirect("https://" + ALLOWED_HOST + ":8443/x"));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(canonicalEntry(url)));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind());
            assertEquals(1, transport.requested().size());
        }
    }

    @Test
    void rejectsRedirectToLoopbackResolvedAddress() {
        String url = "https://" + ALLOWED_HOST + "/start.png";
        FakeTransport transport = new FakeTransport(redirect("https://host2/private.png"));
        try (ReferenceImageStore store = store(transport,
                publicResolver().with("host2", ipv4(127, 0, 0, 1)), ALLOWED_HOST, "host2")) {
            ReferenceException failure = reject(() -> store.reference(canonicalEntry(url)));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind());
            assertEquals(1, transport.requested().size(),
                    "a redirect resolving only to a loopback address must never be fetched");
        }
    }

    @Test
    void rejectsRedirectWithoutLocation() {
        String url = "https://" + ALLOWED_HOST + "/start.png";
        FakeTransport transport = new FakeTransport(
                new ReferenceImageStore.Response(302, "", "", new byte[0]));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(canonicalEntry(url)));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind());
            assertEquals(1, transport.requested().size());
        }
    }

    @Test
    void rejectsExcessiveRedirects() {
        String url = "https://" + ALLOWED_HOST + "/start.png";
        ReferenceImageStore.Response[] script = new ReferenceImageStore.Response[
                ReferenceImageStore.MAX_REDIRECTS + 1];
        for (int i = 0; i < script.length; i++) {
            script[i] = redirect("https://" + ALLOWED_HOST + "/hop" + i + ".png");
        }
        FakeTransport transport = new FakeTransport(script);
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(canonicalEntry(url)));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind());
            assertEquals(ReferenceImageStore.MAX_REDIRECTS + 1, transport.requested().size(),
                    "at most one more request than the redirect cap may be issued");
        }
    }

    // ---------------------------------------------------------------- address policy

    @Test
    void globallyRoutablePolicyTable() {
        record Case(String literal, boolean globallyRoutable) {
        }
        List<Case> cases = List.of(
                new Case("93.184.216.34", true),
                new Case("1.1.1.1", true),
                new Case("8.8.8.8", true),
                new Case("0.0.0.0", false),
                new Case("10.0.0.1", false),
                new Case("100.64.0.1", false),
                new Case("127.0.0.1", false),
                new Case("169.254.1.1", false),
                new Case("172.16.0.1", false),
                new Case("192.0.0.1", false),
                new Case("192.0.2.1", false),
                new Case("192.88.99.1", false),
                new Case("192.168.1.1", false),
                new Case("198.18.0.1", false),
                new Case("198.51.100.1", false),
                new Case("203.0.113.1", false),
                new Case("224.0.0.1", false),
                new Case("240.0.0.1", false),
                new Case("255.255.255.255", false),
                new Case("::", false),
                new Case("::1", false),
                new Case("fc00::1", false),
                new Case("fd00::1", false),
                new Case("fe80::1", false),
                new Case("fec0::1", false),
                new Case("ff02::1", false),
                new Case("2001:db8::1", false),
                new Case("2002::1", false),
                new Case("2001::1", false),
                new Case("2001:2::1", false),
                new Case("2001:10::1", false),
                new Case("2001:20::1", false),
                new Case("3fff::1", false),
                new Case("64:ff9b::1", false),
                new Case("::ffff:93.184.216.34", false),
                new Case("64:ff9b:1::1", false),
                new Case("100::1", false),
                new Case("2001:1::1", false),
                new Case("2001:3::1", false),
                new Case("2001:4:112::1", false),
                new Case("2620:4f:8000::1", false),
                new Case("4000::1", false),
                new Case("5f00::1", false),
                new Case("2606:4700::1111", true),
                new Case("2001:4860:4860::8888", true),
                new Case("2a00:1450:4001:824::200e", true),
                new Case("2400:cb00:2049:1::c629:d7a2", true));
        for (Case testCase : cases) {
            assertEquals(testCase.globallyRoutable(),
                    ReferenceImageStore.isGloballyRoutable(literal(testCase.literal())),
                    testCase.literal());
        }
    }

    @Test
    void rejectsPrivateResolvedAddress() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store = store(transport,
                new FakeResolver().with(ALLOWED_HOST, ipv4(10, 0, 0, 1)), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(
                    canonicalEntry("https://" + ALLOWED_HOST + "/ref.png")));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind());
            assertTrue(transport.requested().isEmpty());
        }
    }

    @Test
    void rejectsLoopbackResolvedAddress() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store = store(transport,
                new FakeResolver().with(ALLOWED_HOST, ipv4(127, 0, 0, 1)), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(
                    canonicalEntry("https://" + ALLOWED_HOST + "/ref.png")));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind());
            assertTrue(transport.requested().isEmpty());
        }
    }

    @Test
    void rejectsLinkLocalResolvedAddress() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store = store(transport,
                new FakeResolver().with(ALLOWED_HOST, ipv4(169, 254, 1, 1)), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(
                    canonicalEntry("https://" + ALLOWED_HOST + "/ref.png")));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind());
            assertTrue(transport.requested().isEmpty());
        }
    }

    @Test
    void rejectsUniqueLocalResolvedAddress() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store = store(transport,
                new FakeResolver().with(ALLOWED_HOST, literal("fc00::1")), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(
                    canonicalEntry("https://" + ALLOWED_HOST + "/ref.png")));
            assertEquals(ReferenceException.Kind.UNSAFE_TARGET, failure.kind());
            assertTrue(transport.requested().isEmpty());
        }
    }

    // ---------------------------------------------------------------- payload identity

    @Test
    void rejectsWrongDigest() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        CorpusEntry forged = new CorpusEntry("ref", url, null, "MIT", "ref.xml", CORPUS_THRESHOLDS, 2, 2,
                "0".repeat(64), PNG_2X2.length, "image/png");
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(forged));
            assertEquals(ReferenceException.Kind.IDENTITY_MISMATCH, failure.kind());
        }
    }

    @Test
    void rejectsWrongMediaType() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        CorpusEntry entry = new CorpusEntry("ref", url, null, "MIT", "ref.xml", CORPUS_THRESHOLDS, 2, 2,
                PNG_2X2_SHA256, PNG_2X2.length, "image/jpeg");
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(entry));
            assertEquals(ReferenceException.Kind.IDENTITY_MISMATCH, failure.kind());
        }
    }

    @Test
    void rejectsMissingContentType() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        FakeTransport transport = new FakeTransport(
                new ReferenceImageStore.Response(200, "", "", PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(canonicalEntry(url)));
            assertEquals(ReferenceException.Kind.IDENTITY_MISMATCH, failure.kind());
        }
    }

    @Test
    void rejectsWrongLength() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        CorpusEntry entry = new CorpusEntry("ref", url, null, "MIT", "ref.xml", CORPUS_THRESHOLDS, 2, 2,
                PNG_2X2_SHA256, PNG_2X2.length + 1, "image/png");
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(entry));
            assertEquals(ReferenceException.Kind.IDENTITY_MISMATCH, failure.kind());
        }
    }

    @Test
    void rejectsWrongDimensions() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        CorpusEntry entry = verifiedEntry(url, PNG_2X2, "image/png", 1280, 720);
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(entry));
            assertEquals(ReferenceException.Kind.IDENTITY_MISMATCH, failure.kind());
        }
    }

    @Test
    void rejectsOversizedPayload() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        byte[] oversized = new byte[(int) ReferenceImageStore.MAX_BYTES + 1];
        CorpusEntry entry = new CorpusEntry("ref", url, null, "MIT", "ref.xml", CORPUS_THRESHOLDS, 2, 2,
                sha256(oversized), ReferenceImageStore.MAX_BYTES, "image/png");
        FakeTransport transport = new FakeTransport(
                new ReferenceImageStore.Response(200, "image/png", "", oversized));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(entry));
            assertEquals(ReferenceException.Kind.IDENTITY_MISMATCH, failure.kind());
        }
    }

    @Test
    void rejectsUnexpectedStatus() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        FakeTransport transport = new FakeTransport(
                new ReferenceImageStore.Response(500, "text/html", "", new byte[0]));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(canonicalEntry(url)));
            assertEquals(ReferenceException.Kind.UNEXPECTED_STATUS, failure.kind());
        }
    }

    @Test
    void absentReference404IsEmpty() {
        String url = "https://" + ALLOWED_HOST + "/missing.png";
        FakeTransport transport = new FakeTransport(
                new ReferenceImageStore.Response(404, "text/html", "", new byte[0]));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            assertTrue(store.reference(canonicalEntry(url)).isEmpty(),
                    "only an explicitly absent reference (404/410) may be reported empty");
        }
    }

    // ---------------------------------------------------------------- decode bounds

    @Test
    void rejectsDecodeFailure() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        byte[] truncated = Arrays.copyOf(PNG_2X2, PNG_2X2.length - 5);
        CorpusEntry entry = verifiedEntry(url, truncated, "image/png", 2, 2);
        FakeTransport transport = new FakeTransport(
                new ReferenceImageStore.Response(200, "image/png", "", truncated));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(entry));
            assertEquals(ReferenceException.Kind.DECODE, failure.kind());
        }
    }

    @Test
    void rejectsHugeHeaderMismatchBomb() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        byte[] bomb = patchPngDimensions(PNG_2X2, 100_000, 100_000);
        CorpusEntry entry = verifiedEntry(url, bomb, "image/png", 2, 2);
        FakeTransport transport = new FakeTransport(
                new ReferenceImageStore.Response(200, "image/png", "", bomb));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(entry));
            assertEquals(ReferenceException.Kind.IDENTITY_MISMATCH, failure.kind(),
                    "a header declaring dimensions far beyond the declared identity must be "
                            + "rejected before any pixel allocation");
        }
    }

    @Test
    void rejectsInCapCompressedBombAtDecode() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        byte[] bomb = patchPngDimensions(PNG_2X2, 8192, 8192);
        CorpusEntry entry = verifiedEntry(url, bomb, "image/png", 8192, 8192);
        FakeTransport transport = new FakeTransport(
                new ReferenceImageStore.Response(200, "image/png", "", bomb));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceException failure = reject(() -> store.reference(entry));
            assertEquals(ReferenceException.Kind.DECODE, failure.kind(),
                    "an in-cap decompression bomb must fail at the subsampled decode without "
                            + "a full-resolution allocation");
        }
    }

    @Test
    void subsamplesLargeImagesToBoundedAnalysisResolution() throws IOException {
        byte[] large = pngOrFail(2048, 2048);
        BufferedImage decoded = BoundedDecode.decode(large);
        assertEquals(1024, decoded.getWidth(), "source 2048 wide must subsample by 2");
        assertEquals(1024, decoded.getHeight(), "source 2048 tall must subsample by 2");
        assertTrue(decoded.getWidth() <= BoundedDecode.MAX_ANALYSIS_DIMENSION);
        assertTrue(decoded.getHeight() <= BoundedDecode.MAX_ANALYSIS_DIMENSION);
    }

    @Test
    void keepsNativeResolutionAtOrBelowAnalysisCap() throws IOException {
        BufferedImage decoded = BoundedDecode.decode(PNG_2X2);
        assertEquals(2, decoded.getWidth());
        assertEquals(2, decoded.getHeight());
    }

    // ---------------------------------------------------------------- bounded in-memory cache

    @Test
    void cacheEvictsLeastRecentlyUsedAtEntryCap() {
        byte[] one = png(1, 1, 1);
        byte[] two = png(2, 2, 2);
        byte[] three = png(3, 3, 3);
        ReferenceImageStore.CacheLimits limits = new ReferenceImageStore.CacheLimits(2,
                Long.MAX_VALUE, Long.MAX_VALUE);
        FakeTransport transport = new FakeTransport(ok(one), ok(two), ok(three), ok(one));
        try (ReferenceImageStore store = store(transport, publicResolver(), limits,
                ALLOWED_HOST)) {
            store.reference(verifiedEntry("https://" + ALLOWED_HOST + "/one.png", one,
                    "image/png", 1, 1)).orElseThrow();
            store.reference(verifiedEntry("https://" + ALLOWED_HOST + "/two.png", two,
                    "image/png", 2, 2)).orElseThrow();
            store.reference(verifiedEntry("https://" + ALLOWED_HOST + "/three.png", three,
                    "image/png", 3, 3)).orElseThrow();
            assertEquals(2, store.cachedEntryCount(),
                    "at most maxEntries identities may be retained");
            // The least recently used identity was evicted: re-requesting it refetches.
            store.reference(verifiedEntry("https://" + ALLOWED_HOST + "/one.png", one,
                    "image/png", 1, 1)).orElseThrow();
            assertEquals(4, transport.requested().size(),
                    "an evicted identity must be refetched, never served stale");
            assertEquals(2, store.cachedEntryCount());
        }
    }

    @Test
    void cacheAdmissionTracksEncodedByteAndPixelTotals() {
        byte[] one = png(1, 1, 1);
        byte[] two = png(2, 2, 2);
        ReferenceImageStore.CacheLimits limits = new ReferenceImageStore.CacheLimits(8,
                Long.MAX_VALUE, Long.MAX_VALUE);
        FakeTransport transport = new FakeTransport(ok(one), ok(two));
        try (ReferenceImageStore store = store(transport, publicResolver(), limits,
                ALLOWED_HOST)) {
            store.reference(verifiedEntry("https://" + ALLOWED_HOST + "/one.png", one,
                    "image/png", 1, 1)).orElseThrow();
            store.reference(verifiedEntry("https://" + ALLOWED_HOST + "/two.png", two,
                    "image/png", 2, 2)).orElseThrow();
            assertEquals(one.length + two.length, store.cachedEncodedBytes(),
                    "the cumulative encoded-byte total must equal the admitted payloads");
            assertEquals(1L + 4L, store.cachedDecodedPixels(),
                    "the cumulative decoded-pixel total must equal the admitted pixels");
        }
    }

    @Test
    void cacheEvictsWhenEncodedByteBudgetExceeded() {
        byte[] one = png(1, 1, 1);
        byte[] two = png(2, 2, 2);
        byte[] three = png(3, 3, 3);
        // The budget fits exactly the two larger payloads; the third admission evicts the LRU.
        ReferenceImageStore.CacheLimits limits = new ReferenceImageStore.CacheLimits(8,
                two.length + three.length, Long.MAX_VALUE);
        FakeTransport transport = new FakeTransport(ok(one), ok(two), ok(three), ok(one));
        try (ReferenceImageStore store = store(transport, publicResolver(), limits,
                ALLOWED_HOST)) {
            store.reference(verifiedEntry("https://" + ALLOWED_HOST + "/one.png", one,
                    "image/png", 1, 1)).orElseThrow();
            store.reference(verifiedEntry("https://" + ALLOWED_HOST + "/two.png", two,
                    "image/png", 2, 2)).orElseThrow();
            store.reference(verifiedEntry("https://" + ALLOWED_HOST + "/three.png", three,
                    "image/png", 3, 3)).orElseThrow();
            assertEquals(2, store.cachedEntryCount());
            assertEquals(two.length + three.length, store.cachedEncodedBytes(),
                    "the cumulative encoded bytes must never exceed the budget");
            // The evicted (least recently used) identity is refetched on demand.
            store.reference(verifiedEntry("https://" + ALLOWED_HOST + "/one.png", one,
                    "image/png", 1, 1)).orElseThrow();
            assertEquals(4, transport.requested().size());
        }
    }

    @Test
    void cacheEvictsWhenDecodedPixelBudgetExceeded() {
        byte[] one = png(2, 2, 1);
        byte[] two = png(2, 2, 2);
        byte[] three = png(2, 2, 3);
        // Four pixels each; the budget holds exactly two decoded frames.
        ReferenceImageStore.CacheLimits limits = new ReferenceImageStore.CacheLimits(8,
                Long.MAX_VALUE, 8);
        FakeTransport transport = new FakeTransport(ok(one), ok(two), ok(three));
        try (ReferenceImageStore store = store(transport, publicResolver(), limits,
                ALLOWED_HOST)) {
            store.reference(verifiedEntry("https://" + ALLOWED_HOST + "/one.png", one,
                    "image/png", 2, 2)).orElseThrow();
            store.reference(verifiedEntry("https://" + ALLOWED_HOST + "/two.png", two,
                    "image/png", 2, 2)).orElseThrow();
            store.reference(verifiedEntry("https://" + ALLOWED_HOST + "/three.png", three,
                    "image/png", 2, 2)).orElseThrow();
            assertEquals(2, store.cachedEntryCount());
            assertEquals(8, store.cachedDecodedPixels(),
                    "the cumulative decoded pixels must never exceed the budget");
        }
    }

    @Test
    void singleEntryOverEveryBudgetIsServedButNotCached() {
        byte[] body = png(3, 3, 3);
        ReferenceImageStore.CacheLimits limits = new ReferenceImageStore.CacheLimits(8,
                body.length - 1, 8);
        FakeTransport transport = new FakeTransport(ok(body), ok(body));
        try (ReferenceImageStore store = store(transport, publicResolver(), limits,
                ALLOWED_HOST)) {
            CorpusEntry entry = verifiedEntry("https://" + ALLOWED_HOST + "/big.png", body,
                    "image/png", 3, 3);
            assertEquals(3, store.reference(entry).orElseThrow().width());
            assertEquals(0, store.cachedEntryCount(),
                    "an entry that alone exceeds a budget is served but never retained");
            // Without retention the second request cannot hit and must refetch.
            store.reference(entry).orElseThrow();
            assertEquals(2, transport.requested().size());
        }
    }

    @Test
    void exceedsBudgetIsOverflowSafe() {
        long max = Long.MAX_VALUE;
        assertFalse(ReferenceImageStore.exceedsBudget(0, 1, max));
        assertFalse(ReferenceImageStore.exceedsBudget(max, 0, max));
        assertFalse(ReferenceImageStore.exceedsBudget(max - 1, 1, max));
        assertTrue(ReferenceImageStore.exceedsBudget(max - 1, 2, max));
        assertFalse(ReferenceImageStore.exceedsBudget(0, max, max));
        assertTrue(ReferenceImageStore.exceedsBudget(1, max, max));
        assertFalse(ReferenceImageStore.exceedsBudget(0, max - 1, max));
        assertFalse(ReferenceImageStore.exceedsBudget(5, 10, 15));
        assertTrue(ReferenceImageStore.exceedsBudget(5, 11, 15));
    }

    @Test
    void concurrentSameReferenceFetchesExactlyOnce() throws Exception {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        int threads = 8;
        LatchedTransport transport = new LatchedTransport(ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            List<Thread> workers = new ArrayList<>();
            List<ReferenceImageStore.ReferenceImage> results =
                    Collections.synchronizedList(new ArrayList<>());
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < threads; i++) {
                workers.add(Thread.ofVirtual().start(() -> {
                    try {
                        results.add(store.reference(canonicalEntry(url)).orElseThrow());
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                }));
            }
            awaitAllParked(workers); // exactly one owner in the transport, the rest in join
            transport.release();
            for (Thread worker : workers) {
                worker.join(10_000);
            }
            assertTrue(failures.isEmpty(), "concurrent callers must not fail: " + failures);
            assertEquals(threads, results.size());
            for (ReferenceImageStore.ReferenceImage image : results) {
                assertSame(results.get(0), image,
                        "every concurrent caller must observe the same immutable image");
            }
            assertEquals(1, transport.requestCount(),
                    "concurrent same-identity calls must share exactly one fetch");
            assertEquals(1, store.cachedEntryCount());
        }
    }

    @Test
    void concurrentSameReference404IsShared() throws Exception {
        String url = "https://" + ALLOWED_HOST + "/missing.png";
        int threads = 4;
        LatchedTransport transport = new LatchedTransport(
                new ReferenceImageStore.Response(404, "text/html", "", new byte[0]));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            List<Thread> workers = new ArrayList<>();
            List<Boolean> empties = Collections.synchronizedList(new ArrayList<>());
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < threads; i++) {
                workers.add(Thread.ofVirtual().start(() -> {
                    try {
                        empties.add(store.reference(canonicalEntry(url)).isEmpty());
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                }));
            }
            awaitAllParked(workers);
            transport.release();
            for (Thread worker : workers) {
                worker.join(10_000);
            }
            assertTrue(failures.isEmpty(), "concurrent 404 callers must not fail: " + failures);
            assertEquals(threads, empties.size());
            for (boolean empty : empties) {
                assertTrue(empty, "every concurrent caller must observe the identical "
                        + "absent result");
            }
            assertEquals(1, transport.requestCount(),
                    "the shared absent result must be fetched exactly once");
        }
    }

    @Test
    void concurrentSameReferenceFailureIsShared() throws Exception {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        CorpusEntry forged = new CorpusEntry("ref", url, null, "MIT", "ref.xml", CORPUS_THRESHOLDS, 2, 2,
                "0".repeat(64), PNG_2X2.length, "image/png");
        int threads = 4;
        LatchedTransport transport = new LatchedTransport(ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            List<Thread> workers = new ArrayList<>();
            List<ReferenceException.Kind> kinds = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < threads; i++) {
                workers.add(Thread.ofVirtual().start(() -> {
                    try {
                        store.reference(forged);
                        kinds.add(null); // an unexpected success is a test failure
                    } catch (ReferenceException failure) {
                        kinds.add(failure.kind());
                    } catch (Throwable unexpected) {
                        kinds.add(ReferenceException.Kind.IO);
                    }
                }));
            }
            awaitAllParked(workers);
            transport.release();
            for (Thread worker : workers) {
                worker.join(10_000);
            }
            assertEquals(threads, kinds.size());
            for (ReferenceException.Kind kind : kinds) {
                assertEquals(ReferenceException.Kind.IDENTITY_MISMATCH, kind,
                        "every concurrent caller must observe the identical typed failure");
            }
            assertEquals(1, transport.requestCount(),
                    "the shared typed failure must be fetched exactly once");
        }
    }

    @Test
    void closeDuringInFlightFetchTerminatesWithClosedAndDrains() throws Exception {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        LatchedTransport latched = new LatchedTransport(ok(PNG_2X2));
        ReferenceImageStore store = store(latched, publicResolver(), ALLOWED_HOST);
        CorpusEntry entry = canonicalEntry(url);
        java.util.concurrent.atomic.AtomicReference<Throwable> ownerOutcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> waiterOutcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        workers.add(Thread.ofVirtual().start(() -> {
            try {
                if (store.reference(entry).isPresent()) {
                    ownerOutcome.set(new AssertionError("unexpected valid result after close"));
                } else {
                    ownerOutcome.set(new AssertionError("unexpected absent result after close"));
                }
            } catch (Throwable failure) {
                ownerOutcome.set(failure);
            }
        }));
        workers.add(Thread.ofVirtual().start(() -> {
            try {
                if (store.reference(entry).isPresent()) {
                    waiterOutcome.set(new AssertionError("unexpected valid result after close"));
                } else {
                    waiterOutcome.set(new AssertionError("unexpected absent result after close"));
                }
            } catch (Throwable failure) {
                waiterOutcome.set(failure);
            }
        }));
        awaitAllParked(workers); // owner in the transport, waiter joined the in-flight future
        store.close();
        for (Thread worker : workers) {
            worker.join(10_000);
        }
        assertClosedOutcome(ownerOutcome.get(), "the fetch owner");
        assertClosedOutcome(waiterOutcome.get(), "a waiter that joined the in-flight fetch");
        assertEquals(0, store.cachedEntryCount(),
                "close must admit nothing from the aborted fetch");
        assertThrows(IllegalStateException.class, () -> store.reference(entry),
                "no active work may remain after close returns");
        assertTrue(workers.stream().noneMatch(Thread::isAlive),
                "both threads must have ended before close returned");
    }

    /** Waits until every worker is parked (owner in the transport, waiters in the join). */
    private static void awaitAllParked(List<Thread> workers) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            long parked = 0;
            for (Thread worker : workers) {
                Thread.State state = worker.getState();
                if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                    parked++;
                }
            }
            if (parked == workers.size()) {
                return;
            }
            Thread.onSpinWait();
        }
        org.junit.jupiter.api.Assertions.fail("not all workers parked in the fetch before "
                + "release: " + workers.stream().map(Thread::getState).toList());
    }

    private static void assertClosedOutcome(Throwable outcome, String role) {
        assertTrue(outcome instanceof ReferenceException,
                role + " must end with a typed failure, got: " + outcome);
        assertEquals(ReferenceException.Kind.CLOSED,
                ((ReferenceException) outcome).kind(),
                role + " must end with CLOSED, never deliver a post-close result");
    }

    @Test
    void referenceImageIsImmutableAndFaithful() throws IOException {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceImageStore.ReferenceImage image =
                    store.reference(canonicalEntry(url)).orElseThrow();
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(PNG_2X2));
            assertEquals(source.getWidth(), image.width());
            assertEquals(source.getHeight(), image.height());
            int original = source.getRGB(0, 0);
            assertEquals(original, image.rgb(0, 0),
                    "the immutable pixel store must faithfully copy the decoded image");
            // A caller mutating its own decoded copy cannot affect the cached value.
            source.setRGB(0, 0, 0xFF0000);
            assertEquals(original, image.rgb(0, 0),
                    "a mutation on the caller's copy must never reach the immutable image");
            // The same immutable instance is served on later hits.
            assertSame(image, store.reference(canonicalEntry(url)).orElseThrow());
        }
    }

    @Test
    void storeCloseClearsCacheAndFailsFastOnUse() throws IOException {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST);
        store.reference(canonicalEntry(url)).orElseThrow();
        assertEquals(1, store.cachedEntryCount());
        store.close();
        assertTrue(transport.closed(), "close must abort the transport");
        assertEquals(0, store.cachedEntryCount(), "close must clear every owned reference");
        assertEquals(0, store.cachedEncodedBytes());
        assertEquals(0, store.cachedDecodedPixels());
        assertThrows(IllegalStateException.class, () -> store.reference(canonicalEntry(url)),
                "a closed store must fail fast instead of silently refetching");
        store.close(); // idempotent
    }

    @Test
    void imageReturnedBeforeCloseRemainsReadable() throws IOException {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST);
        ReferenceImageStore.ReferenceImage image =
                store.reference(canonicalEntry(url)).orElseThrow();
        store.close();
        assertEquals(2, image.width());
        assertEquals(2, image.height());
        assertEquals(0xFF000000, image.rgb(0, 0),
                "an immutable image handed out before close stays readable (detached)");
    }

    // ---------------------------------------------------------------- HTTP framing

    private static byte[] response(String statusAndHeaders, String body) {
        return (statusAndHeaders + "\r\n\r\n" + body).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static ReferenceImageStore.Response parse(String statusAndHeaders, String body)
            throws IOException {
        return ReferenceImageStore.parseResponse(
                new ByteArrayInputStream(response(statusAndHeaders, body)));
    }

    @Test
    void parsesContentLengthResponse() throws IOException {
        ReferenceImageStore.Response parsed = parse(
                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 5", "hello");
        assertEquals(200, parsed.statusCode());
        assertEquals("image/png", parsed.contentType());
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), parsed.body());
    }

    @Test
    void parsesChunkedResponseBody() throws IOException {
        byte[] raw = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\nX-Trailer: t\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        ReferenceImageStore.Response parsed =
                ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw));
        assertArrayEquals("hello world".getBytes(StandardCharsets.US_ASCII), parsed.body());
    }

    @Test
    void rejectsTransferEncodingWithContentLength() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 5\r\n\r\n"
                + "5\r\nhello\r\n0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsConflictingContentLengths() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\nhello")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsMalformedContentLength() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nContent-Length: banana\r\n\r\nhello")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsUnsupportedTransferEncodingChain() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsDuplicateTransferEncodingFields() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsOversizedChunkSizeLine() {
        String huge = "F".repeat(ReferenceImageStore.MAX_HEADER_LINE + 1);
        byte[] raw = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + huge
                + "\r\n0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsMalformedChunkSize() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n0\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsTruncatedChunkedBody() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhel")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsOversizedHeaderLine() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nX-Filler: "
                + "a".repeat(ReferenceImageStore.MAX_HEADER_LINE) + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void headerNamesAreCaseInsensitive() throws IOException {
        ReferenceImageStore.Response parsed = parse(
                "HTTP/1.1 200 OK\r\nCONTENT-TYPE: image/png\r\nLOCATION: /x.png", "");
        assertEquals("image/png", parsed.contentType());
        assertEquals("/x.png", parsed.location());
    }

    @Test
    void readsBodyToEofWithoutLength() throws IOException {
        ReferenceImageStore.Response parsed = parse(
                "HTTP/1.1 200 OK\r\nContent-Type: image/png", "payload");
        assertArrayEquals("payload".getBytes(StandardCharsets.US_ASCII), parsed.body());
    }

    @Test
    void rejectsContentLengthOverCap() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nContent-Length: " + (ReferenceImageStore.MAX_BYTES + 1)
                + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsMalformedStatusLine() {
        byte[] raw = "BOGUS\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    // ---------------------------------------------------------------- deadline

    @Test
    void deadlineInputStreamAbortsWhenBudgetExhausted() throws IOException {
        try (Socket socket = new Socket()) {
            ReferenceImageStore.Clock expired = () -> 2_000_000_000L;
            try (InputStream in = new ReferenceImageStore.DeadlineInputStream(
                    new ByteArrayInputStream(new byte[]{1, 2, 3}), socket, expired,
                    1_000_000_000L)) {
                IOException failure = assertThrows(IOException.class, in::read);
                assertTrue(failure.getMessage().contains("deadline"),
                        "an exhausted budget must abort the exchange before any byte is read");
            }
        }
    }

    @Test
    void deadlineInputStreamReadsWithinBudget() throws IOException {
        try (Socket socket = new Socket()) {
            ReferenceImageStore.Clock fixed = () -> 500_000_000L;
            try (InputStream in = new ReferenceImageStore.DeadlineInputStream(
                    new ByteArrayInputStream(new byte[]{42}), socket, fixed, 1_000_000_000L)) {
                assertEquals(42, in.read());
            }
        }
    }

    // ---------------------------------------------------------------- Content-Length grammar

    @Test
    void rejectsPlusSignContentLength() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nContent-Length: +5\r\n\r\nhello")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsNegativeContentLength() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\nhello")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsTrailingSpaceContentLength() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nContent-Length: 5 \r\n\r\nhello")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsHugeContentLength() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nContent-Length: "
                + "9".repeat(30) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void acceptsContentLengthWithoutDelimiterSpace() throws IOException {
        ReferenceImageStore.Response parsed = parse(
                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length:5", "hello");
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), parsed.body());
    }

    // ---------------------------------------------------------------- handshake deadline

    @Test
    void handshakeWatchdogEnforcesAbsoluteDeadline() throws Exception {
        try (Socket socket = new Socket()) {
            ReferenceImageStore.Clock frozen = () -> 0L;
            long deadline = 1_000_000L; // 1 ms of budget: the watchdog fires almost immediately
            CountDownLatch watchdogObserved = new CountDownLatch(1);
            ReferenceImageStore.Handshake trickle = () -> {
                while (!socket.isClosed()) {
                    Thread.onSpinWait();
                }
                watchdogObserved.countDown();
                throw new IOException("socket closed by the deadline watchdog");
            };
            IOException failure = assertThrows(IOException.class, () ->
                    ReferenceImageStore.runHandshakeUnderDeadline(trickle, socket, frozen,
                            deadline));
            assertTrue(failure.getMessage().contains("socket closed"),
                    "a trickling handshake must observe the watchdog-closed socket");
            assertTrue(watchdogObserved.await(5, TimeUnit.SECONDS),
                    "the watchdog must fire at the absolute deadline");
        }
    }

    @Test
    void handshakeCompletesWithinDeadline() throws Exception {
        try (Socket socket = new Socket()) {
            ReferenceImageStore.Clock frozen = () -> 0L;
            boolean[] ran = {false};
            ReferenceImageStore.Handshake fast = () -> ran[0] = true;
            ReferenceImageStore.runHandshakeUnderDeadline(fast, socket, frozen,
                    1_000_000_000L);
            assertTrue(ran[0], "a fast handshake must complete inside the deadline");
        }
    }

    // ---------------------------------------------------------------- chunked trailers

    @Test
    void acceptsChunkedTrailersAtBound() throws IOException {
        String line = "X-1: " + "a".repeat(4096) + "\r\n";
        byte[] raw = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n"
                + line.repeat(15) + "\r\n").getBytes(StandardCharsets.US_ASCII);
        ReferenceImageStore.Response parsed =
                ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw));
        assertArrayEquals(new byte[0], parsed.body(),
                "trailers exactly at the cap plus the terminating line must parse");
    }

    @Test
    void rejectsChunkedTrailersOverBound() {
        String line = "X-1: " + "a".repeat(4096) + "\r\n";
        byte[] raw = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n"
                + line.repeat(16) + "\r\n").getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    // ---------------------------------------------------------------- strict line reader

    @Test
    void rejectsBareLfLineTerminator() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nX-F: a\nX-G: b\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsBareCrInsideLine() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nX-F: a\rb\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsBareCrAtEndOfLine() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nX-F: a\rX-G: b\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsControlOctetInHeaderLine() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nX-F: a\u0001b\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsBareLfStatusLine() {
        byte[] raw = "HTTP/1.1 200 OK\n\r\n".getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsBareCrStatusLine() {
        byte[] raw = "HTTP/1.1 200 OK\rX\r\n".getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void acceptsHeaderLineAtExactCap() throws IOException {
        String filler = "X: " + "a".repeat(ReferenceImageStore.MAX_HEADER_LINE - 5) + "\r\n";
        ReferenceImageStore.Response parsed = parse(
                "HTTP/1.1 200 OK\r\n" + filler + "Content-Length: 2", "hi");
        assertArrayEquals("hi".getBytes(StandardCharsets.US_ASCII), parsed.body(),
                "a header line of exactly the cap raw octets (CRLF included) must parse");
    }

    @Test
    void rejectsHeaderLineOverCapByOne() {
        String filler = "X: " + "a".repeat(ReferenceImageStore.MAX_HEADER_LINE - 4) + "\r\n";
        byte[] raw = ("HTTP/1.1 200 OK\r\n" + filler + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void acceptsHeadersAtExactTotalCap() throws IOException {
        int target = ReferenceImageStore.MAX_HEADER_BYTES;
        String status = "HTTP/1.1 200 OK\r\n"; // 17 raw octets
        String blank = "\r\n"; // the header terminator, 2 raw octets
        String contentLength = "Content-Length: 2\r\n"; // 19 raw octets
        int fillerBudget = target - status.length() - blank.length() - contentLength.length();
        int lineRaw = 2005; // "X: " + 2000 a's + CRLF
        int lines = fillerBudget / lineRaw;
        int remainder = fillerBudget - lines * lineRaw;
        StringBuilder builder = new StringBuilder(status);
        for (int i = 0; i < lines; i++) {
            builder.append("X: ").append("a".repeat(lineRaw - 5)).append("\r\n");
        }
        builder.append("X: ").append("a".repeat(remainder - 5)).append("\r\n");
        builder.append(contentLength).append(blank).append("hi");
        ReferenceImageStore.Response parsed = ReferenceImageStore.parseResponse(
                new ByteArrayInputStream(builder.toString().getBytes(StandardCharsets.ISO_8859_1)));
        assertArrayEquals("hi".getBytes(StandardCharsets.US_ASCII), parsed.body(),
                "headers totaling exactly the cap raw octets (CRLF included) must parse");
    }

    @Test
    void rejectsHeadersOverTotalCapByOne() {
        int target = ReferenceImageStore.MAX_HEADER_BYTES;
        String status = "HTTP/1.1 200 OK\r\n";
        String blank = "\r\n";
        String contentLength = "Content-Length: 2\r\n";
        int fillerBudget = target - status.length() - blank.length() - contentLength.length();
        int lineRaw = 2005;
        int lines = fillerBudget / lineRaw;
        int remainder = fillerBudget - lines * lineRaw;
        StringBuilder builder = new StringBuilder(status);
        for (int i = 0; i < lines; i++) {
            builder.append("X: ").append("a".repeat(lineRaw - 5)).append("\r\n");
        }
        builder.append("X: ").append("a".repeat(remainder - 4)).append("\r\n"); // one octet over
        builder.append(contentLength).append(blank).append("hi");
        assertThrows(IOException.class, () -> ReferenceImageStore.parseResponse(
                new ByteArrayInputStream(builder.toString().getBytes(StandardCharsets.ISO_8859_1))));
    }

    @Test
    void crlfOctetsCountTowardTotalHeaderCap() {
        // Old accounting stripped CRs and counted one octet per line terminator; the new
        // accounting counts every raw octet including CRLF. 16000 three-octet lines total
        // 80000 raw octets (> cap) but only 64017 non-CR octets (< cap), so only raw counting
        // rejects this response.
        byte[] raw = ("HTTP/1.1 200 OK\r\n" + "abc\r\n".repeat(16000) + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsBareLfChunkTerminator() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\n0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void rejectsBareCrChunkTerminator() {
        byte[] raw = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\rX\r\n0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> ReferenceImageStore.parseResponse(new ByteArrayInputStream(raw)));
    }

    @Test
    void evictionIsLeastRecentlyUsedAcrossIdentities() throws IOException {
        byte[] one = png(1, 1, 1);
        byte[] two = png(2, 2, 2);
        byte[] three = png(3, 3, 3);
        ReferenceImageStore.CacheLimits limits = new ReferenceImageStore.CacheLimits(2,
                Long.MAX_VALUE, Long.MAX_VALUE);
        // Scripted: one, two, hit(one), three, refetch(two), hit(one).
        FakeTransport transport = new FakeTransport(ok(one), ok(two), ok(three), ok(two));
        try (ReferenceImageStore store = store(transport, publicResolver(), limits,
                ALLOWED_HOST)) {
            CorpusEntry entryOne = verifiedEntry("https://" + ALLOWED_HOST + "/one.png", one,
                    "image/png", 1, 1);
            CorpusEntry entryTwo = verifiedEntry("https://" + ALLOWED_HOST + "/two.png", two,
                    "image/png", 2, 2);
            CorpusEntry entryThree = verifiedEntry("https://" + ALLOWED_HOST + "/three.png",
                    three, "image/png", 3, 3);
            store.reference(entryOne).orElseThrow();
            store.reference(entryTwo).orElseThrow();
            store.reference(entryOne).orElseThrow(); // access-order touch: one is now MRU
            store.reference(entryThree).orElseThrow(); // evicts two (the LRU), keeps one
            assertEquals(3, transport.requested().size());
            assertEquals(2, store.cachedEntryCount());
            // one is still cached: no new request.
            assertEquals(1, store.reference(entryOne).orElseThrow().width());
            assertEquals(3, transport.requested().size(),
                    "the re-touched entry must be served from the cache after the eviction");
            // two was evicted: requesting it refetches.
            assertEquals(2, store.reference(entryTwo).orElseThrow().width());
            assertEquals(4, transport.requested().size(),
                    "the evicted entry must be refetched on demand");
        }
    }
}
