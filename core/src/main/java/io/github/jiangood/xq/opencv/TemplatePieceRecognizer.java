package io.github.jiangood.xq.opencv;

import io.github.jiangood.xq.util.FenUtil;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class TemplatePieceRecognizer implements PieceRecognizer {

    private static final Logger log = Logger.getLogger(TemplatePieceRecognizer.class.getName());
    private static final double MATCH_THRESHOLD = 0.50;

    private final Mat[] templateMats;
    private final String[] templateTypes;
    private Point[][] lastGrid;

    public TemplatePieceRecognizer(Mat[] templateMats, String[] templateTypes) {
        this.templateMats = templateMats;
        this.templateTypes = templateTypes;
        log.info("已加载 " + templateMats.length + " 个棋子模板");
    }

    public Point[][] getLastGrid() {
        return lastGrid;
    }

    @Override
    public String[][] parseBoard(String imageFile) throws Exception {
        log.info("加载图像: " + imageFile);
        Mat srcOrig = Imgcodecs.imread(imageFile, Imgcodecs.IMREAD_COLOR);
        if (srcOrig.empty()) throw new Exception("无法加载图片: " + imageFile);

        // Crop to board region (rough crop → contour detection → validation)
        Mat srcColor = BoardUtils.cropBoardCenter(srcOrig);
        if (srcColor != srcOrig) srcOrig.release();
        log.info("parseBoard: crop size = " + srcColor.cols() + "x" + srcColor.rows());

        // Compute grid (dynamic line detection, may be null)
        lastGrid = BoardUtils.computeGrid(srcColor);
        if (lastGrid == null) {
            srcColor.release();
            throw new Exception("无法检测棋盘网格线");
        }

        Mat srcGray = new Mat();
        Imgproc.cvtColor(srcColor, srcGray, Imgproc.COLOR_BGR2GRAY);

        // ── Per-grid-cell template competition ─────────────────────────
        //
        // For each grid intersection, run ALL 14 templates and pick the
        // one with the highest match score AT that exact position.
        // This avoids the "first-come-first-served" race condition where
        // a globally strong but wrong match claims a cell before the
        // correct template gets a chance.
        //
        // 1. Compute matchTemplate result maps for all templates
        // 2. For each grid cell, look up the score from every template
        //    at the grid point position → pick the best one

        List<Mat> resultMaps = new ArrayList<>();
        for (Mat tmpl : templateMats) {
            Mat result = new Mat();
            Imgproc.matchTemplate(srcGray, tmpl, result, Imgproc.TM_CCOEFF_NORMED);
            resultMaps.add(result);
        }

        String[][] board = new String[10][9];
        int detectedCount = 0;

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Point gp = lastGrid[r][c];
                double bestScore = MATCH_THRESHOLD;
                String bestType = null;

                // Try all 14 templates, searching a small window around the
                // grid point so a slight grid-offset doesn't miss the piece.
                int searchR = Math.max(lastGrid.length > 1
                    ? (int) ((lastGrid[1][0].y - lastGrid[0][0].y) * 0.25) : 3, 2);

                for (int t = 0; t < templateMats.length; t++) {
                    Mat tmpl = templateMats[t];
                    Mat result = resultMaps.get(t);

                    // Centre of the search window = grid point minus template centre offset
                    int cx = (int) (gp.x - tmpl.cols() / 2.0);
                    int cy = (int) (gp.y - tmpl.rows() / 2.0);

                    int x0 = Math.max(0, cx - searchR);
                    int y0 = Math.max(0, cy - searchR);
                    int x1 = Math.min(result.cols() - 1, cx + searchR);
                    int y1 = Math.min(result.rows() - 1, cy + searchR);

                    for (int yy = y0; yy <= y1; yy++) {
                        for (int xx = x0; xx <= x1; xx++) {
                            double score = result.get(yy, xx)[0];
                            if (score > bestScore) {
                                bestScore = score;
                                bestType = templateTypes[t];
                            }
                        }
                    }
                }

                if (bestType != null) {
                    board[r][c] = bestType;
                    detectedCount++;
                }
            }
        }

        for (Mat m : resultMaps) m.release();
        srcGray.release();
        srcColor.release();
        log.info("格子匹配完成，检测到 " + detectedCount + " 个棋子");
        return board;
    }

}
