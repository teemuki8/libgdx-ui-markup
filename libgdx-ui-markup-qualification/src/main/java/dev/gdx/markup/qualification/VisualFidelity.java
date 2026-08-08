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
    /**
     * Fixed analysis resolution: every input (subsampled references at 960x540, recreations
     * at 1280x720, synthetic fixtures) is resampled once to this 16:9 canvas before any
     * metric component runs, so color proportions, detail energy, and the grid geometry all
     * operate on identical dimensions and no energy normalization is needed for resolution
     * differences.
     */
    static final int ANALYSIS_WIDTH = 640;
    static final int ANALYSIS_HEIGHT = 360;

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

    /**
     * Package-private seam for allocation-bounded tests over counting pixel sources. Both
     * inputs are resampled exactly once into immutable packed-RGB primitive buffers at the
     * fixed analysis resolution; every metric component then reads the buffers, never the
     * source, so each source pixel is read a bounded number of times.
     */
    static FidelityScore measure(Pixels reference, Pixels recreation) {
        int[] referenceBuffer = resample(reference);
        int[] recreationBuffer = resample(recreation);
        Pixels normalizedReference = bufferPixels(referenceBuffer);
        Pixels normalizedRecreation = bufferPixels(recreationBuffer);
        RegionSimilarity.Regions coarse = RegionSimilarity.measure(
                ANALYSIS_WIDTH, ANALYSIS_HEIGHT, normalizedReference::rgb,
                ANALYSIS_WIDTH, ANALYSIS_HEIGHT, normalizedRecreation::rgb);
        double geometry = geometry(normalizedReference, normalizedRecreation);
        double color = color(normalizedReference, normalizedRecreation);
        double detail = detail(normalizedReference, normalizedRecreation);
        return new FidelityScore(coarse.dice(), geometry, color, detail,
                coarse.referenceCells(), coarse.recreationCells());
    }

    /**
     * Deterministic pixel-center bilinear resample of any source into the fixed analysis
     * resolution. Each output pixel samples its source at {@code (ox+0.5)*sw/ow - 0.5},
     * averaged over the four neighbors with replicate borders; channels are interpolated
     * independently so packed-ARGB carries cannot corrupt colors. A source already at the
     * analysis resolution still flows through the buffer so metrics never re-read it.
     */
    static int[] resample(Pixels source) {
        int sourceWidth = source.width();
        int sourceHeight = source.height();
        int[] out = new int[ANALYSIS_WIDTH * ANALYSIS_HEIGHT];
        for (int oy = 0; oy < ANALYSIS_HEIGHT; oy++) {
            double y = ((oy + 0.5) * sourceHeight / ANALYSIS_HEIGHT) - 0.5;
            for (int ox = 0; ox < ANALYSIS_WIDTH; ox++) {
                double x = ((ox + 0.5) * sourceWidth / ANALYSIS_WIDTH) - 0.5;
                out[oy * ANALYSIS_WIDTH + ox] =
                        sampleBilinear(source, sourceWidth, sourceHeight, x, y);
            }
        }
        return out;
    }

    private static int sampleBilinear(Pixels source, int width, int height, double x, double y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        x0 = Math.max(0, Math.min(width - 1, x0));
        y0 = Math.max(0, Math.min(height - 1, y0));
        int x1 = Math.min(width - 1, x0 + 1);
        int y1 = Math.min(height - 1, y0 + 1);
        double tx = x - Math.floor(x);
        double ty = y - Math.floor(y);
        int c00 = source.rgb(x0, y0);
        int c10 = source.rgb(x1, y0);
        int c01 = source.rgb(x0, y1);
        int c11 = source.rgb(x1, y1);
        int r = (int) Math.round(lerp(lerp((c00 >> 16) & 0xff, (c10 >> 16) & 0xff, tx),
                lerp((c01 >> 16) & 0xff, (c11 >> 16) & 0xff, tx), ty));
        int g = (int) Math.round(lerp(lerp((c00 >> 8) & 0xff, (c10 >> 8) & 0xff, tx),
                lerp((c01 >> 8) & 0xff, (c11 >> 8) & 0xff, tx), ty));
        int b = (int) Math.round(lerp(lerp(c00 & 0xff, c10 & 0xff, tx),
                lerp(c01 & 0xff, c11 & 0xff, tx), ty));
        return (r << 16) | (g << 8) | b;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Read-only Pixels over an immutable packed-RGB buffer at the analysis resolution. */
    private static Pixels bufferPixels(int[] rgb) {
        return new Pixels() {
            @Override public int width() {
                return ANALYSIS_WIDTH;
            }

            @Override public int height() {
                return ANALYSIS_HEIGHT;
            }

            @Override public int rgb(int x, int y) {
                return rgb[y * ANALYSIS_WIDTH + x];
            }
        };
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
        int[] grayA = gray(a);
        int[] grayB = gray(b);
        boolean[][] maskA = edgeMask(a, grayA);
        boolean[][] maskB = edgeMask(b, grayB);
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
                    orientedIntersection += orientationSimilarity(a, grayA, b, grayB, row, col);
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

    /**
     * Directed-gradient orientation similarity of one cell pair, in [0, 1]. The gray arrays
     * are computed once per image by the caller and reused across every cell, so the metric
     * reads each pixel a bounded number of times instead of re-decoding per matched cell.
     */
    private static double orientationSimilarity(Pixels a, int[] grayA, Pixels b, int[] grayB,
            int row, int col) {
        int widthA = a.width();
        int heightA = a.height();
        int widthB = b.width();
        int heightB = b.height();
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
    private static boolean[][] edgeMask(Pixels pixels, int[] gray) {
        int width = pixels.width();
        int height = pixels.height();
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

    /**
     * Quantized RGB histogram intersection over normalized bin proportions:
     * {@code sum min(histA[i]/totalA, histB[i]/totalB)}. Normalizing by each image's own
     * total makes the score invariant to resolution — a reference subsampled to 960x540 and
     * a recreation rendered at 1280x720 with identical color proportions intersect to 1.0
     * instead of being capped by the raw-count ratio (0.5625). A hue-rotated or re-paletted
     * recreation still drops because its bin proportions move.
     */
    private static double color(Pixels a, Pixels b) {
        int bins = 1 << (3 * COLOR_BITS);
        int[] histA = new int[bins];
        int[] histB = new int[bins];
        long totalA = fillHistogram(a, histA);
        long totalB = fillHistogram(b, histB);
        if (totalA == 0 || totalB == 0) {
            return 0.0;
        }
        double intersection = 0;
        for (int i = 0; i < bins; i++) {
            double shareA = histA[i] / (double) totalA;
            double shareB = histB[i] / (double) totalB;
            intersection += Math.min(shareA, shareB);
        }
        return intersection;
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
        // Both images were resampled to the fixed analysis resolution, so their raw gradient
        // energies are directly comparable; a box blur (an averaging operator) can never
        // increase total high-frequency energy, keeping the anti-blur guarantee.
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
                // Cells of the fixed analysis grid have identical areas in both images, so
                // raw cell energies are directly comparable; no area normalization is needed.
                if (energyA == 0) {
                    continue; // reference-blank cell: outside the local detail scope
                }
                if (energyB == 0) {
                    total += 0.0; // missing reference detail
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
    interface Pixels {
        int width();

        int height();

        int rgb(int x, int y);
    }
}
