package com.example.xq.cv;

import cn.hutool.core.io.FileUtil;
import org.opencv.core.*;
import org.opencv.highgui.HighGui;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CvUtil {

    static {
        // 加载OpenCV本地库
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }


    public static void main(String[] args) {
        // 加载图像
        Mat src = Imgcodecs.imread("test.jpg");

        Map<Point, String> matchResult = matchTemplate(src);

        int x = 8;
        int y = 640;


        Scalar color = new Scalar(0, 255, 0); // 绿色

        int w = (src.width() - 2 * x) / 9;
        int h = w - 5;
        String[][] arr = new String[9][10];
        for (int i = 0; i < 9; i++) {
            for (int j = 0; j < 10; j++) {
                Rect rec = new Rect(x + i * w, y + j * h, w, h);
                //   Imgproc.rectangle(src, rec, color, 2);
                // 保存模板
                //  Imgcodecs.imwrite("img" + i +j + ".jpg", new Mat(src, rec));

                for (Map.Entry<Point, String> e : matchResult.entrySet()) {
                    Point p = e.getKey();
                    String name = e.getValue();
                    if (p.inside(rec)) {
                        arr[i][j] = name;
                        Imgproc.rectangle(src, rec, color, 2);
                    }
                }
            }
        }


        // 显示结果
        HighGui.namedWindow("img", HighGui.WINDOW_NORMAL);
        HighGui.resizeWindow("img", 800, 900);

        HighGui.imshow("img", src);
        HighGui.waitKey(0);
        HighGui.destroyAllWindows();


    }

    public static Map<Point, String> matchTemplate(Mat src) {
        Map<Point, String> map = new HashMap<>();
        File template = new File("template");
        for (File file : template.listFiles()) {
            List<Point> points = matchTemplate(src, file);
            for (Point point : points) {
                map.put(point, FileUtil.mainName(file));
            }
        }
        return map;
    }

    public static List<Point> matchTemplate(Mat src, File templateFIle) {


        File tempFile = FileUtil.createTempFile(); // 中文不可读


        FileUtil.copy(templateFIle.getAbsolutePath(), tempFile.getAbsolutePath(), true);

        Mat templateImage = Imgcodecs.imread(tempFile.getAbsolutePath(), Imgcodecs.IMREAD_COLOR);


        Mat result = new Mat();
        Imgproc.matchTemplate(src, templateImage, result, Imgproc.TM_CCOEFF_NORMED);

        // 设置匹配阈值（0-1之间，越接近1要求越严格）
        double threshold = 0.7;

        // 存储所有匹配位置
        List<Point> matches = new ArrayList<>();

        // 找出所有超过阈值的匹配位置
        for (int i = 0; i < result.rows(); i++) {
            for (int j = 0; j < result.cols(); j++) {
                double[] value = result.get(i, j);
                if (value != null && value[0] >= threshold) {
                    Point p = new Point(j, i);


                    // 输出所有匹配位置
                    System.out.println(FileUtil.mainName(templateFIle) + " 找到 " + matches.size() + " 个匹配项：");
                    System.out.println("坐标: (" + p.x + ", " + p.y + ")");

                    // 在主图上绘制矩形标记匹配位置
                 /*   Imgproc.rectangle(src, p,
                            new Point(p.x + templateImage.cols(), p.y + templateImage.rows()),
                            new Scalar(0, 0, 255), 2);*/

                    matches.add(new Point(p.x + templateImage.cols() /2, p.y+ templateImage.height() /2));
                }
            }
        }


        FileUtil.del(tempFile);


        return matches;
    }


}
