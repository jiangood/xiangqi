package io.github.jiangood.xq.opencv;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

public class ChessboardRecognizer {

    private static final Logger log = Logger.getLogger(ChessboardRecognizer.class.getName());
    private static final double DEFAULT_THRESHOLD = 0.65;
    private static final double CLUSTER_TOLERANCE = 5.0;

    private static final Map<String, Double> PIECE_THRESHOLDS = new HashMap<>();
    static {
        PIECE_THRESHOLDS.put("rk", 0.70);
        PIECE_THRESHOLDS.put("bk", 0.70);
    }

    static {
        try {
            System.load(new File("lib/opencv_java4110.dll").getCanonicalPath());
        } catch (IOException e) {
            throw new RuntimeException("无法加载 OpenCV 原生库", e);
        }
    }

    private final Map<String, Mat> templateMatMap = new LinkedHashMap<>();

    public ChessboardRecognizer() {
        log.info("初始化opencv中...");
        File templateDir = new File("template");
        log.info("模板目录:" + templateDir + " 存在：" + FileUtil.exist(templateDir));

        File[] files = templateDir.listFiles();
        if (files != null) {
            for (File file : files) {
                log.info("文件 " + file.getAbsolutePath() + " " + file.length());
                Mat templateImage = Imgcodecs.imread(file.getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
                templateMatMap.put(FileUtil.mainName(file), templateImage);
            }
        }
        Assert.notEmpty(templateMatMap, "未加载到模板文件");
    }

    public String[][] parseBoard(String imageFile) throws Exception {
        log.info("加载图像: " + imageFile);
        Mat src = Imgcodecs.imread(imageFile, Imgcodecs.IMREAD_GRAYSCALE);

        // Step 1: Locate board adaptively
        Rect boardRect = locateBoard(src);
        log.info("棋盘外边框: " + boardRect);

        // Step 2: Full-matchTemplate on board region (proven approach)
        Mat boardRegion = new Mat(src, boardRect);
        Map<Point, String> matchResult = matchTemplate(boardRegion);
        log.info("模板匹配找到 " + matchResult.size() + " 个棋子");

        // Step 3: Calibrate grid using detected piece positions
        Point[][] calibratedGrid = calibrateGrid(matchResult, boardRect);
        log.info("自校准网格完成");

        // Step 4: Map each match to nearest calibrated grid intersection
        String[][] board = new String[10][9];
        double cellRadius = Math.max(calibratedGrid[1][0].y - calibratedGrid[0][0].y, calibratedGrid[0][1].x - calibratedGrid[0][0].x) / 3.0;
        log.info("匹配半径: " + String.format("%.1f", cellRadius));

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

    private Rect locateBoard(Mat src) {
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(src, blurred, new Size(5, 5), 0);

        Mat edges = new Mat();
        Imgproc.Canny(blurred, edges, 30, 100);

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
        Imgproc.dilate(edges, edges, kernel);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        log.info("locateBoard: 找到 " + contours.size() + " 个轮廓, 图片总像素 " + src.total());

        Rect largestRect = null;
        double largestArea = 0;

        for (int i = 0; i < contours.size(); i++) {
            MatOfPoint contour = contours.get(i);
            Rect rect = Imgproc.boundingRect(contour);
            double area = rect.area();
            double pct = area / src.total() * 100;
            log.info("  轮廓 #" + i + ": " + rect + " 面积=" + (int)area + " (" + String.format("%.1f", pct) + "%)");
            if (area > src.total() * 0.1 && area > largestArea) {
                largestArea = area;
                largestRect = rect;
            }
        }

        Assert.notNull(largestRect, "未能定位棋盘区域");
        return largestRect;
    }

    private Point[][] locateGridLines(Mat src, Rect boardRect) {
        log.info("棋盘区域尺寸: " + boardRect.width + "x" + boardRect.height);

        // Estimate border margin as ~5% of board size on each side
        double borderRatio = 0.05;
        int margin = (int) (Math.min(boardRect.width, boardRect.height) * borderRatio);
        int gridLeft = boardRect.x + margin;
        int gridTop = boardRect.y + margin;
        int gridWidth = boardRect.width - 2 * margin;
        int gridHeight = boardRect.height - 2 * margin;

        double cellW = (double) gridWidth / 8.0;  // 8 gaps between 9 columns
        double cellH = (double) gridHeight / 9.0; // 9 gaps between 10 rows

        log.info("网格估算: 边框=" + margin + "px, 单元格=" + String.format("%.1f", cellW) + "x" + String.format("%.1f", cellH));

        Point[][] grid = new Point[10][9];
        for (int row = 0; row < 10; row++) {
            for (int col = 0; col < 9; col++) {
                double x = gridLeft + col * cellW;
                double y = gridTop + row * cellH;
                grid[row][col] = new Point(x, y);
            }
        }

        return grid;
    }

    private Point[][] calibrateGrid(Map<Point, String> matches, Rect boardRect) {
        if (matches.size() < 16) {
            return locateGridLines(null, boardRect);
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

    private Map<Point, String> matchTemplate(Mat src) {
        Map<Point, String> map = new LinkedHashMap<>();
        templateMatMap.forEach((name, mat) -> {
            List<Point> points = matchTemplateSingle(src, mat);
            for (Point point : points) {
                map.put(point, name);
            }
        });
        return map;
    }

    private List<Point> matchTemplateSingle(Mat src, Mat templateImage) {
        Mat result = new Mat();
        Imgproc.matchTemplate(src, templateImage, result, Imgproc.TM_CCOEFF_NORMED);

        double threshold = DEFAULT_THRESHOLD;
        List<Point> matches = new ArrayList<>();

        for (int i = 0; i < result.rows(); i++) {
            for (int j = 0; j < result.cols(); j++) {
                double[] value = result.get(i, j);
                if (value != null && value[0] >= threshold) {
                    Point p = new Point(j, i);
                    matches.add(new Point(p.x + templateImage.cols() / 2.0, p.y + templateImage.height() / 2.0));
                }
            }
        }

        // Apply NMS: keep the best match per cluster
        if (matches.size() > 1) {
            List<Point> filtered = new ArrayList<>();
            boolean[] removed = new boolean[matches.size()];
            int templateSize = templateImage.cols(); // approximate cluster radius
            for (int i = 0; i < matches.size(); i++) {
                if (removed[i]) continue;
                filtered.add(matches.get(i));
                for (int j = i + 1; j < matches.size(); j++) {
                    if (removed[j]) continue;
                    double dx = matches.get(i).x - matches.get(j).x;
                    double dy = matches.get(i).y - matches.get(j).y;
                    if (Math.sqrt(dx * dx + dy * dy) < templateSize * 0.7) {
                        removed[j] = true;
                    }
                }
            }
            return filtered;
        }

        return matches;
    }
}
