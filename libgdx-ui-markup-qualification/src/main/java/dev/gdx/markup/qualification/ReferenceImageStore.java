package dev.gdx.markup.qualification;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * Fetches copyrighted reference images at test time into a gitignored cache and never
 * redistributes them. Entries that cannot be fetched (offline, moved URL, oversized payload)
 * report empty so the qualification marks them skipped instead of failing.
 */
public final class ReferenceImageStore implements AutoCloseable {
    /** Maximum accepted reference payload. */
    public static final long MAX_BYTES = 8L * 1024 * 1024;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final Path cacheDir;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Creates a store whose cache lives under {@code cacheDir} (created on demand). */
    public ReferenceImageStore(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * Returns the cached reference image for the entry, fetching it when absent. Empty when the
     * image is unavailable, oversized, or not decodable.
     */
    public Optional<Path> reference(CorpusEntry entry) {
        Path cached = cacheDir.resolve(entry.id() + ".jpg");
        if (Files.isRegularFile(cached)) {
            try {
                if (Files.size(cached) > 0) {
                    return Optional.of(cached);
                }
            } catch (IOException failure) {
                // treat an unreadable cache entry as absent and refetch
            }
        }
        Path download = cacheDir.resolve(entry.id() + ".download");
        try {
            Files.createDirectories(cacheDir);
            HttpRequest request = HttpRequest.newBuilder(URI.create(entry.sourceUrl()))
                    .GET()
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            HttpResponse<Path> response =
                    http.send(request, HttpResponse.BodyHandlers.ofFile(download));
            if (response.statusCode() != 200) {
                Files.deleteIfExists(download);
                return Optional.empty();
            }
            if (Files.size(download) > MAX_BYTES || !decodable(download)) {
                Files.deleteIfExists(download);
                return Optional.empty();
            }
            Files.move(download, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(cached);
        } catch (IOException | InterruptedException failure) {
            try {
                Files.deleteIfExists(download);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
            return Optional.empty();
        }
    }

    private static boolean decodable(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            return ImageIO.read(input) != null;
        } catch (IOException failure) {
            return false;
        }
    }

    @Override public void close() {
        http.close();
    }
}
