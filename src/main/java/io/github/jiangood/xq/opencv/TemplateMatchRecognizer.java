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

public class TemplateMatchRecognizer implements PieceRecognizer {

    private static final Logger log = Logger.getLogger(TemplateMatchRecognizer.class.getName());

    static {
        try {
            System.load(new File("lib/opencv_java4110.dll").getCanonicalPath());
        } catch (IOException e) {
            throw new RuntimeException("无法加载 OpenCV 原生库", e);
        }
    }

    private final Map<String, Mat> templateMatMap = new LinkedHashMap<>();

    public TemplateMatchRecognizer() {
        log.info("初始化 TemplateMatchRecognizer...");
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

    @Override
    public String[][] parseBoard(String imageFile) throws Exception {
        log.info("加载图像: " + imageFile);
        Mat src = Imgcodecs.imread(imageFile, Imgcodecs.IMREAD_GRAYSCALE);

        Rect boardRect = BoardUtils.locateBoard(src);
        log.info("棋盘外边框: " + boardRect);

        Mat boardRegion = new Mat(src, boardRect);
        Map<Point, String> matchResult = matchTemplate(boardRegion);
        log.info("模板匹配找到 " + matchResult.size() + " 个棋子");

        Point[][] calibratedGrid = BoardUtils.calibrateGrid(matchResult, boardRect);
        log.info("自校准网格完成");

        BoardUtils.saveVisualization(imageFile, boardRect, matchResult, calibratedGrid);

        return BoardUtils.assignPiecesToGrid(matchResult, calibratedGrid, boardRect);
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

        double threshold = 0.65;
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

        // NMS
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
}
