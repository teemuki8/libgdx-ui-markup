package dev.gdx.markup.qualification;

import java.awt.image.BufferedImage;

/**
 * Deterministic deliberate-negative image transformations used to calibrate the fidelity
 * gate. Each transform targets a failure mode a careless recreation can exhibit: a vertical
 * flip or a fixed translation misplaces every element (geometry), a hue rotation re-palettes
 * the whole screen (color), and a box blur or a uniform scale destroys typography/detail.
 *
 * <p>Every transform is a pure function of the source pixels with fixed integer/rounding
 * math and replicate borders, so the same source always yields the same negative — the
 * calibration is reproducible offline with no random or external input. The resulting images
 * are committed under {@code src/test/resources/negative/} as the reproducible fixtures.
 */
final class NegativeTransforms {
    /** Fixed translation: 7.5% of the 1280x720 recreation canvas. */
    static final int TRANSLATE_DX = 192;
    static final int TRANSLATE_DY = 108;
    /** Fixed hue rotation in degrees (YIQ-style chroma rotation). */
    static final int HUE_DEGREES = 120;
    /**
     * Fixed separable box-blur radius. Radius 8 smears 5-8 pixel text strokes well below the
     * sharp-edge magnitude threshold, so the deliberate detail negative genuinely removes
     * typography instead of merely spreading it.
     */
    static final int BLUR_RADIUS = 8;
    /** Fixed uniform scale factor; the scaled content is centered on the source canvas. */
    static final double SCALE_FACTOR = 0.75;

    private NegativeTransforms() {
    }

    /** Vertical flip (mirror around the horizontal axis). */
    static BufferedImage flip(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, source.getRGB(x, height - 1 - y));
            }
        }
        return out;
    }

    /** Fixed translation; the vacated border is filled with the source's corner color. */
    static BufferedImage translate(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int fill = source.getRGB(0, 0);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sx = x - TRANSLATE_DX;
                int sy = y - TRANSLATE_DY;
                out.setRGB(x, y, sx >= 0 && sx < width && sy >= 0 && sy < height
                        ? source.getRGB(sx, sy)
                        : fill);
            }
        }
        return out;
    }

    /** Fixed hue rotation preserving luminance (YIQ chroma rotation, documented formula). */
    static BufferedImage hueRotate(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        double radians = Math.toRadians(HUE_DEGREES);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = source.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                double luminance = 0.299 * r + 0.587 * g + 0.114 * b;
                double redChroma = r - luminance;
                double blueChroma = b - luminance;
                double rotatedRed = redChroma * cos - blueChroma * sin;
                double rotatedBlue = redChroma * sin + blueChroma * cos;
                int nr = clamp(Math.round((float) (luminance + rotatedRed)));
                int ng = clamp(Math.round((float) (luminance - 0.509 * rotatedRed
                        - 0.194 * rotatedBlue)));
                int nb = clamp(Math.round((float) (luminance - 0.299 / 0.886 * rotatedRed
                        - 0.587 / 0.886 * rotatedBlue)));
                out.setRGB(x, y, (nr << 16) | (ng << 8) | nb);
            }
        }
        return out;
    }

    /**
     * Separable box blur with replicate borders (radius {@value #BLUR_RADIUS}). The red,
     * green, and blue channels are averaged independently: summing packed ARGB integers
     * would let channel carries corrupt the color (a measured 4.5x gradient-energy
     * inflation), which would turn the deliberate detail negative into garbage that the
     * detail metric could not reject.
     */
    static BufferedImage blur(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int diameter = BLUR_RADIUS * 2 + 1;
        int[] red = new int[Math.max(width, height)];
        int[] green = new int[Math.max(width, height)];
        int[] blue = new int[Math.max(width, height)];
        BufferedImage horizontal = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = source.getRGB(x, y);
                red[x] = (rgb >> 16) & 0xff;
                green[x] = (rgb >> 8) & 0xff;
                blue[x] = rgb & 0xff;
            }
            for (int x = 0; x < width; x++) {
                long sumRed = 0;
                long sumGreen = 0;
                long sumBlue = 0;
                for (int i = -BLUR_RADIUS; i <= BLUR_RADIUS; i++) {
                    int index = clampIndex(x + i, width);
                    sumRed += red[index];
                    sumGreen += green[index];
                    sumBlue += blue[index];
                }
                horizontal.setRGB(x, y, ((int) (sumRed / diameter) << 16)
                        | ((int) (sumGreen / diameter) << 8)
                        | (int) (sumBlue / diameter));
            }
        }
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int rgb = horizontal.getRGB(x, y);
                red[y] = (rgb >> 16) & 0xff;
                green[y] = (rgb >> 8) & 0xff;
                blue[y] = rgb & 0xff;
            }
            for (int y = 0; y < height; y++) {
                long sumRed = 0;
                long sumGreen = 0;
                long sumBlue = 0;
                for (int i = -BLUR_RADIUS; i <= BLUR_RADIUS; i++) {
                    int index = clampIndex(y + i, height);
                    sumRed += red[index];
                    sumGreen += green[index];
                    sumBlue += blue[index];
                }
                out.setRGB(x, y, ((int) (sumRed / diameter) << 16)
                        | ((int) (sumGreen / diameter) << 8)
                        | (int) (sumBlue / diameter));
            }
        }
        return out;
    }

    /** Uniform scale centered on the source canvas; borders get the corner color. */
    static BufferedImage scale(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int scaledWidth = Math.max(1, (int) Math.round(width * SCALE_FACTOR));
        int scaledHeight = Math.max(1, (int) Math.round(height * SCALE_FACTOR));
        // Center the scaled content; an odd leftover pixel goes to the right/bottom border,
        // matching the documented "centered" behavior and keeping both borders symmetric.
        int offsetX = (width - scaledWidth) / 2;
        int offsetY = (height - scaledHeight) / 2;
        int fill = source.getRGB(0, 0);
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x >= offsetX && x < offsetX + scaledWidth
                        && y >= offsetY && y < offsetY + scaledHeight) {
                    int sx = Math.min(width - 1, (int) ((x - offsetX) / SCALE_FACTOR));
                    int sy = Math.min(height - 1, (int) ((y - offsetY) / SCALE_FACTOR));
                    out.setRGB(x, y, source.getRGB(sx, sy));
                } else {
                    out.setRGB(x, y, fill);
                }
            }
        }
        return out;
    }

    /** All five negatives in a stable order, for calibration and fixture generation. */
    static BufferedImage[] all(BufferedImage source) {
        return new BufferedImage[] {
            flip(source), translate(source), hueRotate(source), blur(source), scale(source),
        };
    }

    /** Human-readable names aligned with {@link #all}. */
    static String[] names() {
        return new String[] {"flip", "translate", "hue", "blur", "scale"};
    }

    private static int clampIndex(int index, int length) {
        return Math.max(0, Math.min(length - 1, index));
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : value > 255 ? 255 : value;
    }
}
