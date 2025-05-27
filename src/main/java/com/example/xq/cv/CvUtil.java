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

        Rect roi = new Rect(16, 630, src.width() - 16 * 2, 1790 - 630);

        Mat result = new Mat(src, roi);




        Scalar color = new Scalar(0, 255, 0); // 绿色

        int w = result.width() / 9;
        int h =w;
        for (int i = 0; i < 9; i++){
            for(int j= 0; j < 10; j++){
                Imgproc.rectangle(result, new Rect(i * w + w/2, j * h + h/2, w, h  ), color,2);
            }
        }




        // 显示结果
        HighGui.namedWindow("img", HighGui.WINDOW_NORMAL);
        HighGui.resizeWindow("img", 800, 900);

        HighGui.imshow("img", result);
        HighGui.waitKey(0);
        HighGui.destroyAllWindows();




    }
}
