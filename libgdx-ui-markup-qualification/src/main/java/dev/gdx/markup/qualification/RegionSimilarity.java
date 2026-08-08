package dev.gdx.markup.qualification;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Structural layout similarity between a reference game UI and its markup recreation.
 *
 * <p>Both images are partitioned into a coarse cell grid; each cell's gray-level variance is
 * classified as structured (text, borders, panels) using the image's own variance histogram
 * (mean + 0.75 standard deviation), which keeps the classifier robust to art style and font
 * differences. The score is the Dice coefficient of the two structured-region masks after a
 * one-cell dilation on both sides, so it measures whether UI elements sit in the same regions
 * with a tolerance for scale and art differences, not whether pixels match.
 */
public final class RegionSimilarity {
    /** Grid geometry shared by every comparison. */
    public static final int GRID_COLS = 80;
    /** Grid geometry shared by every comparison. */
    public static final int GRID_ROWS = 45;

    private static final double CLASSIFIER_DEVIATIONS = 0.75;

    private RegionSimilarity() {
    }

    /** One measured comparison: dilated Dice and the raw structured-cell counts. */
    public record Regions(double dice, int referenceCells, int recreationCells) {
    }

    /** Measures region overlap between the authenticated reference and the recreation image. */
    public static Regions measure(ReferenceImageStore.ReferenceImage reference, Path recreation)
            throws IOException {
        BufferedImage decoded = BoundedDecode.decode(recreation);
        return measure(reference.width(), reference.height(), reference::rgb,
                decoded.getWidth(), decoded.getHeight(), decoded::getRGB);
    }

    /** Package-private: dilated Dice over two caller-supplied bounded pixel sources. */
    static Regions measure(int widthA, int heightA, java.util.function.IntBinaryOperator rgbA,
            int widthB, int heightB, java.util.function.IntBinaryOperator rgbB) {
        boolean[][] referenceRegions = regions(widthA, heightA, rgbA);
        boolean[][] recreationRegions = regions(widthB, heightB, rgbB);
        boolean[][] dilatedReference = dilate(referenceRegions);
        boolean[][] dilatedRecreation = dilate(recreationRegions);
        int intersection = 0;
        int dilatedReferenceCells = 0;
        int dilatedRecreationCells = 0;
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                if (dilatedReference[row][col]) {
                    dilatedReferenceCells++;
                }
                if (dilatedRecreation[row][col]) {
                    dilatedRecreationCells++;
                }
                if (dilatedReference[row][col] && dilatedRecreation[row][col]) {
                    intersection++;
                }
            }
        }
        int total = dilatedReferenceCells + dilatedRecreationCells;
        double dice = total == 0 ? 0.0 : 2.0 * intersection / total;
        return new Regions(dice, count(referenceRegions), count(recreationRegions));
    }

    /** Expands every structured cell to its 3x3 neighborhood (one-cell tolerance). */
    private static boolean[][] dilate(boolean[][] mask) {
        boolean[][] dilated = new boolean[GRID_ROWS][GRID_COLS];
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                if (!mask[row][col]) {
                    continue;
                }
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int targetRow = row + dr;
                        int targetCol = col + dc;
                        if (targetRow >= 0 && targetRow < GRID_ROWS
                                && targetCol >= 0 && targetCol < GRID_COLS) {
                            dilated[targetRow][targetCol] = true;
                        }
                    }
                }
            }
        }
        return dilated;
    }

    private static int count(boolean[][] mask) {
        int count = 0;
        for (boolean[] row : mask) {
            for (boolean cell : row) {
                if (cell) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Classifies the authenticated reference's already-decoded immutable pixels. */
    static boolean[][] regions(ReferenceImageStore.ReferenceImage reference) {
        return regions(reference.width(), reference.height(), reference::rgb);
    }

    /** Classifies the caller's own recreation screenshot, decoded at the bounded resolution. */
    static boolean[][] regions(Path image) throws IOException {
        BufferedImage decoded = BoundedDecode.decode(image);
        return regions(decoded.getWidth(), decoded.getHeight(), decoded::getRGB);
    }

    /** Package-private: grid classifier over a caller-supplied bounded pixel source. */
    static boolean[][] regions(int width, int height,
            java.util.function.IntBinaryOperator rgb) {
        int[][] gray = gray(width, height, rgb);
        int[][] sum = integral(gray);
        long[][] sumSquares = integralSquares(gray);
        double[] cells = new double[GRID_COLS * GRID_ROWS];
        double total = 0;
        for (int row = 0; row < GRID_ROWS; row++) {
            int y0 = row * height / GRID_ROWS;
            int y1 = (row + 1) * height / GRID_ROWS;
            for (int col = 0; col < GRID_COLS; col++) {
                int x0 = col * width / GRID_COLS;
                int x1 = (col + 1) * width / GRID_COLS;
                double count = (double) (x1 - x0) * (y1 - y0);
                double mean = rect(sum, x0, y0, x1, y1) / count;
                double meanOfSquares = rect(sumSquares, x0, y0, x1, y1) / count;
                double variance = Math.max(0, meanOfSquares - mean * mean);
                cells[row * GRID_COLS + col] = variance;
                total += variance;
            }
        }
        double mean = total / cells.length;
        double squaredDeviations = 0;
        for (double cell : cells) {
            squaredDeviations += (cell - mean) * (cell - mean);
        }
        double standardDeviation = Math.sqrt(squaredDeviations / cells.length);
        double threshold = mean + CLASSIFIER_DEVIATIONS * standardDeviation;
        boolean[][] result = new boolean[GRID_ROWS][GRID_COLS];
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                result[row][col] = cells[row * GRID_COLS + col] > threshold;
            }
        }
        return result;
    }

    private static int[][] gray(int width, int height, java.util.function.IntBinaryOperator rgb) {
        int[][] gray = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = rgb.applyAsInt(x, y);
                int red = (value >> 16) & 0xff;
                int green = (value >> 8) & 0xff;
                int blue = value & 0xff;
                gray[y][x] = (red * 299 + green * 587 + blue * 114) / 1000;
            }
        }
        return gray;
    }

    private static int[][] integral(int[][] values) {
        int height = values.length;
        int width = values[0].length;
        int[][] integral = new int[height + 1][width + 1];
        for (int y = 0; y < height; y++) {
            int rowSum = 0;
            for (int x = 0; x < width; x++) {
                rowSum += values[y][x];
                integral[y + 1][x + 1] = integral[y][x + 1] + rowSum;
            }
        }
        return integral;
    }

    private static long[][] integralSquares(int[][] values) {
        int height = values.length;
        int width = values[0].length;
        long[][] integral = new long[height + 1][width + 1];
        for (int y = 0; y < height; y++) {
            long rowSum = 0;
            for (int x = 0; x < width; x++) {
                long value = values[y][x];
                rowSum += value * value;
                integral[y + 1][x + 1] = integral[y][x + 1] + rowSum;
            }
        }
        return integral;
    }

    private static long rect(int[][] integral, int x0, int y0, int x1, int y1) {
        return (long) integral[y1][x1] - integral[y0][x1] - integral[y1][x0] + integral[y0][x0];
    }

    private static long rect(long[][] integral, int x0, int y0, int x1, int y1) {
        return integral[y1][x1] - integral[y0][x1] - integral[y1][x0] + integral[y0][x0];
    }
}
