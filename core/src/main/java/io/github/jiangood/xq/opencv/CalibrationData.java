package io.github.jiangood.xq.opencv;

import org.opencv.core.Point;
import java.util.List;

public class CalibrationData {
    public int imageWidth;
    public int imageHeight;
    public double cellSize;
    public double pieceSize;
    public double pieceScale;
    public Point[][] grid;  // [10][9]
    public List<CalibrationTemplate> templates;

    public CalibrationData() {}
}
