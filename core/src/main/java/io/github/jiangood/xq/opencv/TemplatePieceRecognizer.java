package io.github.jiangood.xq.opencv;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class TemplatePieceRecognizer implements PieceRecognizer {

    private static final Logger log = Logger.getLogger(TemplatePieceRecognizer.class.getName());
    private static final double MATCH_THRESHOLD = 0.65;

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

        // Sliding window: match each template across the entire board image,
        // then snap results to the calibration grid (same as v2.0 approach).
        Map<Point, String> allMatches = new LinkedHashMap<>();
        for (int t = 0; t < templateMats.length; t++) {
            Mat tmpl = templateMats[t];
            if (tmpl.empty()) continue;

            List<Point> matches = matchTemplateSliding(srcGray, tmpl, MATCH_THRESHOLD);
            for (Point p : matches) {
                allMatches.put(p, templateTypes[t]);
            }
        }

        // Snap all detections to the nearest calibration grid intersection
        String[][] board = BoardUtils.assignPiecesToGrid(allMatches, calibrationData.grid);

        srcGray.release();
        log.info("滑动窗口匹配完成，检测到 " + countPieces(board) + " 个棋子");
        return board;
    }

    private List<Point> matchTemplateSliding(Mat src, Mat template, double threshold) {
        Mat result = new Mat();
        Imgproc.matchTemplate(src, template, result, Imgproc.TM_CCOEFF_NORMED);

        List<Point> matches = new ArrayList<>();
        int cols = result.cols();
        int rows = result.rows();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                double score = result.get(y, x)[0];
                if (score >= threshold) {
                    matches.add(new Point(
                        x + template.cols() / 2.0,
                        y + template.height() / 2.0
                    ));
                }
            }
        }
        result.release();

        // Non-maximum suppression
        if (matches.size() <= 1) return matches;

        List<Point> filtered = new ArrayList<>();
        boolean[] removed = new boolean[matches.size()];
        int nmsDist = (template.cols() + template.height()) / 2;
        for (int i = 0; i < matches.size(); i++) {
            if (removed[i]) continue;
            filtered.add(matches.get(i));
            for (int j = i + 1; j < matches.size(); j++) {
                if (removed[j]) continue;
                double dx = matches.get(i).x - matches.get(j).x;
                double dy = matches.get(i).y - matches.get(j).y;
                if (Math.sqrt(dx * dx + dy * dy) < nmsDist * 0.7) {
                    removed[j] = true;
                }
            }
        }
        return filtered;
    }

    private int countPieces(String[][] board) {
        int count = 0;
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                if (board[r][c] != null) count++;
        return count;
    }
}
