package dev.gdx.markup.qualification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Authenticated remote reference pipeline. The store refuses non-https, user-info, fragment,
 * wrong-host, and non-443 targets, pins every fetch to freshly approved globally-routable
 * resolved addresses (never re-resolving), validates each redirect hop against the same policy,
 * and verifies digest, media type, length, and header dimensions on both download and cache hit
 * through single NOFOLLOW handles. Policy, identity, cache, and decode failures raise typed
 * {@link ReferenceException}s; empty is reserved for explicitly absent references (404/410).
 * Transport and DNS are injected, so the suite never touches the network.
 */
final class ReferenceImageStoreTest {
    private static final String ALLOWED_HOST = "shared.akamai.steamstatic.com";
    private static final InetAddress PUBLIC_ADDRESS = ipv4(93, 184, 216, 34);

    /** Deterministic 2x2 PNG used as the canonical verified payload. */
    private static final byte[] PNG_2X2 = pngOrFail(2, 2);
    private static final String PNG_2X2_SHA256 = sha256(PNG_2X2);

    @TempDir Path tempDir;

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

    /** A remote entry whose declared identity exactly matches {@code body}. */
    private static CorpusEntry verifiedEntry(String url, byte[] body, String mediaType,
            int width, int height) {
        return new CorpusEntry("ref", url, null, "MIT", "ref.xml", 0.2, width, height,
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
        return store(tempDir.resolve("cache"), transport, resolver, allowedHosts);
    }

    private ReferenceImageStore store(Path root, ReferenceImageStore.Transport transport,
            FakeResolver resolver, String... allowedHosts) {
        return new ReferenceImageStore(root, transport, resolver, Set.of(allowedHosts));
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

    private static final class FakeTransport implements ReferenceImageStore.Transport {
        private final List<URI> requested = new ArrayList<>();
        private final List<List<InetAddress>> approved = new ArrayList<>();
        private final Deque<ReferenceImageStore.Response> script = new ArrayDeque<>();

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

        @Override
        public ReferenceImageStore.Response get(URI uri, List<InetAddress> pinned) {
            requested.add(uri);
            approved.add(List.copyOf(pinned));
            ReferenceImageStore.Response response = script.pollFirst();
            if (response == null) {
                throw new AssertionError("unexpected request to " + uri);
            }
            return response;
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

    // ---------------------------------------------------------------- happy path

    @Test
    void fetchesVerifiedReferenceIntoCache() throws IOException {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceImageStore.ReferenceImage image =
                    store.reference(canonicalEntry(url)).orElseThrow();
            assertEquals(2, image.image().getWidth());
            assertEquals(2, image.image().getHeight());
            Path cached = store.sessionDir().resolve("ref.png");
            assertTrue(Files.isRegularFile(cached));
            assertArrayEquals(PNG_2X2, Files.readAllBytes(cached),
                    "the session cache must hold exactly the verified bytes");
            assertEquals(1, transport.requested().size());
            assertEquals(List.of(PUBLIC_ADDRESS), transport.approved(0),
                    "the fetch must be pinned to the approved address");
        }
    }

    @Test
    void followsBoundedRedirectToVerifiedBody() {
        String url = "https://" + ALLOWED_HOST + "/start.png";
        FakeTransport transport = new FakeTransport(redirect("/final.png"), ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            ReferenceImageStore.ReferenceImage image =
                    store.reference(canonicalEntry(url)).orElseThrow();
            assertEquals(2, image.image().getWidth());
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
        CorpusEntry forged = new CorpusEntry("ref", url, null, "MIT", "ref.xml", 0.2, 2, 2,
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
        CorpusEntry entry = new CorpusEntry("ref", url, null, "MIT", "ref.xml", 0.2, 2, 2,
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
        CorpusEntry entry = new CorpusEntry("ref", url, null, "MIT", "ref.xml", 0.2, 2, 2,
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
        CorpusEntry entry = new CorpusEntry("ref", url, null, "MIT", "ref.xml", 0.2, 2, 2,
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

    // ---------------------------------------------------------------- session cache

    @Test
    void sessionCacheDirIsOwnerOnly() throws IOException {
        try (ReferenceImageStore store = store(silentTransport(), publicResolver(), ALLOWED_HOST)) {
            try {
                Set<PosixFilePermission> permissions =
                        Files.getPosixFilePermissions(store.sessionDir());
                assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE), permissions,
                        "the private session cache must be owner-only");
            } catch (UnsupportedOperationException unsupported) {
                org.junit.jupiter.api.Assumptions.abort(
                        "POSIX permissions unavailable: " + unsupported);
            }
        }
    }

    @Test
    void sessionCacheDirIsFreshlyCreatedPerStore() {
        ReferenceImageStore first = store(silentTransport(), publicResolver(), ALLOWED_HOST);
        ReferenceImageStore second = store(silentTransport(), publicResolver(), ALLOWED_HOST);
        try (first; second) {
            assertNotEquals(first.sessionDir(), second.sessionDir(),
                    "each store must own an unpredictable, freshly created session dir");
            assertEquals(first.sessionDir().getParent(), second.sessionDir().getParent());
        }
    }

    @Test
    void validCacheHitSkipsNetwork() throws IOException {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            Files.write(store.sessionDir().resolve("ref.png"), PNG_2X2);
            ReferenceImageStore.ReferenceImage image = store.reference(
                    canonicalEntry("https://" + ALLOWED_HOST + "/ref.png")).orElseThrow();
            assertEquals(2, image.image().getWidth());
            assertTrue(transport.requested().isEmpty(),
                    "a verified cache hit must not touch the network");
        }
    }

    @Test
    void forgedCacheHitWithWrongLengthFailsLoudly() throws IOException {
        try (ReferenceImageStore store = store(silentTransport(), publicResolver(), ALLOWED_HOST)) {
            Files.write(store.sessionDir().resolve("ref.png"), new byte[]{0, 1, 2, 3});
            ReferenceException failure = reject(() -> store.reference(
                    canonicalEntry("https://" + ALLOWED_HOST + "/ref.png")));
            assertEquals(ReferenceException.Kind.CACHE, failure.kind(),
                    "a forged cache file must fail the qualification, never be used");
        }
    }

    @Test
    void forgedCacheHitWithWrongDigestFailsLoudly() throws IOException {
        try (ReferenceImageStore store = store(silentTransport(), publicResolver(), ALLOWED_HOST)) {
            byte[] forged = Arrays.copyOf(PNG_2X2, PNG_2X2.length);
            forged[forged.length - 1] ^= 0x01;
            Files.write(store.sessionDir().resolve("ref.png"), forged);
            ReferenceException failure = reject(() -> store.reference(
                    canonicalEntry("https://" + ALLOWED_HOST + "/ref.png")));
            assertEquals(ReferenceException.Kind.CACHE, failure.kind(),
                    "a same-length forged cache file must fail the digest check");
        }
    }

    @Test
    void plantedCacheSymlinkFailsLoudly() throws IOException {
        Path outside = tempDir.resolve("outside.bin");
        Files.writeString(outside, "sentinel");
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store = store(transport, publicResolver(), ALLOWED_HOST)) {
            createSymlinkOrAbort(store.sessionDir().resolve("ref.png"), outside);
            ReferenceException failure = reject(() -> store.reference(
                    canonicalEntry("https://" + ALLOWED_HOST + "/ref.png")));
            assertEquals(ReferenceException.Kind.CACHE, failure.kind(),
                    "a symlink planted at the cache path must never be followed");
            assertEquals("sentinel", Files.readString(outside),
                    "nothing may be written through the planted symlink");
        }
    }

    private static void createSymlinkOrAbort(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable: " + unavailable);
        }
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

    // ---------------------------------------------------------------- session root and cleanup

    @Test
    void sessionFallsBackToOsTempWhenConfiguredRootIsSymlink() throws IOException {
        Path symlinkRoot = tempDir.resolve("cache-symlink");
        Path target = Files.createDirectories(tempDir.resolve("cache-target"));
        createSymlinkOrAbort(symlinkRoot, target);
        try (ReferenceImageStore store =
                store(symlinkRoot, silentTransport(), publicResolver(), ALLOWED_HOST)) {
            Path osTemp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            assertTrue(store.sessionDir().toRealPath().startsWith(osTemp),
                    "a symlinked configured root must not host the session");
            assertFalse(store.sessionDir().toRealPath().startsWith(target.toRealPath()),
                    "the session must never be created through the planted symlink");
        }
    }

    @Test
    void sessionFallsBackToOsTempWhenConfiguredRootIsFile() throws IOException {
        Path rootFile = tempDir.resolve("cache-file");
        Files.writeString(rootFile, "not a directory");
        try (ReferenceImageStore store =
                store(rootFile, silentTransport(), publicResolver(), ALLOWED_HOST)) {
            Path osTemp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            assertTrue(store.sessionDir().toRealPath().startsWith(osTemp),
                    "a non-directory configured root must not host the session");
        }
    }

    @Test
    void closeDeletesSessionRecursively() throws IOException {
        Path session;
        try (ReferenceImageStore store =
                store(silentTransport(), publicResolver(), ALLOWED_HOST)) {
            session = store.sessionDir();
            Files.writeString(session.resolve("ref.png"), "x");
            Files.createDirectories(session.resolve("sub"));
            Files.writeString(session.resolve("sub").resolve("other.bin"), "y");
            assertTrue(Files.exists(session));
        }
        assertFalse(Files.exists(session), "close must delete the whole session tree");
    }

    @Test
    void closeAggregatesCleanupFailures() throws IOException {
        ReferenceImageStore store = store(silentTransport(), publicResolver(), ALLOWED_HOST);
        Path session = store.sessionDir();
        Files.writeString(session.resolve("ref.png"), "x");
        try {
            try {
                // 0500: the owner can read and list but cannot delete the children.
                Files.setPosixFilePermissions(session, Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException unsupported) {
                org.junit.jupiter.api.Assumptions.abort(
                        "POSIX permissions unavailable: " + unsupported);
            }
            ReferenceException failure = assertThrows(ReferenceException.class, store::close);
            assertEquals(ReferenceException.Kind.IO, failure.kind());
        } finally {
            Files.setPosixFilePermissions(session, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            try (var paths = Files.walk(session)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
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
            java.util.concurrent.CountDownLatch watchdogObserved =
                    new java.util.concurrent.CountDownLatch(1);
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
            assertTrue(watchdogObserved.await(5, java.util.concurrent.TimeUnit.SECONDS),
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

    // ---------------------------------------------------------------- secure parent policy

    private static void setMode(Path path, Set<PosixFilePermission> permissions, int sticky) {
        try {
            Files.setPosixFilePermissions(path, permissions);
            if (sticky != 0) {
                int mode = (Integer) Files.getAttribute(path, "unix:mode");
                Files.setAttribute(path, "unix:mode", mode | sticky);
            }
        } catch (UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("POSIX attributes unavailable: " + unsupported);
        } catch (IOException failure) {
            throw new AssertionError("cannot set POSIX mode on " + path, failure);
        }
    }

    private static Set<PosixFilePermission> allPermissions() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE);
    }

    @Test
    void secureParentPolicySeam() throws IOException {
        Path privateDir = Files.createDirectories(tempDir.resolve("private"));
        setMode(privateDir, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE), 0);
        Path sharedSticky = Files.createDirectories(tempDir.resolve("shared-sticky"));
        setMode(sharedSticky, allPermissions(), 01000);
        Path worldWritable = Files.createDirectories(tempDir.resolve("world-writable"));
        setMode(worldWritable, allPermissions(), 0);

        assertTrue(ReferenceImageStore.isSecureParent(privateDir),
                "a private user-owned directory is provably secure");
        assertTrue(ReferenceImageStore.isSecureParent(sharedSticky),
                "a shared temp with the sticky bit is provably secure");
        assertFalse(ReferenceImageStore.isSecureParent(worldWritable),
                "a world-writable non-sticky directory cannot be proven secure");
    }

    @Test
    void attackerWritableConfiguredRootIsNotUsed() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("public-cache"));
        setMode(root, allPermissions(), 0);
        try (ReferenceImageStore store =
                store(root, silentTransport(), publicResolver(), ALLOWED_HOST)) {
            assertFalse(store.sessionDir().toRealPath().startsWith(root.toRealPath()),
                    "an attacker-writable configured root must not host the session");
        }
    }

    @Test
    void intermediateSymlinkInConfiguredRootIsRejected() throws IOException {
        Path elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));
        Path link = tempDir.resolve("link");
        createSymlinkOrAbort(link, elsewhere);
        Path root = Files.createDirectories(link.resolve("sub"));
        try (ReferenceImageStore store =
                store(root, silentTransport(), publicResolver(), ALLOWED_HOST)) {
            assertFalse(store.sessionDir().toRealPath().startsWith(root.toRealPath()),
                    "a symlinked intermediate must make the configured root untrusted");
        }
    }

    @Test
    void secureParentFailsClosedWhenFallbackIsUnsafe() throws IOException {
        Path unsafeRoot = Files.createDirectories(tempDir.resolve("unsafe-root"));
        setMode(unsafeRoot, allPermissions(), 0);
        Path unsafeTemp = Files.createDirectories(tempDir.resolve("unsafe-temp"));
        setMode(unsafeTemp, allPermissions(), 0);
        ReferenceException failure = assertThrows(ReferenceException.class,
                () -> ReferenceImageStore.secureParent(unsafeRoot, unsafeTemp));
        assertEquals(ReferenceException.Kind.IO, failure.kind(),
                "when both the configured root and the fallback are unprovable, the store "
                        + "must fail closed");
    }

    @Test
    void secureParentUsesValidatedFallback() throws IOException {
        Path unsafeRoot = Files.createDirectories(tempDir.resolve("unsafe-fallback-root"));
        setMode(unsafeRoot, allPermissions(), 0);
        Path privateTemp = Files.createDirectories(tempDir.resolve("private-temp"));
        setMode(privateTemp, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE), 0);
        assertEquals(privateTemp, ReferenceImageStore.secureParent(unsafeRoot, privateTemp),
                "a validated fallback parent may host the session");
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
}
