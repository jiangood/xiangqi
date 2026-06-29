package io.github.jiangood.xq.opencv;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class BoardUtils {

    private static final Logger log = Logger.getLogger(BoardUtils.class.getName());

    public static Mat cropCenter(Mat src) {
        return cropCenter(src, 4.0 / 3.0);
    }

    public static Mat cropCenter(Mat src, double ratio) {
        int h = src.rows();
        int w = src.cols();
        if ((double) h / w <= ratio) {
            return src;
        }
        int cropH = (int) (w * ratio);
        int y = (h - cropH) / 2;
        return new Mat(src, new Rect(0, y, w, cropH));
    }

    public static Rect locateBoard(Mat src) {
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(src, blurred, new Size(5, 5), 0);

        Mat edges = new Mat();
        Imgproc.Canny(blurred, edges, 30, 100);

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
        Imgproc.dilate(edges, edges, kernel);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        log.info("locateBoard: " + contours.size() + " contours");

        Rect largestRect = null;
        double largestArea = 0;
        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            double area = rect.area();
            if (area > src.total() * 0.1 && area > largestArea) {
                largestArea = area;
                largestRect = rect;
            }
        }

        if (largestRect == null) {
            throw new RuntimeException("未能定位棋盘区域");
        }
        return largestRect;
    }

    /**
     * Backward-compatible calibrateGrid (no binary image available).
     */
    public static Point[][] calibrateGrid(Map<Point, String> matches, Rect boardRect) {
        return calibrateGrid(matches, boardRect, null);
    }

    /**
     * Calibrate grid using river detection (primary) or piece-based/geometric fallback.
     *
     * @param matches    piece detections (board-crop coordinates)
     * @param boardRect  board bounding rect in full-image coordinates
     * @param binaryBoard Otsu-thresholded board crop (1-channel binary), or null
     * @return 10x9 grid in full-image coordinates
     */
    public static Point[][] calibrateGrid(Map<Point, String> matches, Rect boardRect, Mat binaryBoard) {
        if (binaryBoard != null) {
            double cellSize = boardRect.width / 9.0;
            int[][] lines = detectGridLines(binaryBoard, cellSize);
            return calibrateGrid(matches, boardRect, binaryBoard, lines[0], lines[1]);
        }
        // Fallback: piece-based adaptive then geometric
        if (matches.size() >= 16) {
            return calibrateGridAdaptive(matches, boardRect);
        }
        return calibrateGridFallback(boardRect);
    }

    public static Point[][] calibrateGrid(Map<Point, String> matches, Rect boardRect, Mat binaryBoard, int[] hChain, int[] vChain) {
        double bw = boardRect.width, bh = boardRect.height;
        double cellSize = bw / 9.0;

        if (hChain != null && hChain.length >= 6) {
            double hCenter = (hChain[0] + hChain[hChain.length - 1]) / 2.0;
            double[] river = detectRiver(hChain, cellSize, hCenter);

            double cs_h;
            double originY;

            if (river != null) {
                double y4 = river[0], csRiver = river[2];
                cs_h = csRiver;
                originY = y4 - 4 * cs_h;
            } else {
                double[] hSpacings = new double[hChain.length - 1];
                for (int i = 0; i < hChain.length - 1; i++) {
                    hSpacings[i] = hChain[i + 1] - hChain[i];
                }
                Arrays.sort(hSpacings);
                cs_h = hSpacings[hSpacings.length / 2];
                originY = hCenter - 4.5 * cs_h;
            }

            double cs_w;
            double originX;

            if (vChain != null && vChain.length >= 4) {
                double[] vSpacings = new double[vChain.length - 1];
                for (int i = 0; i < vChain.length - 1; i++) {
                    vSpacings[i] = vChain[i + 1] - vChain[i];
                }
                Arrays.sort(vSpacings);
                cs_w = vSpacings[vSpacings.length / 2];

                int vFirst = Math.max(0, (int) (vChain[0] / cs_w + 0.3));
                double[] origins = new double[vChain.length];
                for (int i = 0; i < vChain.length; i++) {
                    origins[i] = vChain[i] - (vFirst + i) * cs_w;
                }
                Arrays.sort(origins);
                originX = origins[origins.length / 2];
            } else {
                cs_w = cs_h;
                originX = bw / 2.0 - 4 * cs_h;
            }

            Point[][] grid = new Point[10][9];
            for (int r = 0; r < 10; r++) {
                for (int c = 0; c < 9; c++) {
                    grid[r][c] = new Point(boardRect.x + originX + c * cs_w,
                                           boardRect.y + originY + r * cs_h);
                }
            }
            return grid;
        }

        // Fallback: piece-based adaptive then geometric
        if (matches.size() >= 16) {
            return calibrateGridAdaptive(matches, boardRect);
        }
        return calibrateGridFallback(boardRect);
    }

    private static Point[][] calibrateGridFallback(Rect boardRect) {
        double borderRatio = 0.05;
        int margin = (int) (Math.min(boardRect.width, boardRect.height) * borderRatio);
        int gridLeft = boardRect.x + margin;
        int gridTop = boardRect.y + margin;
        int gridWidth = boardRect.width - 2 * margin;
        int gridHeight = boardRect.height - 2 * margin;
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

    private static Point[][] calibrateGridAdaptive(Map<Point, String> matches, Rect boardRect) {
        double minY = Double.MAX_VALUE, maxY = -1;
        double minX = Double.MAX_VALUE, maxX = -1;
        for (Point p : matches.keySet()) {
            if (p.y < minY) minY = p.y;
            if (p.y > maxY) maxY = p.y;
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
        }
        double cellH = (maxY - minY) / 9.0;
        double cellW = (maxX - minX) / 8.0;
        Point[][] grid = new Point[10][9];
        for (int row = 0; row < 10; row++) {
            for (int col = 0; col < 9; col++) {
                grid[row][col] = new Point(boardRect.x + minX + col * cellW,
                                           boardRect.y + minY + row * cellH);
            }
        }
        return grid;
    }

    // ─── Grid line detection helpers ────────────────────────────────────────

    private static int[] extractGroups(int[] counts, int threshold, int gap) {
        List<Integer> above = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > threshold) above.add(i);
        }
        if (above.size() < 5) return null;

        List<Integer> groups = new ArrayList<>();
        int start = above.get(0);
        for (int i = 1; i < above.size(); i++) {
            if (above.get(i) - above.get(i - 1) > gap) {
                groups.add((start + above.get(i - 1)) / 2);
                start = above.get(i);
            }
        }
        groups.add((start + above.get(above.size() - 1)) / 2);

        int[] result = new int[groups.size()];
        for (int i = 0; i < groups.size(); i++) result[i] = groups.get(i);
        return result;
    }

    private static int[] findBestLineChain(int[] groups, double cellSize, double maxErr, int minChain) {
        if (groups == null || groups.length < 2) return null;

        List<Integer> bestChain = new ArrayList<>();
        int n = groups.length;

        for (int i = 0; i < n - 1; i++) {
            double d = groups[i + 1] - groups[i];
            if (Math.abs(d / cellSize - 1) >= maxErr) continue;

            List<Integer> chain = new ArrayList<>();
            chain.add(groups[i]);
            chain.add(groups[i + 1]);

            double expected = groups[i + 1] + d;
            for (int k = i + 2; k < n; k++) {
                if (Math.abs(groups[k] - expected) <= cellSize * maxErr) {
                    chain.add(groups[k]);
                    expected = groups[k] + d;
                } else if (groups[k] > expected + cellSize * maxErr) {
                    break;
                }
            }

            expected = groups[i] - d;
            for (int k = i - 1; k >= 0; k--) {
                if (Math.abs(groups[k] - expected) <= cellSize * maxErr) {
                    chain.add(0, groups[k]);
                    expected = groups[k] - d;
                } else if (groups[k] < expected - cellSize * maxErr) {
                    break;
                }
            }

            if (chain.size() > bestChain.size()) bestChain = chain;
        }

        if (bestChain.size() < minChain) return null;
        int[] result = new int[bestChain.size()];
        for (int i = 0; i < bestChain.size(); i++) result[i] = bestChain.get(i);
        return result;
    }

    public static int[][] detectGridLines(Mat binaryImg, double cellSize) {
        int h = binaryImg.rows();
        int w = binaryImg.cols();

        Mat fg;
        if (Core.countNonZero(binaryImg) > w * h * 0.5) {
            fg = new Mat();
            Core.bitwise_not(binaryImg, fg);
        } else {
            fg = binaryImg.clone();
        }

        int kLen = Math.max((int) (cellSize * 0.8), 1);
        int gap = (int) (cellSize * 0.1);
        int hTh = (int) (w * 0.15);

        Mat hKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(kLen, 1));
        Mat hLines = new Mat();
        Imgproc.erode(fg, hLines, hKernel);
        Imgproc.dilate(hLines, hLines, hKernel);

        int[] hCnt = new int[h];
        for (int y = 0; y < h; y++) {
            Mat rowMat = hLines.row(y);
            hCnt[y] = (int) Core.countNonZero(rowMat);
            rowMat.release();
        }

        int[] hGroups = extractGroups(hCnt, hTh, gap);
        int[] hChain = findBestLineChain(hGroups, cellSize, 0.2, 4);

        Mat vKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(1, kLen));
        Mat vLines = new Mat();
        Imgproc.erode(fg, vLines, vKernel);
        Imgproc.dilate(vLines, vLines, vKernel);

        int[] vCnt = new int[w];
        for (int x = 0; x < w; x++) {
            Mat colMat = vLines.col(x);
            vCnt[x] = (int) Core.countNonZero(colMat);
            colMat.release();
        }

        int vTh = (int) (h * 0.15);
        int[] vGroups = extractGroups(vCnt, vTh, gap);
        int[] vChain = findBestLineChain(vGroups, cellSize, 0.2, 4);

        fg.release();
        hLines.release();
        vLines.release();

        return new int[][]{hChain, vChain};
    }

    private static Integer estimateStartRow(int[] hChain, double cs) {
        int N = hChain.length;
        if (N == 10) return 0;
        double firstSp = hChain[1] - hChain[0];
        double lastSp = hChain[N - 1] - hChain[N - 2];
        double thresh = Math.max(cs * 0.88, cs - 15);
        boolean lastC = lastSp < thresh;
        boolean firstC = firstSp < thresh;

        if (N == 9) {
            if (lastC && !firstC) return 1;
            if (firstC && !lastC) return 0;
            return null;
        }
        if (N == 8) {
            if (lastC && !firstC) return 2;
            if (firstC && !lastC) return 0;
            return 1;
        }
        return Math.max(0, Math.min(10 - N, (int) Math.round((10 - N) / 2.0)));
    }

    public static double[] detectRiver(int[] hChain, double cellSize, double cropCenterY) {
        if (hChain == null || hChain.length < 6) return null;

        int N = hChain.length;
        double[] spacings = new double[N - 1];
        for (int i = 0; i < N - 1; i++) {
            spacings[i] = hChain[i + 1] - hChain[i];
        }
        double[] sorted = spacings.clone();
        Arrays.sort(sorted);
        double cs = sorted[sorted.length / 2];

        Integer R = estimateStartRow(hChain, cs);
        if (R != null) {
            int idx = 4 - R;
            if (idx >= 0 && idx < N - 1) {
                return new double[]{hChain[idx], hChain[idx + 1], hChain[idx + 1] - hChain[idx]};
            }
        }

        double bestScore = Double.MAX_VALUE;
        double[] bestPair = null;
        for (int i = 0; i < N - 1; i++) {
            double y1 = hChain[i], y2 = hChain[i + 1];
            double spacing = y2 - y1;
            double midpoint = (y1 + y2) / 2.0;
            double dist = Math.abs(midpoint - cropCenterY);
            double spacingDev = Math.abs(spacing / cs - 1);
            double score = dist / Math.max(cropCenterY, 1) + spacingDev;
            if (score < bestScore) {
                bestScore = score;
                bestPair = new double[]{y1, y2, spacing};
            }
        }
        return bestPair;
    }

    public static String[][] assignPiecesToGrid(Map<Point, String> matchResult, Point[][] calibratedGrid, Rect boardRect) {
        String[][] board = new String[10][9];
        double cellRadius = Math.max(
            calibratedGrid[1][0].y - calibratedGrid[0][0].y,
            calibratedGrid[0][1].x - calibratedGrid[0][0].x
        ) / 3.0;

        for (Map.Entry<Point, String> entry : matchResult.entrySet()) {
            Point matchPt = entry.getKey();
            String pieceName = entry.getValue();

            double bestDist = Double.MAX_VALUE;
            int bestRow = -1, bestCol = -1;
            for (int row = 0; row < 10; row++) {
                for (int col = 0; col < 9; col++) {
                    double dx = matchPt.x - (calibratedGrid[row][col].x - boardRect.x);
                    double dy = matchPt.y - (calibratedGrid[row][col].y - boardRect.y);
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

    public static Mat drawBoardRect(Mat src, Rect boardRect) {
        Mat output = src.clone();
        Imgproc.rectangle(output, boardRect.tl(), boardRect.br(), new Scalar(255, 0, 0), 3);
        return output;
    }

    public static Mat drawDetectionsOnly(Mat src, Rect boardRect, Map<Point, String> detections, Point[][] grid) {
        Mat output = src.clone();
        Imgproc.rectangle(output, boardRect.tl(), boardRect.br(), new Scalar(255, 0, 0), 2);

        double cellW = grid[0][1].x - grid[0][0].x;
        double cellH = grid[1][0].y - grid[0][0].y;
        for (Map.Entry<Point, String> e : detections.entrySet()) {
            Point pt = e.getKey();
            String name = e.getValue();
            Scalar color = name.startsWith("r") ? new Scalar(0, 0, 255) : new Scalar(0, 0, 0);
            double absX = boardRect.x + pt.x;
            double absY = boardRect.y + pt.y;
            double x1 = absX - cellW / 2;
            double y1 = absY - cellH / 2;
            double x2 = absX + cellW / 2;
            double y2 = absY + cellH / 2;
            Imgproc.rectangle(output, new Point(x1, y1), new Point(x2, y2), color, 2);
        }
        return output;
    }

    public static void drawMove(Mat image, Point[][] grid, String uciMove) {
        if (uciMove.length() != 4) return;
        int x1 = uciMove.charAt(0) - 'a';
        int y1 = uciMove.charAt(1) - '0';
        int x2 = uciMove.charAt(2) - 'a';
        int y2 = uciMove.charAt(3) - '0';
        if (x1 < 0 || x1 > 8 || y1 < 0 || y1 > 9) return;
        if (x2 < 0 || x2 > 8 || y2 < 0 || y2 > 9) return;
        Point from = grid[9 - y1][x1];
        Point to = grid[9 - y2][x2];
        Imgproc.arrowedLine(image, from, to, new Scalar(255, 255, 0), 3, Imgproc.LINE_AA, 0, 0.3);
    }

    public static Mat drawPreview(Mat src, Rect boardRect, Map<Point, String> detections, Point[][] grid) {
        Mat output = src.clone();
        Imgproc.rectangle(output, boardRect.tl(), boardRect.br(), new Scalar(255, 0, 0), 2);

        double cellW = grid[0][1].x - grid[0][0].x;
        double cellH = grid[1][0].y - grid[0][0].y;
        for (Map.Entry<Point, String> e : detections.entrySet()) {
            Point pt = e.getKey();
            String name = e.getValue();
            boolean isRed = name.startsWith("r");
            Scalar color = isRed ? new Scalar(0, 0, 255) : new Scalar(0, 0, 0);
            double absX = boardRect.x + pt.x;
            double absY = boardRect.y + pt.y;
            double x1 = absX - cellW / 2;
            double y1 = absY - cellH / 2;
            double x2 = absX + cellW / 2;
            double y2 = absY + cellH / 2;
            Imgproc.rectangle(output, new Point(x1, y1), new Point(x2, y2), color, 2);

            Point textOrg = new Point(x1, Math.max(y1 - 4, 0));
            Imgproc.putText(output, name, textOrg,
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, color, 2, Imgproc.LINE_AA, false);
        }
        return output;
    }

    public static Mat drawCropCenter(IntermediateResult ir) {
        if (ir == null || ir.srcOriginal == null) return new Mat();
        Mat output = ir.srcOriginal.clone();
        int h = ir.srcOriginal.rows();
        int w = ir.srcOriginal.cols();
        double ratio = 4.0 / 3.0;
        if ((double) h / w <= ratio) return output;
        int cropH = (int) (w * ratio);
        int y = (h - cropH) / 2;
        Imgproc.rectangle(output, new Point(0, y), new Point(w, y + cropH),
                new Scalar(255, 255, 0), 2, Imgproc.LINE_8, 0);
        return output;
    }

    public static Mat toBgr(Mat singleChannel) {
        Mat bgr = new Mat();
        Imgproc.cvtColor(singleChannel, bgr, Imgproc.COLOR_GRAY2BGR);
        return bgr;
    }

    public static Mat drawCanny(IntermediateResult ir) {
        if (ir == null || ir.srcCanny == null) return new Mat();
        return toBgr(ir.srcCanny);
    }

    public static Mat drawContours(IntermediateResult ir) {
        if (ir == null || ir.srcCannyDilated == null) return new Mat();
        Mat output = toBgr(ir.srcCannyDilated);
        if (ir.contours == null) return output;
        Scalar GREEN = new Scalar(0, 255, 0);
        Scalar BLUE = new Scalar(255, 0, 0);
        Rect largest = null;
        double largestArea = 0;
        for (int i = 0; i < ir.contours.size(); i++) {
            MatOfPoint contour = ir.contours.get(i);
            Imgproc.drawContours(output, ir.contours, i, GREEN, 1);
            Rect rect = Imgproc.boundingRect(contour);
            double area = rect.area();
            if (area > largestArea) {
                largestArea = area;
                largest = rect;
            }
        }
        if (largest != null) {
            Imgproc.rectangle(output, largest.tl(), largest.br(), BLUE, 2);
        }
        return output;
    }

    public static Mat drawHLines(IntermediateResult ir) {
        if (ir == null || ir.srcOriginal == null || ir.hLinePositions == null) return new Mat();
        Mat output = ir.srcOriginal.clone();
        Scalar RED = new Scalar(0, 0, 255);
        int w = output.width();
        for (int y : ir.hLinePositions) {
            Imgproc.line(output, new Point(0, y), new Point(w, y), RED, 2);
        }
        return output;
    }

    public static Mat drawVLines(IntermediateResult ir) {
        if (ir == null || ir.srcOriginal == null || ir.vLinePositions == null) return new Mat();
        Mat output = ir.srcOriginal.clone();
        Scalar RED = new Scalar(0, 0, 255);
        int h = output.height();
        for (int x : ir.vLinePositions) {
            Imgproc.line(output, new Point(x, 0), new Point(x, h), RED, 2);
        }
        return output;
    }

    public static Mat drawRiver(IntermediateResult ir) {
        if (ir == null || ir.srcOriginal == null || ir.riverLine == null) return new Mat();
        Mat output = ir.srcOriginal.clone();
        Scalar GREEN = new Scalar(0, 255, 0);
        int w = output.width();
        int y4 = (int) ir.riverLine[0];
        int y5 = (int) ir.riverLine[1];
        Imgproc.line(output, new Point(0, y4), new Point(w, y4), GREEN, 3);
        Imgproc.line(output, new Point(0, y5), new Point(w, y5), GREEN, 3);
        Imgproc.putText(output, "River", new Point(10, y4 - 8),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, GREEN, 2);
        Imgproc.putText(output, "River", new Point(w - 100, y5 + 30),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, GREEN, 2);
        return output;
    }

    public static Mat drawGridFull(IntermediateResult ir) {
        if (ir == null || ir.srcOriginal == null || ir.grid == null) return new Mat();
        Mat output = ir.srcOriginal.clone();
        Point[][] grid = ir.grid;
        Scalar RED = new Scalar(0, 0, 255);
        for (int r = 0; r < 10; r++) {
            Imgproc.line(output, grid[r][0], grid[r][8], RED, 2);
        }
        for (int c = 0; c < 9; c++) {
            Imgproc.line(output, grid[0][c], grid[4][c], RED, 2);
            Imgproc.line(output, grid[5][c], grid[9][c], RED, 2);
            if (c == 0 || c == 8) {
                Imgproc.line(output, grid[4][c], grid[5][c], RED, 2);
            }
        }
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Imgproc.drawMarker(output, grid[r][c], RED, Imgproc.MARKER_CROSS, 6, 1);
            }
        }
        return output;
    }

    public static Mat drawRawDetections(IntermediateResult ir) {
        if (ir == null || ir.srcOriginal == null || ir.rawDetections == null) return new Mat();
        if (ir.boardRect == null) return new Mat();
        Mat output = ir.srcOriginal.clone();
        double cellW = ir.grid != null ? ir.grid[0][1].x - ir.grid[0][0].x : 40;
        double cellH = ir.grid != null ? ir.grid[1][0].y - ir.grid[0][0].y : 40;
        for (Map.Entry<Point, String> e : ir.rawDetections.entrySet()) {
            Point pt = e.getKey();
            String name = e.getValue();
            double absX = ir.boardRect.x + pt.x;
            double absY = ir.boardRect.y + pt.y;
            double x1 = absX - cellW / 2;
            double y1 = absY - cellH / 2;
            double x2 = absX + cellW / 2;
            double y2 = absY + cellH / 2;
            Float score = ir.rawDetectionScores != null ? ir.rawDetectionScores.get(pt) : null;
            String label = score != null ? String.format("%s %.2f", name, score) : name;
            Imgproc.rectangle(output, new Point(x1, y1), new Point(x2, y2), new Scalar(128, 128, 128), 2);
            Imgproc.putText(output, label, new Point(x1, Math.max(y1 - 6, 0)),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, new Scalar(128, 128, 128), 1);
        }
        return output;
    }

    public static Mat drawColorCorrection(IntermediateResult ir) {
        if (ir == null || ir.srcOriginal == null || ir.correctedDetections == null) return new Mat();
        if (ir.boardRect == null) return new Mat();
        Mat output = ir.srcOriginal.clone();
        double cellW = ir.grid != null ? ir.grid[0][1].x - ir.grid[0][0].x : 40;
        double cellH = ir.grid != null ? ir.grid[1][0].y - ir.grid[0][0].y : 40;

        for (Map.Entry<Point, String> e : ir.correctedDetections.entrySet()) {
            Point pt = e.getKey();
            String name = e.getValue();
            double absX = ir.boardRect.x + pt.x;
            double absY = ir.boardRect.y + pt.y;
            double x1 = absX - cellW / 2;
            double y1 = absY - cellH / 2;
            double x2 = absX + cellW / 2;
            double y2 = absY + cellH / 2;

            String rawName = ir.rawDetections != null ? ir.rawDetections.get(pt) : null;
            boolean corrected = rawName != null && !rawName.equals(name);
            Scalar color = corrected ? new Scalar(0, 255, 255) :
                    (name.startsWith("r") ? new Scalar(0, 0, 255) : new Scalar(0, 0, 0));
            String label = corrected ? name + "*" : name;

            Imgproc.rectangle(output, new Point(x1, y1), new Point(x2, y2), color, 2);
            Imgproc.putText(output, label, new Point(x1, Math.max(y1 - 6, 0)),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, color, 1);
        }
        return output;
    }

    public static Mat drawPiecesSnapped(IntermediateResult ir) {
        if (ir == null || ir.srcOriginal == null || ir.grid == null || ir.correctedDetections == null || ir.boardRect == null) return new Mat();
        Mat output = ir.srcOriginal.clone();
        Point[][] grid = ir.grid;
        Scalar LIGHT = new Scalar(200, 200, 200);

        for (int r = 0; r < 10; r++) {
            Imgproc.line(output, grid[r][0], grid[r][8], LIGHT, 1);
        }
        for (int c = 0; c < 9; c++) {
            Imgproc.line(output, grid[0][c], grid[4][c], LIGHT, 1);
            Imgproc.line(output, grid[5][c], grid[9][c], LIGHT, 1);
            if (c == 0 || c == 8) {
                Imgproc.line(output, grid[4][c], grid[5][c], LIGHT, 1);
            }
        }

        String[][] board = assignPiecesToGrid(ir.correctedDetections, grid, ir.boardRect);
        if (board == null) return output;
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                String piece = board[r][c];
                if (piece == null) continue;
                Point pt = grid[r][c];
                boolean isRed = piece.startsWith("r");
                Scalar color = isRed ? new Scalar(0, 0, 255) : new Scalar(0, 0, 0);
                Imgproc.circle(output, pt, 8, color, -1);
                Imgproc.putText(output, piece, new Point(pt.x + 8, pt.y - 5),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, color, 1);
            }
        }
        return output;
    }

    public static Mat drawMoveArrow(IntermediateResult ir) {
        if (ir == null) return new Mat();
        Mat output = drawPiecesSnapped(ir);
        if (ir.bestUciMove != null && ir.bestUciMove.length() == 4 && ir.grid != null) {
            drawMove(output, ir.grid, ir.bestUciMove);
        }
        return output;
    }

    public static Mat drawAllDetections(IntermediateResult ir) {
        if (ir == null || ir.srcOriginal == null) return new Mat();
        if (ir.allDetections == null || ir.allDetections.isEmpty()) return ir.srcOriginal.clone();
        if (ir.boardRect == null) return new Mat();
        Mat output = ir.srcOriginal.clone();
        double cellW = ir.grid != null ? ir.grid[0][1].x - ir.grid[0][0].x : 40;
        double cellH = ir.grid != null ? ir.grid[1][0].y - ir.grid[0][0].y : 40;
        for (Map.Entry<Point, String> e : ir.allDetections.entrySet()) {
            Point pt = e.getKey();
            String name = e.getValue();
            double absX = ir.boardRect.x + pt.x;
            double absY = ir.boardRect.y + pt.y;
            double x1 = absX - cellW / 2;
            double y1 = absY - cellH / 2;
            double x2 = absX + cellW / 2;
            double y2 = absY + cellH / 2;
            boolean kept = ir.rawDetections.containsKey(pt);
            Scalar color = kept ? new Scalar(0, 200, 0) : new Scalar(0, 0, 255);
            Imgproc.rectangle(output, new Point(x1, y1), new Point(x2, y2), color, 2);
        }
        return output;
    }

    public static Rect computeRefinedRect(Point[][] grid, Rect boardRect) {
        if (grid == null) return boardRect;
        double cellH = grid[1][0].y - grid[0][0].y;
        double cellW = grid[0][1].x - grid[0][0].x;
        double cellSize = Math.max(cellH, cellW);
        int margin = Math.max((int)(cellSize * 0.5), 10);
        int x = (int)grid[0][0].x - margin;
        int y = (int)grid[0][0].y - margin;
        int right = (int)grid[9][8].x + margin;
        int bottom = (int)grid[9][8].y + margin;
        x = Math.max(boardRect.x, x);
        y = Math.max(boardRect.y, y);
        right = Math.min(boardRect.x + boardRect.width, right);
        bottom = Math.min(boardRect.y + boardRect.height, bottom);
        int w = Math.max(right - x, 1);
        int h = Math.max(bottom - y, 1);
        return new Rect(x, y, w, h);
    }

    public static Mat drawFenImage(String fen) {
        Mat img = new Mat(120, 1000, CvType.CV_8UC3, new Scalar(255, 255, 255));
        Imgproc.putText(img, "FEN: " + fen, new Point(20, 70),
                Imgproc.FONT_HERSHEY_SIMPLEX, 1.2, new Scalar(0, 0, 0), 2);
        return img;
    }

    public static Mat drawBoardLayout(String[][] board) {
        int cellSize = 64;
        int pad = 48;
        int w = 8 * cellSize + 2 * pad;
        int h = 9 * cellSize + 2 * pad;
        Mat img = new Mat(h, w, CvType.CV_8UC3, new Scalar(248, 222, 176));
        Scalar BLACK = new Scalar(0, 0, 0);

        // Horizontal lines
        for (int r = 0; r < 10; r++) {
            int y = pad + r * cellSize;
            Imgproc.line(img, new Point(pad, y), new Point(pad + 8 * cellSize, y), BLACK, 1);
        }
        // Vertical lines
        for (int c = 0; c < 9; c++) {
            int x = pad + c * cellSize;
            Imgproc.line(img, new Point(x, pad), new Point(x, pad + 4 * cellSize), BLACK, 1);
            Imgproc.line(img, new Point(x, pad + 5 * cellSize), new Point(x, pad + 9 * cellSize), BLACK, 1);
            if (c == 0 || c == 8) {
                Imgproc.line(img, new Point(x, pad + 4 * cellSize), new Point(x, pad + 5 * cellSize), BLACK, 1);
            }
        }

        // Pieces at intersections
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                String p = board[r][c];
                if (p == null || p.length() != 2) continue;
                int cx = pad + c * cellSize;
                int cy = pad + r * cellSize;
                boolean isRed = p.charAt(0) == 'r';
                Scalar color = isRed ? new Scalar(0, 0, 255) : new Scalar(0, 0, 0);
                String symbol = isRed ? String.valueOf(Character.toUpperCase(p.charAt(1)))
                                      : String.valueOf(Character.toLowerCase(p.charAt(1)));

                Imgproc.circle(img, new Point(cx, cy), cellSize / 2 - 4, new Scalar(255, 255, 255), -1);
                Imgproc.circle(img, new Point(cx, cy), cellSize / 2 - 4, color, 1);
                Imgproc.putText(img, symbol, new Point(cx - 8, cy + 8),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, color, 2);
            }
        }

        return img;
    }

    private static final java.util.Map<String, String> PIECE_CHINESE = java.util.Map.ofEntries(
        java.util.Map.entry("rk", "帅"), java.util.Map.entry("ra", "仕"),
        java.util.Map.entry("rb", "相"), java.util.Map.entry("rr", "車"),
        java.util.Map.entry("rn", "馬"), java.util.Map.entry("rc", "炮"),
        java.util.Map.entry("rp", "兵"), java.util.Map.entry("bk", "将"),
        java.util.Map.entry("ba", "士"), java.util.Map.entry("bb", "象"),
        java.util.Map.entry("br", "车"), java.util.Map.entry("bn", "马"),
        java.util.Map.entry("bc", "炮"), java.util.Map.entry("bp", "卒")
    );

    public static String detectionsToText(IntermediateResult ir) {
        if (ir.allDetections == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Point, String> e : ir.allDetections.entrySet()) {
            String name = e.getValue();
            Float score = ir.allDetectionScores != null ? ir.allDetectionScores.get(e.getKey()) : null;
            boolean kept = ir.rawDetections.containsKey(e.getKey());
            sb.append(kept ? "[KEPT] " : "[NMS]  ");
            sb.append(name);
            if (score != null) sb.append(String.format(" %.2f", score));
            sb.append("\n");
        }
        return sb.toString();
    }

    public static String boardToText(String[][] board) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < board.length; r++) {
            for (int c = 0; c < board[r].length; c++) {
                String p = board[r][c];
                sb.append(p != null ? PIECE_CHINESE.getOrDefault(p, p) : "＋");
                if (c < board[r].length - 1) sb.append(" ");
            }
            if (r < board.length - 1) sb.append("\n");
        }
        sb.append("\n\n红方: 帅 仕 相 車 馬 炮 兵\n");
        sb.append("黑方: 将 士 象 车 马 炮 卒");
        return sb.toString();
    }
}
