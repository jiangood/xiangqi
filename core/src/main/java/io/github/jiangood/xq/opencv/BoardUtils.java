package io.github.jiangood.xq.opencv;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.opencv.imgproc.Imgproc.CHAIN_APPROX_SIMPLE;
import static org.opencv.imgproc.Imgproc.RETR_EXTERNAL;

public class BoardUtils {

    private static final Logger log = Logger.getLogger(BoardUtils.class.getName());

    /**
     * Board rectangle within the last cropBoardCenter result, or null if no board was found.
     * Used by computeGrid to avoid the heuristic 10% margin.
     */
    private static Rect sBoardRectInCropped = null;

    // ─── Board cropping ──────────────────────────────────────────────────

    /**
     * 1. Roughly crop the image to a generous height (width × 1.3) to remove
     *    excessive non-board UI space.
     * 2. Detect the board rectangle via edge + contour analysis.
     * 3. Validate the found rectangle has roughly 9:10 aspect ratio.
     * 4. Expand by half a piece width (≈ w/18) and crop to the final region.
     */
    public static Mat cropBoardCenter(Mat src) {
        int h = src.rows();
        int w = src.cols();

        // Step 1: rough crop – generous height based on width
        double maxAspect = 1.3;
        int cropH = (int) (w * maxAspect);
        Mat rough;
        if (cropH < h) {
            int y = (h - cropH) / 2;
            Mat roi = new Mat(src, new Rect(0, y, w, cropH));
            rough = roi.clone();
            roi.release();
        } else {
            rough = src.clone();
        }

        // Step 2: detect board rectangle
        Rect boardRect = findBoardRect(rough);

        if (boardRect != null) {
            // Step 3: validate aspect ratio ≈ 9:10 (allow 0.6–1.4)
            double ar = (double) boardRect.width / boardRect.height;
            if (ar < 0.6 || ar > 1.4) {
                log.warning("Board rect aspect ratio " + String.format("%.2f", ar) + " out of range, ignoring");
                boardRect = null;
            }
        }

        if (boardRect != null) {
            // Step 4: expand by half a piece (≈ a cell half ≈ w/18)
            int halfPiece = Math.max(boardRect.width / 18, 4);
            int x = Math.max(0, boardRect.x - halfPiece);
            int y = Math.max(0, boardRect.y - halfPiece);
            int bw = Math.min(rough.cols() - x, boardRect.width + 2 * halfPiece);
            int bh = Math.min(rough.rows() - y, boardRect.height + 2 * halfPiece);
            Mat roi = new Mat(rough, new Rect(x, y, bw, bh));
            Mat result = roi.clone();
            roi.release();
            rough.release();
            // Save board rect position within the cropped result for computeGrid
            int boardInCropX = boardRect.x - x;
            int boardInCropY = boardRect.y - y;
            sBoardRectInCropped = new Rect(boardInCropX, boardInCropY, boardRect.width, boardRect.height);
            log.info("cropBoardCenter: board rect found at " + boardRect.x + "," + boardRect.y + " " + boardRect.width + "x" + boardRect.height
                + ", final crop=" + result.cols() + "x" + result.rows()
                + ", boardInCrop=" + sBoardRectInCropped.x + "," + sBoardRectInCropped.y);
            return result;
        }

        // No board found – fall back to the rough-cropped image as-is
        sBoardRectInCropped = null;
        log.warning("cropBoardCenter: Board not detected, rough=" + rough.cols() + "x" + rough.rows() + " (orig=" + w + "x" + h + ")");
        return rough;
    }

    /**
     * Find the chess board rectangle using Canny edge detection + contour analysis.
     * Returns null if no suitable rectangle is found.
     */
    public static Rect findBoardRect(Mat src) {
        int h = src.rows();
        int w = src.cols();
        if (w <= 0 || h <= 0) return null;
        double minArea = w * h * 0.08;

        Mat gray = new Mat();
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);

        // Try Canny edge detection for cleaner contours
        Mat edges = new Mat();
        Imgproc.Canny(gray, edges, 50, 150);
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(edges, contours, hierarchy, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

        Rect bestRect = null;
        double bestScore = 0;

        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            double area = rect.area();
            if (area < minArea) continue;

            double ar = (double) rect.width / rect.height;
            // Board aspect is 9:10 ≈ 0.9; allow 0.5–1.6 for screenshots with padding
            if (ar < 0.5 || ar > 1.6) continue;

            // Score: 70% area fill (larger = better), 30% closeness to 0.9 aspect
            double areaScore = area / (w * h);
            double arScore = 1.0 - Math.abs(ar - 0.9) / 1.2;
            double score = areaScore * 0.7 + arScore * 0.3;

            if (score > bestScore) {
                bestScore = score;
                bestRect = rect;
            }
        }

        edges.release();
        hierarchy.release();
        for (MatOfPoint c : contours) c.release();

        // Also try OTSU approach as fallback
        if (bestRect == null || bestScore < 0.15) {
            if (bestRect != null) bestScore = 0; // too low, reset

            Mat blurred = new Mat();
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
            Mat binary = new Mat();
            Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);
            blurred.release();

            Mat kernel2 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9, 9));
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel2);

            List<MatOfPoint> contours2 = new ArrayList<>();
            Mat hierarchy2 = new Mat();
            Imgproc.findContours(binary, contours2, hierarchy2, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

            for (MatOfPoint contour : contours2) {
                Rect rect = Imgproc.boundingRect(contour);
                double area = rect.area();
                if (area < minArea) continue;

                double ar = (double) rect.width / rect.height;
                if (ar < 0.5 || ar > 1.6) continue;

                double areaScore = area / (w * h);
                double arScore = 1.0 - Math.abs(ar - 0.9) / 1.2;
                double score = areaScore * 0.7 + arScore * 0.3;

                if (score > bestScore) {
                    bestScore = score;
                    if (bestRect == null) bestRect = new Rect();
                    bestRect.x = rect.x;
                    bestRect.y = rect.y;
                    bestRect.width = rect.width;
                    bestRect.height = rect.height;
                }
            }

            binary.release();
            hierarchy2.release();
            for (MatOfPoint c : contours2) c.release();
        }
		gray.release();

        if (bestRect != null) {
            log.info("findBoardRect: best=" + bestRect.width + "x" + bestRect.height
                + " at (" + bestRect.x + "," + bestRect.y + ")" + " score=" + String.format("%.3f", bestScore));
        } else {
            log.warning("findBoardRect: no board rect found (w=" + w + " h=" + h + ")");
        }
        return (bestScore > 0.15) ? bestRect : null;
    }

    // ─── Grid computation (dynamic line detection only, no fallback) ─────

    /**
     * Compute a 10×9 grid by detecting the actual board lines.
     * Returns null if dynamic detection fails (no fallback).
     */
    public static Point[][] computeGrid(Mat croppedBoard) {
        Point[][] grid = detectGridFromLines(croppedBoard);
        if (grid != null) {
            log.info("computeGrid: detected from lines, cell="
                + String.format("%.1f", grid[1][0].y - grid[0][0].y));
        }
        return grid;
    }

    /**
     * Compute grid directly from the detected board rectangle.
     * Estimates the board border width from the board proportions so that
     * grid points land on intersection centers rather than board edges.
     *
     * Assuming square cells: boardRect.width  = 8·cell + 2·border
     *                       boardRect.height = 9·cell + 2·border
     * Solving: border = (9·width - 8·height) / 2
     */
    private static Point[][] computeGridFromBoardRect(Rect boardRect) {
        int border = Math.max(0, (9 * boardRect.width - 8 * boardRect.height) / 2);
        int maxBorder = Math.min(boardRect.width, boardRect.height) / 4;
        border = Math.min(border, maxBorder);

        double cellW = (double) (boardRect.width - 2 * border) / 8.0;
        double cellH = (double) (boardRect.height - 2 * border) / 9.0;
        double gridX = boardRect.x + border;
        double gridY = boardRect.y + border;

        Point[][] grid = new Point[10][9];
        for (int row = 0; row < 10; row++) {
            for (int col = 0; col < 9; col++) {
                grid[row][col] = new Point(gridX + col * cellW, gridY + row * cellH);
            }
        }
        return grid;
    }

    // ─── Dynamic grid line detection (morphological + projection) ────────

    /**
     * Detect grid lines using morphological extraction + projection.
     *
     * <ol>
     *   <li>OTSU binarization (white lines on black background)</li>
     *   <li>Erosion with a long rectangular kernel — removes circular pieces
     *       (≤ cellSize×0.65 diameter) while preserving long grid lines</li>
     *   <li>Row/column projection → peaks → grouping → chain detection</li>
     *   <li>Uniform extrapolation to fill in occluded/missing lines</li>
     * </ol>
     *
     * Compared to HoughLinesP, this is far more robust against piece
     * occlusion because the projection sum works even when pieces cover
     * a large fraction of each line.
     *
     * @param boardImage cropped board image (output of cropBoardCenter)
     * @return 10×9 grid, or null if detection or validation fails
     */
    public static Point[][] detectGridFromLines(Mat boardImage) {
        int h = boardImage.rows();
        int w = boardImage.cols();
        if (w <= 0 || h <= 0) return null;

        double cellSizeEst = Math.max((double) w / 9.0, (double) h / 10.0);
        if (cellSizeEst < 8.0) return null; // too small for meaningful detection

        // 1. Binarize: white grid lines on black background
        Mat binary = binarizeBoard(boardImage);
        if (binary == null) return null;

        // 2. Detect horizontal lines (expect 10)
        double[] hPos = detectUniformLines(binary, true, cellSizeEst, h, w);
        if (hPos == null) {
            binary.release();
            return null;
        }

        // 3. Detect vertical lines (expect 9)
        double[] vPos = detectUniformLines(binary, false, cellSizeEst, h, w);
        binary.release();
        if (vPos == null) return null;

        // 4. Build 10×9 grid
        Point[][] grid = new Point[10][9];
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                grid[r][c] = new Point(vPos[c], hPos[r]);
            }
        }

        // 5. Validate
        if (!validateGrid(grid, w, h)) {
            log.warning("detectGridFromLines: grid validation failed");
            return null;
        }

        log.info("detectGridFromLines: success — 10×9 grid, cell="
            + String.format("%.1f", grid[1][0].y - grid[0][0].y));
        return grid;
    }

    /** OTSU threshold and ensure grid lines are white on black. */
    private static Mat binarizeBoard(Mat boardImage) {
        Mat gray = new Mat();
        Imgproc.cvtColor(boardImage, gray, Imgproc.COLOR_BGR2GRAY);

        Mat binary = new Mat();
        Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
		gray.release();

        int total = binary.rows() * binary.cols();
        if (Core.countNonZero(binary) > total * 0.5) {
            Core.bitwise_not(binary, binary);
        }
        return binary;
    }

    /**
     * Detect N uniformly-spaced lines in one orientation.
     *
     * @param binary     binarized image (white lines on black)
     * @param horizontal true = detect horizontal rows, false = detect vertical columns
     * @param cellSize   estimated cell size in pixels
     * @param imgH       image height
     * @param imgW       image width
     * @return array of N line positions, or null if detection fails
     */
    private static double[] detectUniformLines(Mat binary, boolean horizontal,
                                                double cellSize, int imgH, int imgW) {
        int expectedCount = horizontal ? 10 : 9;
        int otherDim = horizontal ? imgW : imgH;

        // Build long rectangular kernel (80% of cell size) to erode through pieces
        int kLen = Math.max((int) (cellSize * 0.80), 3);
        Size kSize = horizontal ? new Size(kLen, 1) : new Size(1, kLen);
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, kSize);

        Mat lines = new Mat();
        Imgproc.erode(binary, lines, kernel);
        Imgproc.dilate(lines, lines, kernel);
        kernel.release();

        // Project: sum per row or per column
        int[] counts = horizontal
            ? computeRowProjection(lines)
            : computeColumnProjection(lines);
        lines.release();

        int threshold = (int) (otherDim * 0.15);
        int gap = Math.max((int) (cellSize * 0.10), 2);

        List<Integer> groups = extractGroups(counts, threshold, gap);
        if (groups == null || groups.size() < 5) {
            log.fine("detectUniformLines: too few groups (" + (groups != null ? groups.size() : 0)
                + ") after projection, threshold=" + threshold);
            return null;
        }

        int minChain = horizontal ? 7 : 6;
        List<Integer> chain = findBestChain(groups, cellSize, 0.20, minChain);
        if (chain == null || chain.size() < minChain) {
            log.fine("detectUniformLines: best chain too short ("
                + (chain != null ? chain.size() : 0) + "/" + expectedCount + ")");
            return null;
        }

        double center = horizontal ? imgH / 2.0 : imgW / 2.0;
        return chainToUniform(chain, expectedCount, center);
    }

    /**
     * Compute the projection (sum of non-zero pixels) for every row.
     * Returns int[rows], where each entry is the count for that row.
     */
    private static int[] computeRowProjection(Mat img) {
        int rows = img.rows();
        int cols = img.cols();
        int[] counts = new int[rows];
        byte[] rowBuf = new byte[cols];
        for (int y = 0; y < rows; y++) {
            img.get(y, 0, rowBuf);
            int sum = 0;
            for (int x = 0; x < cols; x++) {
                if ((rowBuf[x] & 0xFF) > 0) sum++;
            }
            counts[y] = sum;
        }
        return counts;
    }

    /**
     * Compute the projection (sum of non-zero pixels) for every column.
     * Returns int[cols], where each entry is the count for that column.
     */
    private static int[] computeColumnProjection(Mat img) {
        int rows = img.rows();
        int cols = img.cols();
        int[] counts = new int[cols];
        byte[] colBuf = new byte[rows];
        for (int x = 0; x < cols; x++) {
            img.col(x).get(0, 0, colBuf);
            int sum = 0;
            for (int y = 0; y < rows; y++) {
                if ((colBuf[y] & 0xFF) > 0) sum++;
            }
            counts[x] = sum;
        }
        return counts;
    }

    /**
     * Extract group center positions from a 1D projection array.
     * <p>
     * Finds all indices where {@code counts[i] > threshold}, groups consecutive
     * indices, and returns the center of each group. Two consecutive above-threshold
     * indices separated by more than {@code minGap} start a new group.
     *
     * @return sorted list of group center positions, or null if fewer than 5 groups
     */
    private static List<Integer> extractGroups(int[] counts, int threshold, int minGap) {
        List<Integer> above = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > threshold) above.add(i);
        }
        if (above.size() < 5) return null;

        List<Integer> groups = new ArrayList<>();
        int start = above.get(0);
        for (int i = 1; i < above.size(); i++) {
            if (above.get(i) - above.get(i - 1) > minGap) {
                groups.add((start + above.get(i - 1)) / 2);
                start = above.get(i);
            }
        }
        groups.add((start + above.get(above.size() - 1)) / 2);
        // Already in sorted order (above was scanned sequentially)
        return groups;
    }

    /**
     * Find the longest chain of equally-spaced lines from a list of candidate positions.
     * <p>
     * Starts from every adjacent pair whose spacing is within {@code maxErrRatio}
     * of {@code expectedSpacing}, extends forward and backward maintaining that
     * spacing, and returns the longest chain found.
     *
     * @param groups          sorted candidate line positions (pixel coordinates)
     * @param expectedSpacing expected distance between adjacent lines
     * @param maxErrRatio     maximum relative error allowed ({@code 0.20} = ±20%)
     * @param minCount        minimum chain length to be considered valid
     * @return sorted chain of line positions, or null if no chain meets minCount
     */
    private static List<Integer> findBestChain(List<Integer> groups,
                                                double expectedSpacing,
                                                double maxErrRatio,
                                                int minCount) {
        if (groups == null || groups.size() < 2) return null;

        int n = groups.size();
        List<Integer> bestChain = new ArrayList<>();
        double errPixels = expectedSpacing * maxErrRatio;

        for (int i = 0; i < n - 1; i++) {
            int d = groups.get(i + 1) - groups.get(i);
            if (Math.abs(d / expectedSpacing - 1.0) >= maxErrRatio) continue;

            // Seed chain with this pair
            List<Integer> chain = new ArrayList<>();
            chain.add(groups.get(i));
            chain.add(groups.get(i + 1));

            // Extend forward
            int expected = groups.get(i + 1) + d;
            for (int k = i + 2; k < n; k++) {
                if (Math.abs(groups.get(k) - expected) <= errPixels) {
                    chain.add(groups.get(k));
                    expected = groups.get(k) + d;
                } else if (groups.get(k) > expected + errPixels) {
                    break;
                }
            }

            // Extend backward
            expected = groups.get(i) - d;
            for (int k = i - 1; k >= 0; k--) {
                if (Math.abs(groups.get(k) - expected) <= errPixels) {
                    chain.add(0, groups.get(k));
                    expected = groups.get(k) - d;
                } else if (groups.get(k) < expected - errPixels) {
                    break;
                }
            }

            if (chain.size() > bestChain.size()) {
                bestChain = chain;
            }
        }

        if (bestChain.size() < minCount) return null;
        Collections.sort(bestChain);
        return bestChain;
    }

    /**
     * Extrapolate (or validate) a chain to exactly {@code expectedCount}
     * uniformly-spaced positions using the median spacing.
     * <p>
     * If the chain already has the expected count the positions are returned
     * unchanged. Otherwise the median spacing and the known center are used
     * to generate a full uniformly-spaced set.
     */
    private static double[] chainToUniform(List<Integer> chain,
                                            int expectedCount,
                                            double center) {
        int n = chain.size();

        // Compute median spacing
        int[] spacings = new int[n - 1];
        for (int i = 0; i < n - 1; i++) {
            spacings[i] = chain.get(i + 1) - chain.get(i);
        }
        Arrays.sort(spacings);
        double medianSpacing = spacings[spacings.length / 2];

        if (n == expectedCount) {
            double[] result = new double[expectedCount];
            for (int i = 0; i < n; i++) result[i] = chain.get(i);
            return result;
        }

        // Extrapolate from center
        double centerIdx = (expectedCount - 1) / 2.0;
        double origin = center - centerIdx * medianSpacing;

        double[] result = new double[expectedCount];
        for (int i = 0; i < expectedCount; i++) {
            result[i] = origin + i * medianSpacing;
        }
        return result;
    }

    /**
     * Validate a detected grid: all points within image bounds,
     * strictly increasing order, reasonable cell size uniformity.
     */
    private static boolean validateGrid(Point[][] grid, int imgW, int imgH) {
        if (grid.length != 10 || grid[0].length != 9) return false;

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Point p = grid[r][c];
                if (p.x < 0 || p.x >= imgW || p.y < 0 || p.y >= imgH) {
                    log.warning("validateGrid: point (" + r + "," + c + ") out of bounds");
                    return false;
                }
            }
        }

        // Rows must be strictly increasing
        for (int r = 1; r < 10; r++) {
            if (grid[r][0].y <= grid[r - 1][0].y) return false;
        }

        // Columns must be strictly increasing
        for (int c = 1; c < 9; c++) {
            if (grid[0][c].x <= grid[0][c - 1].x) return false;
        }

        // Cell sizes should be roughly uniform (max/min < 2.0)
        double cellH0 = grid[1][0].y - grid[0][0].y;
        double cellW0 = grid[0][1].x - grid[0][0].x;
        if (cellH0 <= 0 || cellW0 <= 0) return false;

        for (int r = 1; r < 10; r++) {
            double cellH = grid[r][0].y - grid[r - 1][0].y;
            if (cellH / cellH0 > 2.0 || cellH0 / cellH > 2.0) return false;
        }
        for (int c = 1; c < 9; c++) {
            double cellW = grid[0][c].x - grid[0][c - 1].x;
            if (cellW / cellW0 > 2.0 || cellW0 / cellW > 2.0) return false;
        }

        return true;
    }

    /**
     * Create a uniform 10×9 grid with generous margins.
     * Margins start at 10% and shrink if the crop window is too small.
     */
    private static Point[][] calibrateGridFallback(Rect boardRect) {
        double borderRatio = 0.10;
        int margin = (int) (Math.min(boardRect.width, boardRect.height) * borderRatio);
        int gridLeft = boardRect.x + margin;
        int gridTop = boardRect.y + margin;
        int gridWidth = boardRect.width - 2 * margin;
        int gridHeight = boardRect.height - 2 * margin;
        // Guard against negative or too-small dimensions
        if (gridWidth < 20 || gridHeight < 20) {
            borderRatio = 0.04;
            margin = (int) (Math.min(boardRect.width, boardRect.height) * borderRatio);
            gridLeft = boardRect.x + margin;
            gridTop = boardRect.y + margin;
            gridWidth = boardRect.width - 2 * margin;
            gridHeight = boardRect.height - 2 * margin;
        }
        double cellW = (double) gridWidth / 8.0;
        double cellH = (double) gridHeight / 9.0;
        Point[][] grid = new Point[10][9];
        for (int row = 0; row < 10; row++) {
            for (int col = 0; col < 9; col++) {
                grid[row][col] = new Point(gridLeft + col * cellW, gridTop + row * cellH);
            }
        }
        return grid;
    }

    // ─── Piece assignment ─────────────────────────────────────────────────

    public static String[][] assignPiecesToGrid(Map<Point, String> matchResult, Point[][] grid) {
        String[][] board = new String[10][9];
        double cellRadius = Math.max(
            grid[1][0].y - grid[0][0].y,
            grid[0][1].x - grid[0][0].x
        ) / 3.0;

        for (Map.Entry<Point, String> entry : matchResult.entrySet()) {
            Point matchPt = entry.getKey();
            String pieceName = entry.getValue();

            double bestDist = Double.MAX_VALUE;
            int bestRow = -1, bestCol = -1;
            for (int row = 0; row < 10; row++) {
                for (int col = 0; col < 9; col++) {
                    double dx = matchPt.x - grid[row][col].x;
                    double dy = matchPt.y - grid[row][col].y;
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestRow = row;
                        bestCol = col;
                    }
                }
            }

            if (bestDist <= cellRadius && bestRow >= 0 && bestCol >= 0 && board[bestRow][bestCol] == null) {
                board[bestRow][bestCol] = pieceName;
            }
        }

        return board;
    }

    // ─── Orientation ──────────────────────────────────────────────────────

    /**
     * 根据将帅在宫格中的位置判断棋盘方向
     * @param board 10×9 棋盘数组
     * @return true  红帅在图像下方（走子方为红方 "w"）
     *         false 黑将在图像下方（走子方为黑方 "b"）
     * @throws IllegalArgumentException 未同时检测到红帅和黑将
     */
    public static boolean isRedBottom(String[][] board) {
        int rkRow = -1, bkRow = -1;
        for (int r = 0; r < 10; r++) {
            if (r > 2 && r < 7) continue;
            for (int c = 3; c <= 5; c++) {
                String p = board[r][c];
                if (p == null) continue;
                if (p.equals("rk")) rkRow = r;
                else if (p.equals("bk")) bkRow = r;
            }
        }
        if (rkRow == -1 || bkRow == -1)
            throw new IllegalArgumentException("未同时检测到红帅和黑将");
        return rkRow > bkRow;
    }
}
