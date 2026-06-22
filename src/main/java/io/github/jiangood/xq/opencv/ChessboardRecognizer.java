package io.github.jiangood.xq.opencv;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class ChessboardRecognizer {

    private static final Logger log = Logger.getLogger(ChessboardRecognizer.class.getName());

    private Map<String, Mat> templateMatMap = new HashMap<>();

    static {
        try {
            System.load(new File("lib/opencv_java4110.dll").getCanonicalPath());
        } catch (IOException e) {
            throw new RuntimeException("无法加载 OpenCV 原生库", e);
        }
    }

    public ChessboardRecognizer() {
        log.info("初始化opencv中...");
        File template = new File("template");
        log.info("模板目录:" + template + " 存在：" + FileUtil.exist(template));

        for (File file : template.listFiles()) {
            log.info("文件 " + file.getAbsolutePath() + " " + file.length());
            Mat templateImage = Imgcodecs.imread(file.getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
            templateMatMap.put(FileUtil.mainName(file), templateImage);
        }
    }

    public String[][] parseBoard(String imageFile) throws Exception {
        log.info("加载图像: " + imageFile);
        Mat src = Imgcodecs.imread(imageFile, Imgcodecs.IMREAD_GRAYSCALE);

        Map<Point, String> matchResult = matchTemplate(src);
        log.info("匹配结果: " + matchResult);
        Assert.notEmpty(matchResult, "未匹配到结果");

        int baseX = 8;
        int baseY = 640;

        int blockWidth = (src.width() - 2 * baseX) / 9;
        int blockHeight = blockWidth - 5;
        String[][] board = new String[10][9];

        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 9; x++) {
                Rect rec = new Rect(baseX + x * blockWidth, baseY + y * blockHeight, blockWidth, blockHeight);
                for (Map.Entry<Point, String> e : matchResult.entrySet()) {
                    Point p = e.getKey();
                    String name = e.getValue();
                    if (p.inside(rec)) {
                        board[y][x] = name;
                    }
                }
            }
        }

        return board;
    }

    public Map<Point, String> matchTemplate(Mat src) throws Exception {
        Map<Point, String> map = new HashMap<>();
        templateMatMap.forEach((name, mat) -> {
            List<Point> points = matchTemplate(src, mat);
            for (Point point : points) {
                map.put(point, FileUtil.mainName(name));
            }
        });
        return map;
    }

    public List<Point> matchTemplate(Mat src, Mat templateImage) {
        long startTime = System.currentTimeMillis();
        Mat result = new Mat();

        Imgproc.matchTemplate(src, templateImage, result, Imgproc.TM_CCOEFF_NORMED);
        log.info("调用 Imgproc.matchTemplate 耗时 " + (System.currentTimeMillis() - startTime));

        startTime = System.currentTimeMillis();
        double threshold = 0.65;

        List<Point> matches = new ArrayList<>();

        for (int i = 0; i < result.rows(); i++) {
            for (int j = 0; j < result.cols(); j++) {
                double[] value = result.get(i, j);
                if (value != null && value[0] >= threshold) {
                    Point p = new Point(j, i);
                    matches.add(new Point(p.x + templateImage.cols() / 2, p.y + templateImage.height() / 2));
                }
            }
        }
        log.info("找出所有超过阈值的匹配位置 耗时 " + (System.currentTimeMillis() - startTime));

        return matches;
    }
}
