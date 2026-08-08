package dev.gdx.markup.qualification;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Deterministic multi-signal visual fidelity scorer: geometry, color, and typography/detail
 * components measured over the bounded normalized images, plus the coarse layout diagnostic.
 *
 * <p>All three components are pure functions of the decoded pixels and the fixed analysis
 * grid, computed with primitive arrays and fixed-order integer operations, so identical inputs
 * always produce identical scores offline with no OCR, network, or model dependency:
 *
 * <ul>
 *   <li><b>Geometry</b> — both images are partitioned into the 80x45 cell grid; each cell is
 *       classified as edge-structured when its mean Sobel gradient magnitude (replicate
 *       borders) exceeds the image's own mean + 0.75 standard deviations. The score is the
 *       Dice coefficient of the two structured masks with no dilation: an inverted or
 *       translated recreation shifts every edge to different cells and collapses the overlap.
 *       Both-empty masks score 1, one-sided masks 0.
 *   <li><b>Color</b> — a fixed 4-bit-per-channel RGB histogram (4096 bins, primitive int
 *       array) of each image; the score is the histogram intersection normalized by the
 *       larger total, so a hue-rotated or re-paletted recreation drops without any geometric
 *       dependence.
 *   <li><b>Detail</b> — per cell, the high-frequency magnitude (pixels with Sobel magnitude
 *       at least 16) and a four-bin gradient-quadrant orientation histogram are compared
 *       between the two images; each cell scores the average of the magnitude ratio and the
 *       orientation total-variation similarity. Cells blank in both images score 1, one-sided
 *       detail scores 0, and the average runs over all cells, so a blurred or missing-text
 *       recreation is penalized in exactly the cells where the reference has detail.
 * </ul>
 */
public final class VisualFidelity {
    /** Grid geometry shared by every comparison. */
    public static final int GRID_COLS = RegionSimilarity.GRID_COLS;
    /** Grid geometry shared by every comparison. */
    public static final int GRID_ROWS = RegionSimilarity.GRID_ROWS;
    /** Sobel magnitude threshold for counting a pixel as typography/detail. */
    static final int DETAIL_EDGE_MAGNITUDE = 16;
    /** Sobel magnitude threshold for the sharp-edge orientation histogram. */
    static final int SHARP_EDGE_MAGNITUDE = 64;
    /** Color quantization bits per channel (4 bits -> 4096 fixed bins). */
    static final int COLOR_BITS = 4;
    /** Classifier spread: cells above mean + 0.75 standard deviations are structured. */
    static final double CLASSIFIER_DEVIATIONS = 0.75;

    private VisualFidelity() {
    }

    /** Measures one recreation screenshot against the authenticated reference. */
    public static FidelityScore measure(ReferenceImageStore.ReferenceImage reference, Path recreation)
            throws IOException {
        return measure(reference, BoundedDecode.decode(recreation));
    }

    /** Package-private overload for tests that already hold a decoded recreation. */
    static FidelityScore measure(ReferenceImageStore.ReferenceImage reference,
            BufferedImage recreation) {
        return measure(pixels(reference), pixels(recreation));
    }

    /** Package-private overload for synthetic in-memory tests (both images). */
    static FidelityScore measure(BufferedImage reference, BufferedImage recreation) {
        return measure(pixels(reference), pixels(recreation));
    }

    private static FidelityScore measure(Pixels reference, Pixels recreation) {
        RegionSimilarity.Regions coarse = RegionSimilarity.measure(
                reference.width(), reference.height(), reference::rgb,
                recreation.width(), recreation.height(), recreation::rgb);
        double geometry = geometry(reference, recreation);
        double color = color(reference, recreation);
        double detail = detail(reference, recreation);
        return new FidelityScore(coarse.dice(), geometry, color, detail,
                coarse.referenceCells(), coarse.recreationCells());
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Orientation-weighted Dice of the thresholded Sobel-gradient cell masks.
     *
     * <p>Each cell that is edge-structured in both images contributes its directed-gradient
     * orientation similarity (1 - 0.5 x total-variation distance of the four-bin gradient
     * quadrant histograms) instead of a plain 1. A vertical flip mirrors every vertical
     * edge's gradient direction and a translation or scale moves edges to different cells,
     * so all three deliberate negatives collapse the weighted overlap while a faithful
     * recreation keeps most of its aligned cells (whose edge directions match the
     * reference's). The coarse 80x45 grid keeps approximate recreations measurable; the
     * orientation weighting is what rejects the layout negatives, not grid resolution.
     */
    private static double geometry(Pixels a, Pixels b) {
        boolean[][] maskA = edgeMask(a);
        boolean[][] maskB = edgeMask(b);
        int countA = 0;
        int countB = 0;
        double orientedIntersection = 0;
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                if (maskA[row][col]) {
                    countA++;
                }
                if (maskB[row][col]) {
                    countB++;
                }
                if (maskA[row][col] && maskB[row][col]) {
                    orientedIntersection += orientationSimilarity(a, b, row, col);
                }
            }
        }
        if (countA == 0 && countB == 0) {
            return 1.0;
        }
        if (countA == 0 || countB == 0) {
            return 0.0;
        }
        return 2.0 * orientedIntersection / (countA + countB);
    }

    /** Directed-gradient orientation similarity of one cell pair, in [0, 1]. */
    private static double orientationSimilarity(Pixels a, Pixels b, int row, int col) {
        int widthA = a.width();
        int heightA = a.height();
        int widthB = b.width();
        int heightB = b.height();
        int[] grayA = gray(a);
        int[] grayB = gray(b);
        int[] binA = new int[4];
        int[] binB = new int[4];
        int countA = 0;
        int countB = 0;
        for (int y = row * heightA / GRID_ROWS; y < (row + 1) * heightA / GRID_ROWS; y++) {
            for (int x = col * widthA / GRID_COLS; x < (col + 1) * widthA / GRID_COLS; x++) {
                if (sobelMagnitude(grayA, widthA, heightA, x, y) >= DETAIL_EDGE_MAGNITUDE) {
                    binA[sobelOrientationBin(grayA, widthA, heightA, x, y)]++;
                    countA++;
                }
            }
        }
        for (int y = row * heightB / GRID_ROWS; y < (row + 1) * heightB / GRID_ROWS; y++) {
            for (int x = col * widthB / GRID_COLS; x < (col + 1) * widthB / GRID_COLS; x++) {
                if (sobelMagnitude(grayB, widthB, heightB, x, y) >= DETAIL_EDGE_MAGNITUDE) {
                    binB[sobelOrientationBin(grayB, widthB, heightB, x, y)]++;
                    countB++;
                }
            }
        }
        if (countA == 0 || countB == 0) {
            return 0.0;
        }
        double totalVariation = 0;
        for (int i = 0; i < 4; i++) {
            double shareA = binA[i] / (double) countA;
            double shareB = binB[i] / (double) countB;
            totalVariation += Math.abs(shareA - shareB);
        }
        return Math.max(0.0, 1.0 - 0.5 * totalVariation);
    }

    /** Classifies cells as edge-structured using the image's own gradient histogram. */
    private static boolean[][] edgeMask(Pixels pixels) {
        int width = pixels.width();
        int height = pixels.height();
        int[] gray = gray(pixels);
        double[] cells = new double[GRID_COLS * GRID_ROWS];
        double total = 0;
        for (int row = 0; row < GRID_ROWS; row++) {
            int y0 = row * height / GRID_ROWS;
            int y1 = (row + 1) * height / GRID_ROWS;
            for (int col = 0; col < GRID_COLS; col++) {
                int x0 = col * width / GRID_COLS;
                int x1 = (col + 1) * width / GRID_COLS;
                long sum = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        sum += sobelMagnitude(gray, width, height, x, y);
                    }
                }
                double mean = (double) sum / ((x1 - x0) * (y1 - y0));
                cells[row * GRID_COLS + col] = mean;
                total += mean;
            }
        }
        double mean = total / cells.length;
        double squaredDeviations = 0;
        for (double cell : cells) {
            squaredDeviations += (cell - mean) * (cell - mean);
        }
        double standardDeviation = Math.sqrt(squaredDeviations / cells.length);
        double threshold = mean + CLASSIFIER_DEVIATIONS * standardDeviation;
        boolean[][] mask = new boolean[GRID_ROWS][GRID_COLS];
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                mask[row][col] = cells[row * GRID_COLS + col] > threshold;
            }
        }
        return mask;
    }

    // --------------------------------------------------------------------- color

    /** Quantized RGB histogram intersection normalized by the larger image total. */
    private static double color(Pixels a, Pixels b) {
        int bins = 1 << (3 * COLOR_BITS);
        int[] histA = new int[bins];
        int[] histB = new int[bins];
        long totalA = fillHistogram(a, histA);
        long totalB = fillHistogram(b, histB);
        if (totalA == 0 || totalB == 0) {
            return 0.0;
        }
        long intersection = 0;
        for (int i = 0; i < bins; i++) {
            intersection += Math.min(histA[i], histB[i]);
        }
        return (double) intersection / Math.max(totalA, totalB);
    }

    private static long fillHistogram(Pixels pixels, int[] histogram) {
        long total = 0;
        for (int y = 0; y < pixels.height(); y++) {
            for (int x = 0; x < pixels.width(); x++) {
                int rgb = pixels.rgb(x, y);
                int shift = 8 - COLOR_BITS;
                int r = ((rgb >> 16) & 0xff) >> shift;
                int g = ((rgb >> 8) & 0xff) >> shift;
                int b = (rgb & 0xff) >> shift;
                histogram[(r << (2 * COLOR_BITS)) | (g << COLOR_BITS) | b]++;
                total++;
            }
        }
        return total;
    }

    // -------------------------------------------------------------------- detail

    /**
     * Typography/detail fidelity with a global energy term that a blur cannot game.
     *
     * <p>Every local averaging convention rewards blurring a sparse recreation, because
     * erasing the recreation's detail converts low-scoring cells into "blank agreement".
     * The score therefore combines a local structural term and a global high-frequency
     * energy term:
     *
     * <ul>
     *   <li>{@code localMatch} averages, over cells where the reference has detail, the
     *       unthresholded gradient-energy ratio (min/max) and the sharp-edge orientation
     *       similarity. Cells where the reference has detail but the recreation has none
     *       score 0 (missing detail); reference-blank cells are excluded so a blur cannot
     *       raise the average by erasing spurious recreation detail.
     *   <li>{@code globalEnergyRatio} is min/max of the two images' total gradient energy.
     *       Blur reduces the recreation's total high-frequency energy monotonically (box
     *       blur is an averaging operator), so a blurred recreation always loses on this
     *       term no matter where its detail was erased or diffused.
     * </ul>
     */
    private static double detail(Pixels a, Pixels b) {
        int widthA = a.width();
        int heightA = a.height();
        int widthB = b.width();
        int heightB = b.height();
        int[] grayA = gray(a);
        int[] grayB = gray(b);
        long energyA = 0;
        long energyB = 0;
        for (int y = 0; y < heightA; y++) {
            for (int x = 0; x < widthA; x++) {
                energyA += sobelMagnitude(grayA, widthA, heightA, x, y);
            }
        }
        for (int y = 0; y < heightB; y++) {
            for (int x = 0; x < widthB; x++) {
                energyB += sobelMagnitude(grayB, widthB, heightB, x, y);
            }
        }
        double localMatch = localDetail(a, b, grayA, grayB);
        double globalEnergyRatio = energyA == 0 && energyB == 0 ? 1.0
                : Math.min(energyA, energyB) / (double) Math.max(energyA, energyB);
        return 0.5 * localMatch + 0.5 * globalEnergyRatio;
    }

    /** Grid-local structural detail over reference-detail cells only. */
    private static double localDetail(Pixels a, Pixels b, int[] grayA, int[] grayB) {
        int widthA = a.width();
        int heightA = a.height();
        int widthB = b.width();
        int heightB = b.height();
        double total = 0;
        long counted = 0;
        for (int row = 0; row < GRID_ROWS; row++) {
            int ay0 = row * heightA / GRID_ROWS;
            int ay1 = (row + 1) * heightA / GRID_ROWS;
            int by0 = row * heightB / GRID_ROWS;
            int by1 = (row + 1) * heightB / GRID_ROWS;
            for (int col = 0; col < GRID_COLS; col++) {
                int ax0 = col * widthA / GRID_COLS;
                int ax1 = (col + 1) * widthA / GRID_COLS;
                int bx0 = col * widthB / GRID_COLS;
                int bx1 = (col + 1) * widthB / GRID_COLS;
                long energyA = 0;
                long energyB = 0;
                int[] binA = new int[4];
                int[] binB = new int[4];
                int sharpA = 0;
                int sharpB = 0;
                for (int y = ay0; y < ay1; y++) {
                    for (int x = ax0; x < ax1; x++) {
                        int magnitude = sobelMagnitude(grayA, widthA, heightA, x, y);
                        energyA += magnitude;
                        if (magnitude >= SHARP_EDGE_MAGNITUDE) {
                            binA[sobelOrientationBin(grayA, widthA, heightA, x, y)]++;
                            sharpA++;
                        }
                    }
                }
                for (int y = by0; y < by1; y++) {
                    for (int x = bx0; x < bx1; x++) {
                        int magnitude = sobelMagnitude(grayB, widthB, heightB, x, y);
                        energyB += magnitude;
                        if (magnitude >= SHARP_EDGE_MAGNITUDE) {
                            binB[sobelOrientationBin(grayB, widthB, heightB, x, y)]++;
                            sharpB++;
                        }
                    }
                }
                if (energyA == 0 && energyB == 0) {
                    total += 1.0; // blank agreement
                    counted++;
                    continue;
                }
                if (energyA == 0 || energyB == 0) {
                    total += 0.0; // one-sided detail: missing or spurious
                    counted++;
                    continue;
                }
                double magnitudeSimilarity =
                        Math.min(energyA, energyB) / (double) Math.max(energyA, energyB);
                double orientationSimilarity = 0.0;
                if (sharpA > 0 && sharpB > 0) {
                    double totalVariation = 0;
                    for (int i = 0; i < 4; i++) {
                        double shareA = binA[i] / (double) sharpA;
                        double shareB = binB[i] / (double) sharpB;
                        totalVariation += Math.abs(shareA - shareB);
                    }
                    orientationSimilarity = Math.max(0.0, 1.0 - 0.5 * totalVariation);
                }
                total += 0.5 * magnitudeSimilarity + 0.5 * orientationSimilarity;
                counted++;
            }
        }
        return counted == 0 ? 0.0 : total / counted;
    }

    // ------------------------------------------------------------- pixel helpers

    /** Replicate-border Sobel gradient magnitude (|gx| + |gy|), deterministic integer math. */
    private static int sobelMagnitude(int[] gray, int width, int height, int x, int y) {
        int y0 = Math.max(0, y - 1);
        int y1 = Math.min(height - 1, y + 1);
        int x0 = Math.max(0, x - 1);
        int x1 = Math.min(width - 1, x + 1);
        int gx = (gray[y0 * width + x1] + 2 * gray[y * width + x1] + gray[y1 * width + x1])
                - (gray[y0 * width + x0] + 2 * gray[y * width + x0] + gray[y1 * width + x0]);
        int gy = (gray[y0 * width + x0] + 2 * gray[y0 * width + x] + gray[y0 * width + x1])
                - (gray[y1 * width + x0] + 2 * gray[y1 * width + x] + gray[y1 * width + x1]);
        return Math.abs(gx) + Math.abs(gy);
    }

    /**
     * Four-bin gradient orientation: the quadrant of (gx, gy). Deterministic integer
     * classification with no transcendental math.
     */
    private static int sobelOrientationBin(int[] gray, int width, int height, int x, int y) {
        int y0 = Math.max(0, y - 1);
        int y1 = Math.min(height - 1, y + 1);
        int x0 = Math.max(0, x - 1);
        int x1 = Math.min(width - 1, x + 1);
        int gx = (gray[y0 * width + x1] + 2 * gray[y * width + x1] + gray[y1 * width + x1])
                - (gray[y0 * width + x0] + 2 * gray[y * width + x0] + gray[y1 * width + x0]);
        int gy = (gray[y0 * width + x0] + 2 * gray[y0 * width + x] + gray[y0 * width + x1])
                - (gray[y1 * width + x0] + 2 * gray[y1 * width + x] + gray[y1 * width + x1]);
        return (gx < 0 ? 1 : 0) | (gy < 0 ? 2 : 0);
    }

    /** Rec. 601 luma in integer fixed point; no per-pixel allocation. */
    private static int[] gray(Pixels pixels) {
        int width = pixels.width();
        int height = pixels.height();
        int[] gray = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = pixels.rgb(x, y);
                int red = (rgb >> 16) & 0xff;
                int green = (rgb >> 8) & 0xff;
                int blue = rgb & 0xff;
                gray[y * width + x] = (red * 299 + green * 587 + blue * 114) / 1000;
            }
        }
        return gray;
    }

    private static Pixels pixels(ReferenceImageStore.ReferenceImage image) {
        return new Pixels() {
            @Override public int width() {
                return image.width();
            }

            @Override public int height() {
                return image.height();
            }

            @Override public int rgb(int x, int y) {
                return image.rgb(x, y);
            }
        };
    }

    private static Pixels pixels(BufferedImage image) {
        return new Pixels() {
            @Override public int width() {
                return image.getWidth();
            }

            @Override public int height() {
                return image.getHeight();
            }

            @Override public int rgb(int x, int y) {
                return image.getRGB(x, y);
            }
        };
    }

    /** Read-only pixel source so metrics never see or mutate the decoded images. */
    private interface Pixels {
        int width();

        int height();

        int rgb(int x, int y);
    }
}
