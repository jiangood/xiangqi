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

    public static Mat cropBoardCenter(Mat src) {
        int h = src.rows();
        int w = src.cols();
        int cropH = (int) (w * 10.0 / 9.0);
        if (cropH >= h) {
            return src.clone();
        }
        int y = (h - cropH) / 2;
        Mat roi = new Mat(src, new Rect(0, y, w, cropH));
        Mat result = roi.clone();
        roi.release();
        return result;
    }

    public static Point[][] calibrateGrid(Map<Point, String> matches, Rect boardRect, Mat binaryBoard, int[] hChain, int[] vChain) {
        double bw = boardRect.width;
        double bh = boardRect.height;
        double centerX = boardRect.x + bw / 2.0;
        double centerY = boardRect.y + bh / 2.0;

        if (hChain != null && hChain.length >= 6) {
            double[] hUniform = chainToUniform(hChain, 10, centerY);

            double[] vUniform;
            if (vChain != null && vChain.length >= 4) {
                vUniform = chainToUniform(vChain, 9, centerX);
            } else {
                double cs_h = hUniform[1] - hUniform[0];
                vUniform = new double[9];
                for (int c = 0; c < 9; c++) {
                    vUniform[c] = centerX - 4 * cs_h + c * cs_h;
                }
            }

            Point[][] grid = new Point[10][9];
            for (int r = 0; r < 10; r++) {
                for (int c = 0; c < 9; c++) {
                    grid[r][c] = new Point(vUniform[c], hUniform[r]);
                }
            }
            return grid;
        }

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

    private static double[] chainToUniform(int[] chain, int expectedN, double knownCenter) {
        int n = chain.length;
        double[] spacings = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            spacings[i] = chain[i + 1] - chain[i];
        }
        Arrays.sort(spacings);
        double cs = spacings[spacings.length / 2];

        if (n == expectedN) {
            double[] result = new double[n];
            for (int i = 0; i < n; i++) result[i] = chain[i];
            return result;
        }

        double centerIdx = (expectedN - 1) / 2.0;
        double origin = knownCenter - centerIdx * cs;
        double[] result = new double[expectedN];
        for (int i = 0; i < expectedN; i++) {
            result[i] = origin + i * cs;
        }
        return result;
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
}
