package io.github.jiangood.xq.model.training;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 根据模板匹配方法，实现yolo训练模型标注
 */
public class YoloUtil {

    private static final Logger log = Logger.getLogger(YoloUtil.class.getName());
    private static final double DEFAULT_THRESHOLD = 0.65;

    private static final Map<String, Integer> PIECE_CLASS_IDS = new LinkedHashMap<>();
    static {
        PIECE_CLASS_IDS.put("rk", 0);
        PIECE_CLASS_IDS.put("ra", 1);
        PIECE_CLASS_IDS.put("rb", 2);
        PIECE_CLASS_IDS.put("rr", 3);
        PIECE_CLASS_IDS.put("rn", 4);
        PIECE_CLASS_IDS.put("rc", 5);
        PIECE_CLASS_IDS.put("rp", 6);
        PIECE_CLASS_IDS.put("bk", 7);
        PIECE_CLASS_IDS.put("ba", 8);
        PIECE_CLASS_IDS.put("bb", 9);
        PIECE_CLASS_IDS.put("br", 10);
        PIECE_CLASS_IDS.put("bn", 11);
        PIECE_CLASS_IDS.put("bc", 12);
        PIECE_CLASS_IDS.put("bp", 13);
    }

    static {
        try {
            System.load(new File("lib/opencv_java4110.dll").getCanonicalPath());
        } catch (IOException e) {
            throw new RuntimeException("无法加载 OpenCV 原生库", e);
        }
    }

    private final Map<String, Mat> templateMatMap = new LinkedHashMap<>();

    public YoloUtil() {
        File templateDir = new File("template");
        File[] files = templateDir.listFiles();
        if (files != null) {
            for (File file : files) {
                Mat templateImage = Imgcodecs.imread(file.getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
                templateMatMap.put(FileUtil.mainName(file), templateImage);
            }
        }
        Assert.notEmpty(templateMatMap, "未加载到模板文件");
    }

    public void processAll(String rawDir, String imageDir, String labelDir, String previewDir) throws Exception {
        processAll(rawDir, imageDir, labelDir, previewDir, 0.2);
    }

    public void processAll(String rawDir, String imageDir, String labelDir, String previewDir, double valRatio) throws Exception {
        List<File> imgList = new ArrayList<>();
        for (File f : FileUtil.loopFiles(new File(rawDir))) {
            if (f.isFile() && f.getName().matches("(?i).*\\.(jpg|jpeg|png|bmp)")) {
                imgList.add(f);
            }
        }
        if (imgList.isEmpty()) {
            log.warning(rawDir + " 中没有图片文件");
            return;
        }

        FileUtil.clean(new File(imageDir));
        FileUtil.clean(new File(labelDir));
        FileUtil.clean(new File(previewDir));

        Collections.shuffle(imgList, new Random(42));
        int splitIdx = (int) (imgList.size() * (1 - valRatio));
        List<File> trainList = imgList.subList(0, splitIdx);
        List<File> valList = imgList.subList(splitIdx, imgList.size());

        String trainImgDir = Paths.get(imageDir, "train").toString();
        String trainLabelDir = Paths.get(labelDir, "train").toString();
        String trainPreviewDir = Paths.get(previewDir, "train").toString();
        String valImgDir = Paths.get(imageDir, "val").toString();
        String valLabelDir = Paths.get(labelDir, "val").toString();
        String valPreviewDir = Paths.get(previewDir, "val").toString();

        new File(trainImgDir).mkdirs();
        new File(trainLabelDir).mkdirs();
        new File(trainPreviewDir).mkdirs();
        new File(valImgDir).mkdirs();
        new File(valLabelDir).mkdirs();
        new File(valPreviewDir).mkdirs();

        int nThreads = Math.min(Runtime.getRuntime().availableProcessors(), 6);
        ExecutorService executor = Executors.newFixedThreadPool(nThreads);
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);
        AtomicInteger seq = new AtomicInteger(0);

        for (File img : trainList) {
            File f = img;
            executor.submit(() -> {
                try {
                    processOne(f.getAbsolutePath(), trainImgDir, trainLabelDir, trainPreviewDir, String.format("%06d", seq.incrementAndGet()));
                    ok.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                    log.warning("处理失败(train): " + f.getName() + " - " + e.getMessage());
                }
            });
        }
        for (File img : valList) {
            File f = img;
            executor.submit(() -> {
                try {
                    processOne(f.getAbsolutePath(), valImgDir, valLabelDir, valPreviewDir, String.format("%06d", seq.incrementAndGet()));
                    ok.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                    log.warning("处理失败(val): " + f.getName() + " - " + e.getMessage());
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        log.info("全部完成: 成功 " + ok.get() + " / 失败 " + fail.get() + " / 共 " + imgList.size()
                + " (train " + trainList.size() + " / val " + valList.size() + ")");
    }

    public void processOne(String imagePath, String imageDir, String labelDir, String previewDir) throws Exception {
        processOne(imagePath, imageDir, labelDir, previewDir, FileUtil.mainName(new File(imagePath)));
    }

    public void processOne(String imagePath, String imageDir, String labelDir, String previewDir, String baseName) throws Exception {
        Mat srcColor = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_COLOR);
        Mat srcGray = new Mat();
        Imgproc.cvtColor(srcColor, srcGray, Imgproc.COLOR_BGR2GRAY);

        Rect boardRect = locateBoard(srcGray);
        log.info("棋盘区域: " + boardRect);

        Mat boardColor = new Mat(srcColor, boardRect);
        Mat boardGray = new Mat(srcGray, boardRect);
        Map<Point, String> matchResult = matchTemplate(boardGray);
        log.info("检测到 " + matchResult.size() + " 个棋子");

        // 先将棋盘二值化(Otsu)，再以3通道灰度保存→消除渲染风格差异、保留纯字形
        Imgproc.threshold(boardGray, boardGray, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        Mat boardGrayBgr = new Mat();
        Imgproc.cvtColor(boardGray, boardGrayBgr, Imgproc.COLOR_GRAY2BGR);

        Point[][] calibratedGrid = calibrateGrid(matchResult, boardRect);
        double cellW = calibratedGrid[0][1].x - calibratedGrid[0][0].x;
        double cellH = calibratedGrid[1][0].y - calibratedGrid[0][0].y;
        if (matchResult.size() < 16) {
            cellW = (double) boardRect.width / 9.0;
            cellH = (double) boardRect.height / 10.0;
        }

        double shrink = 1.0;
        String imgOut = Paths.get(imageDir, baseName + ".jpg").toString();
        Imgcodecs.imwrite(imgOut, boardGrayBgr);
        log.info("保存图片: " + imgOut);

        int boardW = boardRect.width;
        int boardH = boardRect.height;
        List<String> yoloLines = new ArrayList<>();
        for (Map.Entry<Point, String> entry : matchResult.entrySet()) {
            Point matchPt = entry.getKey();
            String pieceName = entry.getValue();
            int classId = PIECE_CLASS_IDS.getOrDefault(pieceName, -1);
            if (classId < 0) continue;

            double cx = matchPt.x / (double) boardW;
            double cy = matchPt.y / (double) boardH;
            double bw = cellW * shrink / (double) boardW;
            double bh = cellH * shrink / (double) boardH;

            yoloLines.add(String.format(Locale.US, "%d %.6f %.6f %.6f %.6f",
                    classId, cx, cy, bw, bh));
        }
        Path labelOut = Paths.get(labelDir, baseName + ".txt");
        Files.write(labelOut, yoloLines, StandardCharsets.UTF_8);
        log.info("保存标注: " + labelOut + " (" + yoloLines.size() + " 条)");

        Mat preview = boardGrayBgr.clone();
        for (Map.Entry<Point, String> entry : matchResult.entrySet()) {
            Point matchPt = entry.getKey();
            String pieceName = entry.getValue();
            Scalar color = pieceName.startsWith("r") ? new Scalar(0, 0, 255) : new Scalar(0, 0, 0);

            double x1 = matchPt.x - cellW * shrink / 2;
            double y1 = matchPt.y - cellH * shrink / 2;
            double x2 = matchPt.x + cellW * shrink / 2;
            double y2 = matchPt.y + cellH * shrink / 2;

            Rect roi = new Rect(new Point(x1, y1), new Point(x2, y2));
            Imgproc.rectangle(preview, roi.tl(), roi.br(), color, 2);
            Imgproc.putText(preview, pieceName,
                    new Point(x1, Math.max(y1 - 4, 0)),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, color, 2,
                    Imgproc.LINE_AA, false);
        }
        String previewOut = Paths.get(previewDir, baseName + ".jpg").toString();
        Imgcodecs.imwrite(previewOut, preview);
        log.info("保存预览: " + previewOut);
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
        Rect largestRect = null;
        double largestArea = 0;
        for (int i = 0; i < contours.size(); i++) {
            MatOfPoint contour = contours.get(i);
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

    private Point[][] calibrateGrid(Map<Point, String> matches, Rect boardRect) {
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
        List<Point> matches = new ArrayList<>();
        for (int i = 0; i < result.rows(); i++) {
            for (int j = 0; j < result.cols(); j++) {
                double[] value = result.get(i, j);
                if (value != null && value[0] >= DEFAULT_THRESHOLD) {
                    Point p = new Point(j, i);
                    matches.add(new Point(p.x + templateImage.cols() / 2.0, p.y + templateImage.height() / 2.0));
                }
            }
        }
        if (matches.size() > 1) {
            List<Point> filtered = new ArrayList<>();
            boolean[] removed = new boolean[matches.size()];
            int templateSize = templateImage.cols();
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

    public static void main(String[] args) throws Exception {
        String rawDir = "model-training/data/raw";
        String imageDir = "model-training/data/images";
        String labelDir = "model-training/data/labels";
        String previewDir = "model-training/data/preview";
        double valRatio = 0.01;

        if (args.length >= 1) rawDir = args[0];
        if (args.length >= 2) imageDir = args[1];
        if (args.length >= 3) labelDir = args[2];
        if (args.length >= 4) previewDir = args[3];
        if (args.length >= 5) valRatio = Double.parseDouble(args[4]);

        new YoloUtil().processAll(rawDir, imageDir, labelDir, previewDir, valRatio);
    }
}
