package io.github.jiangood.xq.opencv;

import io.github.jiangood.xq.util.FenUtil;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class TemplatePieceRecognizer implements PieceRecognizer {

    private static final Logger log = Logger.getLogger(TemplatePieceRecognizer.class.getName());
    private static final double MATCH_THRESHOLD = 0.65;

    private static class MatchResult implements Comparable<MatchResult> {
        Point point;
        double score;
        String pieceType;

        MatchResult(Point p, double s, String t) {
            point = p; score = s; pieceType = t;
        }

        @Override
        public int compareTo(MatchResult o) {
            return Double.compare(o.score, this.score);
        }
    }

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

        // Peak detection: for each template, find peaks via repeated minMaxLoc,
        // then score-sort all matches across templates so high-confidence wins.
        List<MatchResult> allMatches = new ArrayList<>();
        for (int t = 0; t < templateMats.length; t++) {
            Mat tmpl = templateMats[t];
            if (tmpl.empty()) continue;
            allMatches.addAll(matchTemplatePeak(srcGray, tmpl, templateTypes[t]));
        }

        Collections.sort(allMatches);
        Map<Point, String> matchMap = new LinkedHashMap<>();
        for (MatchResult m : allMatches) {
            matchMap.put(m.point, m.pieceType);
        }

        // Snap all detections to the nearest calibration grid intersection
        String[][] board = BoardUtils.assignPiecesToGrid(matchMap, calibrationData.grid);

        srcGray.release();
        log.info("峰值匹配完成，检测到 " + FenUtil.countPieces(board) + " 个棋子");
        return board;
    }

    private List<MatchResult> matchTemplatePeak(Mat src, Mat template, String type) {
        Mat result = new Mat();
        Imgproc.matchTemplate(src, template, result, Imgproc.TM_CCOEFF_NORMED);

        List<MatchResult> matches = new ArrayList<>();
        int h = result.rows(), w = result.cols();
        int supR = Math.max(template.cols(), template.height()) / 2;

        while (true) {
            Core.MinMaxLocResult mm = Core.minMaxLoc(result);
            if (mm.maxVal < MATCH_THRESHOLD) break;

            matches.add(new MatchResult(
                new Point(mm.maxLoc.x + template.cols() / 2.0,
                          mm.maxLoc.y + template.height() / 2.0),
                mm.maxVal, type));

            int x0 = Math.max(0, (int) mm.maxLoc.x - supR);
            int y0 = Math.max(0, (int) mm.maxLoc.y - supR);
            int x1 = Math.min(w, (int) mm.maxLoc.x + supR);
            int y1 = Math.min(h, (int) mm.maxLoc.y + supR);
            result.submat(y0, y1, x0, x1).setTo(new Scalar(-1));
        }
        result.release();
        return matches;
    }

}
