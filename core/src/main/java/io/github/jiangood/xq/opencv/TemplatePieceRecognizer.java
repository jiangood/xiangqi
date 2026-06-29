package io.github.jiangood.xq.opencv;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.logging.Logger;

public class TemplatePieceRecognizer implements PieceRecognizer {

    private static final Logger log = Logger.getLogger(TemplatePieceRecognizer.class.getName());
    private static final double MATCH_THRESHOLD = 0.6;

    private final CalibrationData calibrationData;
    private final File templateDir;
    private final Mat[] templateMats;
    private final String[] templateTypes;

    public TemplatePieceRecognizer(CalibrationData calibrationData, File templateDir) {
        this.calibrationData = calibrationData;
        this.templateDir = templateDir;
        int n = calibrationData.templates.size();
        this.templateMats = new Mat[n];
        this.templateTypes = new String[n];
        loadTemplates();
    }

    private void loadTemplates() {
        for (int i = 0; i < calibrationData.templates.size(); i++) {
            CalibrationTemplate t = calibrationData.templates.get(i);
            templateMats[i] = Imgcodecs.imread(
                new File(templateDir, t.filename).getAbsolutePath(),
                Imgcodecs.IMREAD_GRAYSCALE);
            templateTypes[i] = t.pieceType;
        }
        log.info("已加载 " + templateMats.length + " 个棋子模板");
    }

    @Override
    public String[][] parseBoard(String imageFile) throws Exception {
        log.info("加载图像: " + imageFile);
        Mat srcColor = Imgcodecs.imread(imageFile, Imgcodecs.IMREAD_COLOR);
        srcColor = BoardUtils.cropBoardCenter(srcColor);

        Mat srcGray = new Mat();
        Imgproc.cvtColor(srcColor, srcGray, Imgproc.COLOR_BGR2GRAY);

        Point[][] grid = calibrationData.grid;
        double pieceSize = calibrationData.pieceSize;
        String[][] board = new String[10][9];

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Point center = grid[r][c];
                int half = (int) (pieceSize / 2);
                int x = Math.max(0, Math.min((int) center.x - half, srcGray.cols() - (int) pieceSize));
                int y = Math.max(0, Math.min((int) center.y - half, srcGray.rows() - (int) pieceSize));
                int w = Math.min((int) pieceSize, srcGray.cols() - x);
                int h = Math.min((int) pieceSize, srcGray.rows() - y);

                if (w <= 0 || h <= 0) {
                    board[r][c] = null;
                    continue;
                }

                Rect roi = new Rect(x, y, w, h);
                Mat pieceRegion = new Mat(srcGray, roi);
                String bestType = null;
                double bestScore = MATCH_THRESHOLD;

                for (int t = 0; t < templateMats.length; t++) {
                    Mat tmpl = templateMats[t];
                    if (tmpl.empty()) continue;

                    Mat resizedTmpl = tmpl;
                    Mat tmp = null;
                    if (tmpl.width() != pieceRegion.width() || tmpl.height() != pieceRegion.height()) {
                        tmp = new Mat();
                        Imgproc.resize(tmpl, tmp, pieceRegion.size());
                        resizedTmpl = tmp;
                    }

                    Mat result = new Mat();
                    Imgproc.matchTemplate(pieceRegion, resizedTmpl, result, Imgproc.TM_CCOEFF_NORMED);
                    Core.MinMaxLocResult mm = Core.minMaxLoc(result);
                    result.release();

                    if (tmp != null) tmp.release();

                    if (mm.maxVal > bestScore) {
                        bestScore = mm.maxVal;
                        bestType = templateTypes[t];
                    }
                }

                pieceRegion.release();
                board[r][c] = bestType;
            }
        }

        srcGray.release();
        log.info("模板匹配完成，检测到 " + countPieces(board) + " 个棋子");
        return board;
    }

    private int countPieces(String[][] board) {
        int count = 0;
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                if (board[r][c] != null) count++;
        return count;
    }
}
