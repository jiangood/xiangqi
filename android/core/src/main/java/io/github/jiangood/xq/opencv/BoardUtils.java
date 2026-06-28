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
        double bw = boardRect.width, bh = boardRect.height;
        double cellSize = bw / 9.0;

        if (binaryBoard != null) {
            int[][] lines = detectGridLines(binaryBoard, cellSize);
            int[] hChain = lines[0];
            int[] vChain = lines[1];

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
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, color, 2, Imgproc.LINE_AA, false);
        }
        return output;
    }
}
