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
    static final int TRANSLATE_DX = 96;
    static final int TRANSLATE_DY = 54;
    /** Fixed hue rotation in degrees (YIQ-style chroma rotation). */
    static final int HUE_DEGREES = 120;
    /** Fixed separable box-blur radius. */
    static final int BLUR_RADIUS = 4;
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

    /** Separable box blur with replicate borders (radius {@value #BLUR_RADIUS}). */
    static BufferedImage blur(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int diameter = BLUR_RADIUS * 2 + 1;
        int[] row = new int[width];
        BufferedImage horizontal = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                row[x] = source.getRGB(x, y);
            }
            for (int x = 0; x < width; x++) {
                long sum = 0;
                for (int i = -BLUR_RADIUS; i <= BLUR_RADIUS; i++) {
                    sum += row[clampIndex(x + i, width)];
                }
                horizontal.setRGB(x, y, (int) (sum / diameter));
            }
        }
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] column = new int[height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                column[y] = horizontal.getRGB(x, y);
            }
            for (int y = 0; y < height; y++) {
                long sum = 0;
                for (int i = -BLUR_RADIUS; i <= BLUR_RADIUS; i++) {
                    sum += column[clampIndex(y + i, height)];
                }
                out.setRGB(x, y, (int) (sum / diameter));
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
