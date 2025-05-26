package com.example.xq.cv;

import org.opencv.core.*;
import org.opencv.highgui.HighGui;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

public class CvUtil {

    static {
        // 加载OpenCV本地库
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    public static void main(String[] args) {
        // 加载图像
        Mat src = Imgcodecs.imread("test.jpg");






        // 2. 转换为灰度图
        Mat gray = new Mat();
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);

        Scalar color = new Scalar(0, 255, 0); // 绿色

        Imgproc.rectangle(src, new Rect(16,630, src.width()-16*2, 1790-630), color,4);





        // 显示结果
        HighGui.namedWindow("img", HighGui.WINDOW_NORMAL);
        HighGui.resizeWindow("img", 800, 900);

        HighGui.imshow("img", src);
        HighGui.waitKey(0);
        HighGui.destroyAllWindows();




    }
}
