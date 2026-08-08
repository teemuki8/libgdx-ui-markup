package dev.gdx.markup.qualification;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Image decode bounded to a fixed analysis resolution. The image header (width, height, reader
 * format) is read without allocating pixels; the pixel decode uses {@code ImageReader} source
 * subsampling so the resulting {@link BufferedImage} is at most
 * {@link #MAX_ANALYSIS_DIMENSION} per side. A decompression bomb therefore cannot force a
 * full-resolution allocation, and canonical corpus images (at or below the analysis dimension)
 * decode at their native resolution so measured similarity is unchanged.
 */
final class BoundedDecode {
    /** Analysis resolution cap; also the native resolution of the largest corpus reference. */
    static final int MAX_ANALYSIS_DIMENSION = 1920;

    private BoundedDecode() {
    }

    /** Image header facts read without decoding any pixels. */
    record Header(int width, int height, String formatName) {
    }

    /** Reads the header of an image in memory; never allocates pixels. */
    static Header header(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IOException("no image input service");
            }
            return header(input);
        }
    }

    /** Decodes an in-memory image at the bounded analysis resolution. */
    static BufferedImage decode(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IOException("no image input service");
            }
            return read(input);
        }
    }

    /** Decodes an image file (the caller's own render output) at the bounded analysis resolution. */
    static BufferedImage decode(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
                ImageInputStream input = ImageIO.createImageInputStream(raw)) {
            if (input == null) {
                throw new IOException("no image input service for " + file);
            }
            return read(input);
        }
    }

    private static Header header(ImageInputStream input) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            throw new IOException("no ImageIO reader for the stream");
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(input);
            return new Header(reader.getWidth(0), reader.getHeight(0), reader.getFormatName());
        } finally {
            reader.dispose();
        }
    }

    private static BufferedImage read(ImageInputStream input) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            throw new IOException("no ImageIO reader for the stream");
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(input);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            int sampleX = sampleFactor(width);
            int sampleY = sampleFactor(height);
            javax.imageio.ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(sampleX, sampleY, 0, 0);
            return reader.read(0, param);
        } finally {
            reader.dispose();
        }
    }

    private static int sampleFactor(int dimension) {
        return Math.max(1, (int) Math.ceil(dimension / (double) MAX_ANALYSIS_DIMENSION));
    }
}
