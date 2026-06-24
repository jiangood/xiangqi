package io.github.jiangood.xq.opencv;

import cn.hutool.core.lang.Assert;
import io.github.jiangood.xq.config.AppConfig;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class BoardUtils {

    private static final Logger log = Logger.getLogger(BoardUtils.class.getName());

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

        Assert.notNull(largestRect, "未能定位棋盘区域");
        return largestRect;
    }

    public static Point[][] calibrateGrid(Map<Point, String> matches, Rect boardRect) {
        if (matches.size() < 16) {
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
                grid[row][col] = new Point(boardRect.x + minX + col * cellW, boardRect.y + minY + row * cellH);
            }
        }
        return grid;
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

    public static void saveVisualization(String imagePath, Rect boardRect, Map<Point, String> detections, Point[][] grid) {
        if (!AppConfig.saveResultImage) return;
        File imgFile = new File(imagePath);
        File tmpDir = new File(imgFile.getParentFile(), "tmp");
        tmpDir.mkdirs();
        String baseName = imgFile.getName().replaceAll("(?i)\\.(jpg|jpeg|png|bmp)$", "") + "-result.jpg";
        String outPath = new File(tmpDir, baseName).getPath();
        Mat src = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_COLOR);

        Scalar gridColor = new Scalar(0, 200, 0);
        for (int row = 0; row < 10; row++) {
            for (int col = 0; col < 9; col++) {
                Point p = grid[row][col];
                Imgproc.drawMarker(src, p, gridColor, Imgproc.MARKER_CROSS, 10, 1);
            }
        }

        Imgproc.rectangle(src, boardRect.tl(), boardRect.br(), new Scalar(255, 0, 0), 2);

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
            Imgproc.rectangle(src, new Point(x1, y1), new Point(x2, y2), color, 2);
            Imgproc.putText(src, name, new Point(x1, Math.max(y1 - 4, 0)),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, color, 2, Imgproc.LINE_AA, false);
        }

        Imgcodecs.imwrite(outPath, src);
        log.info("识别结果图已保存: " + outPath);
    }
}
