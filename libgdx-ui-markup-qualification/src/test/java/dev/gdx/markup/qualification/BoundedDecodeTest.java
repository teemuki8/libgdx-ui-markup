package dev.gdx.markup.qualification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Decoding fetched bytes must never touch disk. {@link BoundedDecode} uses an explicit
 * in-memory {@code ImageInputStream}; {@code ImageIO.createImageInputStream} would instead wrap
 * a {@code ByteArrayInputStream} in a file-cached stream that writes an {@code imageio*.tmp}
 * file while decoding. The tests redirect {@code java.io.tmpdir} to an isolated directory and
 * watch it for ImageIO cache-file creation.
 */
final class BoundedDecodeTest {
    private static final byte[] PNG_2X2 = png(2, 2);

    @Test
    void decodesFetchedBytesWithoutWritingImageIOCacheFiles() throws Exception {
        withIsolatedTmpDir(tmp -> {
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                tmp.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
                BufferedImage decoded = BoundedDecode.decode(PNG_2X2);
                assertEquals(2, decoded.getWidth());
                assertEquals(2, decoded.getHeight());
                List<String> created = drainCreated(watcher);
                assertTrue(created.stream().noneMatch(name -> name.startsWith("imageio")),
                        "decoding fetched bytes must not create ImageIO cache files, saw: "
                                + created);
            }
        });
    }

    @Test
    void readsHeaderOfFetchedBytesWithoutWritingImageIOCacheFiles() throws Exception {
        withIsolatedTmpDir(tmp -> {
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                tmp.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
                BoundedDecode.Header header = BoundedDecode.header(PNG_2X2);
                assertEquals(2, header.width());
                assertEquals(2, header.height());
                assertTrue(header.formatName().equalsIgnoreCase("png"));
                List<String> created = drainCreated(watcher);
                assertTrue(created.stream().noneMatch(name -> name.startsWith("imageio")),
                        "reading a header must not create ImageIO cache files, saw: " + created);
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    @FunctionalInterface
    private interface TmpDirAction {
        void run(Path tmp) throws Exception;
    }

    /** Redirects {@code java.io.tmpdir} to a fresh isolated dir for the action's duration. */
    private static void withIsolatedTmpDir(TmpDirAction action) throws Exception {
        Path isolated = Files.createTempDirectory("bounded-decode-tmp");
        String previous = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", isolated.toString());
        try {
            action.run(isolated);
        } finally {
            if (previous == null) {
                System.clearProperty("java.io.tmpdir");
            } else {
                System.setProperty("java.io.tmpdir", previous);
            }
            deleteRecursively(isolated);
        }
    }

    /** Drains one watch key's queued create events (polling for the synchronous decode). */
    private static List<String> drainCreated(WatchService watcher) throws Exception {
        List<String> created = new ArrayList<>();
        var key = watcher.poll(2, TimeUnit.SECONDS);
        if (key != null) {
            for (var event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    created.add(event.context().toString());
                }
            }
        }
        return created;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Deterministic solid PNG, same generator as the reference store tests. */
    private static byte[] png(int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
