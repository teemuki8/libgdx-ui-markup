package dev.gdx.markup.qualification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Authenticated remote reference pipeline. The store refuses non-https, user-info, fragment,
 * and disallowed-host targets, validates every redirect hop against the same policy plus
 * resolved address classes (private/loopback/link-local), and verifies digest, media type,
 * length, and dimensions on both download and cache hit. Transport and DNS are injected, so
 * the suite never touches the network.
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
            ReferenceImageStore.HostResolver resolver, String... allowedHosts) {
        return new ReferenceImageStore(tempDir.resolve("cache"), transport, resolver,
                Set.of(allowedHosts));
    }

    private FakeResolver resolver(String host, InetAddress... addresses) {
        return new FakeResolver().with(host, addresses);
    }

    private Path cacheFile() {
        return tempDir.resolve("cache").resolve("ref.png");
    }

    /** Serves nothing: any request is an unexpected network touch and fails the test. */
    private static FakeTransport silentTransport() {
        return new FakeTransport();
    }

    private static final class FakeTransport implements ReferenceImageStore.Transport {
        private final List<URI> requested = new ArrayList<>();
        private final Deque<ReferenceImageStore.Response> script = new ArrayDeque<>();

        FakeTransport(ReferenceImageStore.Response... responses) {
            for (ReferenceImageStore.Response response : responses) {
                script.add(response);
            }
        }

        List<URI> requested() {
            return requested;
        }

        @Override
        public ReferenceImageStore.Response get(URI uri) {
            requested.add(uri);
            ReferenceImageStore.Response response = script.pollFirst();
            if (response == null) {
                throw new AssertionError("unexpected request to " + uri);
            }
            return response;
        }
    }

    private static final class FakeResolver implements ReferenceImageStore.HostResolver {
        private final Map<String, InetAddress[]> byHost = new java.util.HashMap<>();

        FakeResolver with(String host, InetAddress... addresses) {
            byHost.put(host, addresses);
            return this;
        }

        @Override
        public InetAddress[] resolve(String host) {
            InetAddress[] addresses = byHost.get(host);
            if (addresses == null) {
                throw new AssertionError("unexpected DNS lookup for " + host);
            }
            return addresses;
        }
    }

    // ---------------------------------------------------------------- happy path

    @Test
    void fetchesVerifiedReferenceIntoCache() throws IOException {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            Path reference = store.reference(canonicalEntry(url)).orElseThrow();
            assertEquals(cacheFile(), reference);
            assertArrayEquals(PNG_2X2, Files.readAllBytes(reference),
                    "the cache must hold exactly the verified bytes");
            assertEquals(1, transport.requested().size());
        }
    }

    @Test
    void followsBoundedRedirectToVerifiedBody() throws IOException {
        String url = "https://" + ALLOWED_HOST + "/start.png";
        FakeTransport transport = new FakeTransport(redirect("/final.png"), ok(PNG_2X2));
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            Path reference = store.reference(canonicalEntry(url)).orElseThrow();
            assertArrayEquals(PNG_2X2, Files.readAllBytes(reference));
            assertEquals(2, transport.requested().size());
            assertEquals(URI.create("https://" + ALLOWED_HOST + "/final.png"),
                    transport.requested().get(1),
                    "the relative redirect must be resolved against the request target");
        }
    }

    @Test
    void validCacheHitSkipsNetwork() throws IOException {
        Files.createDirectories(tempDir.resolve("cache"));
        Files.write(cacheFile(), PNG_2X2);
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            Path reference = store.reference(canonicalEntry("https://" + ALLOWED_HOST + "/ref.png"))
                    .orElseThrow();
            assertEquals(cacheFile(), reference);
            assertTrue(transport.requested().isEmpty(),
                    "a verified cache hit must not touch the network");
        }
    }

    // ---------------------------------------------------------------- target shape

    @Test
    void rejectsHttpSchemeUrl() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            assertTrue(store.reference(canonicalEntry("http://" + ALLOWED_HOST + "/ref.png"))
                    .isEmpty());
            assertTrue(transport.requested().isEmpty(),
                    "no request may be issued for a non-https target");
        }
    }

    @Test
    void rejectsUrlWithUserInfo() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            assertTrue(store.reference(canonicalEntry(
                    "https://attacker@" + ALLOWED_HOST + "/ref.png")).isEmpty());
            assertTrue(transport.requested().isEmpty());
        }
    }

    @Test
    void rejectsUrlWithFragment() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            assertTrue(store.reference(canonicalEntry(
                    "https://" + ALLOWED_HOST + "/ref.png#fragment")).isEmpty());
            assertTrue(transport.requested().isEmpty());
        }
    }

    @Test
    void rejectsWrongHost() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            assertTrue(store.reference(canonicalEntry("https://example.com/ref.png")).isEmpty());
            assertTrue(transport.requested().isEmpty(),
                    "a host outside the allowlist must be refused before any request");
        }
    }

    // ---------------------------------------------------------------- redirects

    @Test
    void rejectsRedirectToDisallowedHost() {
        String url = "https://" + ALLOWED_HOST + "/start.png";
        FakeTransport transport = new FakeTransport(redirect("https://evil.example.com/steal.png"));
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            assertTrue(store.reference(canonicalEntry(url)).isEmpty());
            assertEquals(1, transport.requested().size(),
                    "the redirect target must be validated before the follow-up request");
        }
    }

    @Test
    void rejectsRedirectToHttpScheme() {
        String url = "https://" + ALLOWED_HOST + "/start.png";
        FakeTransport transport = new FakeTransport(redirect("http://evil.example.com/steal.png"));
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            assertTrue(store.reference(canonicalEntry(url)).isEmpty());
            assertEquals(1, transport.requested().size());
        }
    }

    @Test
    void rejectsRedirectToLoopbackResolvedAddress() {
        String url = "https://" + ALLOWED_HOST + "/start.png";
        FakeTransport transport = new FakeTransport(redirect("https://host2/private.png"));
        try (ReferenceImageStore store = store(transport,
                resolver(ALLOWED_HOST, PUBLIC_ADDRESS).with("host2", ipv4(127, 0, 0, 1)),
                ALLOWED_HOST, "host2")) {
            assertTrue(store.reference(canonicalEntry(url)).isEmpty());
            assertEquals(1, transport.requested().size(),
                    "a redirect resolving to a loopback address must never be fetched");
        }
    }

    @Test
    void rejectsRedirectWithoutLocation() {
        String url = "https://" + ALLOWED_HOST + "/start.png";
        FakeTransport transport = new FakeTransport(
                new ReferenceImageStore.Response(302, "", "", new byte[0]));
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            assertTrue(store.reference(canonicalEntry(url)).isEmpty());
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
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            assertTrue(store.reference(canonicalEntry(url)).isEmpty());
            assertEquals(ReferenceImageStore.MAX_REDIRECTS + 1, transport.requested().size(),
                    "at most one more request than the redirect cap may be issued");
        }
    }

    // ---------------------------------------------------------------- address policy

    @Test
    void rejectsPrivateResolvedAddress() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, ipv4(10, 0, 0, 1)), ALLOWED_HOST)) {
            assertTrue(store.reference(
                    canonicalEntry("https://" + ALLOWED_HOST + "/ref.png")).isEmpty());
            assertTrue(transport.requested().isEmpty());
        }
    }

    @Test
    void rejectsLoopbackResolvedAddress() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, ipv4(127, 0, 0, 1)), ALLOWED_HOST)) {
            assertTrue(store.reference(
                    canonicalEntry("https://" + ALLOWED_HOST + "/ref.png")).isEmpty());
            assertTrue(transport.requested().isEmpty());
        }
    }

    @Test
    void rejectsLinkLocalResolvedAddress() {
        FakeTransport transport = silentTransport();
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, ipv4(169, 254, 1, 1)), ALLOWED_HOST)) {
            assertTrue(store.reference(
                    canonicalEntry("https://" + ALLOWED_HOST + "/ref.png")).isEmpty());
            assertTrue(transport.requested().isEmpty());
        }
    }

    // ---------------------------------------------------------------- payload identity

    @Test
    void rejectsWrongDigest() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        CorpusEntry entry = verifiedEntry(url, PNG_2X2, "image/png", 2, 2);
        CorpusEntry forged = new CorpusEntry(entry.id(), entry.sourceUrl(), null,
                entry.license(), entry.markupFile(), entry.threshold(), 2, 2,
                "0".repeat(64), entry.bytes(), entry.mediaType());
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            assertTrue(store.reference(forged).isEmpty(),
                    "a payload whose SHA-256 differs from the declared identity must be refused");
        }
    }

    @Test
    void rejectsWrongMediaType() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        CorpusEntry entry = new CorpusEntry("ref", url, null, "MIT", "ref.xml", 0.2, 2, 2,
                PNG_2X2_SHA256, PNG_2X2.length, "image/jpeg");
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            assertTrue(store.reference(entry).isEmpty(),
                    "a server media type that differs from the declared identity must be refused");
        }
    }

    @Test
    void rejectsMissingContentType() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        FakeTransport transport = new FakeTransport(
                new ReferenceImageStore.Response(200, "", "", PNG_2X2));
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            assertTrue(store.reference(canonicalEntry(url)).isEmpty());
        }
    }

    @Test
    void rejectsWrongLength() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        CorpusEntry entry = new CorpusEntry("ref", url, null, "MIT", "ref.xml", 0.2, 2, 2,
                PNG_2X2_SHA256, PNG_2X2.length + 1, "image/png");
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            assertTrue(store.reference(entry).isEmpty(),
                    "a payload whose byte count differs from the declared identity must be refused");
        }
    }

    @Test
    void rejectsWrongDimensions() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        CorpusEntry entry = verifiedEntry(url, PNG_2X2, "image/png", 1280, 720);
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            assertTrue(store.reference(entry).isEmpty(),
                    "a payload whose decoded dimensions differ from the declared reference "
                            + "must be refused");
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
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            assertTrue(store.reference(entry).isEmpty(),
                    "a payload over the byte cap must be refused");
        }
    }

    @Test
    void rejectsNon200Status() {
        String url = "https://" + ALLOWED_HOST + "/ref.png";
        FakeTransport transport = new FakeTransport(
                new ReferenceImageStore.Response(404, "text/html", "", new byte[0]));
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            assertTrue(store.reference(canonicalEntry(url)).isEmpty());
        }
    }

    // ---------------------------------------------------------------- forged cache

    @Test
    void forgedCacheHitWithWrongLengthIsRefetchedAndRepaired() throws IOException {
        Files.createDirectories(tempDir.resolve("cache"));
        Files.write(cacheFile(), new byte[]{0, 1, 2, 3});
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            Path reference = store.reference(
                    canonicalEntry("https://" + ALLOWED_HOST + "/ref.png")).orElseThrow();
            assertEquals(cacheFile(), reference);
            assertArrayEquals(PNG_2X2, Files.readAllBytes(reference),
                    "a forged cache file must be replaced by the verified bytes");
            assertEquals(1, transport.requested().size());
        }
    }

    @Test
    void forgedCacheHitWithWrongDigestIsRefetchedAndRepaired() throws IOException {
        Files.createDirectories(tempDir.resolve("cache"));
        byte[] forged = Arrays.copyOf(PNG_2X2, PNG_2X2.length);
        forged[forged.length - 1] ^= 0x01;
        Files.write(cacheFile(), forged);
        FakeTransport transport = new FakeTransport(ok(PNG_2X2));
        try (ReferenceImageStore store =
                store(transport, resolver(ALLOWED_HOST, PUBLIC_ADDRESS), ALLOWED_HOST)) {
            Path reference = store.reference(
                    canonicalEntry("https://" + ALLOWED_HOST + "/ref.png")).orElseThrow();
            assertArrayEquals(PNG_2X2, Files.readAllBytes(reference),
                    "a same-length forged cache file must fail the digest check and be refetched");
            assertEquals(1, transport.requested().size());
        }
    }
}
