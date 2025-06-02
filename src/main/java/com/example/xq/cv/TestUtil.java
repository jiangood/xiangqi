package com.example.xq.cv;

import cn.hutool.core.util.URLUtil;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.highgui.HighGui;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;

public class TestUtil {

    static {
        // 加载OpenCV本地库
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }


    public static void main(String[] args) {
        // 加载图像
        Mat src = Imgcodecs.imread("test.jpg");

        int x = 8;
        int y = 640;


        Scalar color = new Scalar(0, 255, 0); // 绿色

        int w = (src.width() -2*x)/ 9;
        int h =w-5;
        for (int i = 0; i < 9; i++){
            for(int j= 0; j < 10; j++){
                Rect rec = new Rect(x + i * w, y + j * h, w, h);
                Imgproc.rectangle(src, rec, color,2);
                // 保存模板
              //  Imgcodecs.imwrite("img" + i +j + ".jpg", new Mat(src, rec));



            }
        }

        File dir = new File("template");
        for (File file : dir.listFiles()) {
            System.out.println(file);
            String absolutePath = file.getAbsolutePath();
            absolutePath = URLUtil.decode(absolutePath, "UTF-8");

            Mat templateImage = Imgcodecs.imread(absolutePath, Imgcodecs.IMREAD_COLOR);

            Mat result = new Mat();
            Imgproc.matchTemplate(src, templateImage, result, Imgproc.TM_CCOEFF_NORMED);
            System.out.println(result);

        }


        // 显示结果
        HighGui.namedWindow("img", HighGui.WINDOW_NORMAL);
        HighGui.resizeWindow("img", 800, 900);

        HighGui.imshow("img", src);
        HighGui.waitKey(0);
        HighGui.destroyAllWindows();




    }


}
