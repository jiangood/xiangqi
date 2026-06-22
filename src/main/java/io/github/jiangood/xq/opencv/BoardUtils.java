package io.github.jiangood.xq.opencv;

import cn.hutool.core.lang.Assert;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

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
}
