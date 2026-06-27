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

public class TemplateMatchUtil {

    private static final Logger log = Logger.getLogger(TemplateMatchUtil.class.getName());
    private static final double THRESHOLD = 0.65;
    private static final Map<String, Mat> TEMPLATE_MAT_MAP = new LinkedHashMap<>();

    static {
        try {
            System.load(new File("lib/opencv_java4110.dll").getCanonicalPath());
        } catch (IOException e) {
            throw new RuntimeException("无法加载 OpenCV 原生库", e);
        }
        File templateDir = new File("template");
        File[] files = templateDir.listFiles();
        if (files != null) {
            for (File file : files) {
                Mat templateImage = Imgcodecs.imread(file.getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
                TEMPLATE_MAT_MAP.put(FileUtil.mainName(file), templateImage);
            }
        }
        Assert.notEmpty(TEMPLATE_MAT_MAP, "未加载到模板文件");
    }

    private TemplateMatchUtil() {}

    public static Map<Point, String> matchTemplate(Mat src) {
        Map<Point, String> map = new LinkedHashMap<>();
        TEMPLATE_MAT_MAP.forEach((name, mat) -> {
            List<Point> points = matchTemplateSingle(src, mat);
            for (Point point : points) {
                map.put(point, name);
            }
        });
        return map;
    }

    private static List<Point> matchTemplateSingle(Mat src, Mat templateImage) {
        Mat result = new Mat();
        Imgproc.matchTemplate(src, templateImage, result, Imgproc.TM_CCOEFF_NORMED);
        List<Point> matches = new ArrayList<>();
        for (int i = 0; i < result.rows(); i++) {
            for (int j = 0; j < result.cols(); j++) {
                double[] value = result.get(i, j);
                if (value != null && value[0] >= THRESHOLD) {
                    matches.add(new Point(j + templateImage.cols() / 2.0, i + templateImage.height() / 2.0));
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
}
