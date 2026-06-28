package io.github.jiangood.xq.opencv;

import org.opencv.core.*;
import java.util.List;
import java.util.Map;

public class IntermediateResult {
    public Mat srcOriginal;
    public Mat srcGray;
    public Mat srcCanny;
    public Mat srcCannyDilated;
    public List<MatOfPoint> contours;
    public Rect boardRect;
    public Mat boardCropped;
    public Mat boardBinary;
    public int[] hLinePositions;
    public int[] vLinePositions;
    public double[] riverLine;
    public Point[][] grid;
    public Map<Point, String> rawDetections;
    public Map<Point, String> correctedDetections;
    public String boardFen;
    public String bestUciMove;
}
