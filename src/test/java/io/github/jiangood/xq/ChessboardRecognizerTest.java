package io.github.jiangood.xq;

import ai.onnxruntime.*;
import cn.hutool.core.lang.Assert;
import io.github.jiangood.xq.opencv.BoardUtils;
import io.github.jiangood.xq.opencv.PieceRecognizer;
import io.github.jiangood.xq.opencv.TemplateMatchRecognizer;
import io.github.jiangood.xq.opencv.YoloPieceRecognizer;
import io.github.jiangood.xq.util.FenUtil;
import org.junit.jupiter.api.Test;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.util.*;

public class ChessboardRecognizerTest {

    public static final String BASE_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w";

    private void assertRecognizer(PieceRecognizer recognizer, String imagePath, String expectedFen) throws Exception {
        String[][] board = recognizer.parseBoard(imagePath);
        String fen = FenUtil.toFen(board);
        Assert.state(fen.equals(expectedFen), "Expected: " + expectedFen + " but got: " + fen);
    }

    @Test
    public void testTemplateMatch_base2() throws Exception {
        assertRecognizer(new TemplateMatchRecognizer(), "demos/base-2.jpg", BASE_FEN);
    }

    @Test
    public void testTemplateMatch_last2() throws Exception {
        assertRecognizer(new TemplateMatchRecognizer(), "demos/last-2.jpg",
                "2ba1k3/4a4/4b4/pr7/9/9/P7P/4Br3/3KR4/9 w");
    }

    @Test
    public void testTemplateMatch_last2Changed() throws Exception {
        assertRecognizer(new TemplateMatchRecognizer(), "demos/last-2-changed.jpg",
                "2ba1k3/4a4/4b4/pr7/9/9/P7P/4Br3/3KR4/9 w");
    }

    @Test
    public void testYolo_base2() throws Exception {
        assertRecognizer(new YoloPieceRecognizer("models/xiangqi_yolo.onnx"), "demos/base-2.jpg", BASE_FEN);
    }

    @Test
    public void testYolo_last2() throws Exception {
        assertRecognizer(new YoloPieceRecognizer("models/xiangqi_yolo.onnx"), "demos/last-2.jpg",
                "2ba1k3/4a4/4b4/pr7/9/9/P7P/4Br3/3KR4/9 w");
    }

    @Test
    public void testYolo_last2Changed() throws Exception {
        assertRecognizer(new YoloPieceRecognizer("models/xiangqi_yolo.onnx"), "demos/last-2-changed.jpg",
                "2ba1k3/4a4/4b4/pr7/9/9/P7P/4Br3/3KR4/9 w");
    }

    @Test
    public void testYolo_base1() throws Exception {
        YoloPieceRecognizer yolo = new YoloPieceRecognizer("models/xiangqi_yolo.onnx");
        String[][] board = yolo.parseBoard("demos/base-1.jpg");
        String fen = FenUtil.toFen(board);
        System.out.println("base-1.jpg FEN: " + fen);

        Mat src = Imgcodecs.imread("demos/base-1.jpg", Imgcodecs.IMREAD_COLOR);
        Mat gray = new Mat();
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);

        // draw board rect
        Rect boardRect = BoardUtils.locateBoard(gray);
        Imgproc.rectangle(src, boardRect, new Scalar(0, 255, 0), 3);

        // draw YOLO raw detections (re-run inference manually to get bbox)
        String modelPath = "models/xiangqi_yolo.onnx";
        int inputSize = 1280;
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(1);
        OrtSession session = env.createSession(modelPath, opts);
        int origW = src.width(), origH = src.height();
        float scale = Math.min((float) inputSize / origW, (float) inputSize / origH);
        int newW = Math.round(origW * scale), newH = Math.round(origH * scale);
        int padW = (inputSize - newW) / 2, padH = (inputSize - newH) / 2;
        int padWRest = (inputSize - newW) % 2, padHRest = (inputSize - newH) % 2;
        Mat resized = new Mat();
        Imgproc.resize(src, resized, new Size(newW, newH));
        Mat padded = new Mat();
        Core.copyMakeBorder(resized, padded, padH, padH + padHRest, padW, padW + padWRest,
                Core.BORDER_CONSTANT, new Scalar(114, 114, 114));
        Imgproc.cvtColor(padded, padded, Imgproc.COLOR_BGR2RGB);
        float[] inputData = new float[3 * inputSize * inputSize];
        for (int c = 0; c < 3; c++)
            for (int y = 0; y < inputSize; y++)
                for (int x = 0; x < inputSize; x++) {
                    double[] pixel = padded.get(y, x);
                    inputData[c * inputSize * inputSize + y * inputSize + x] = (float) (pixel[c] / 255.0);
                }
        OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(inputData), new long[]{1, 3, inputSize, inputSize});
        Map<String, OnnxTensor> inputs = Collections.singletonMap(
                session.getInputNames().iterator().next(), inputTensor);
        OrtSession.Result ortResults = session.run(inputs);
        OnnxTensor outputTensor = (OnnxTensor) ortResults.get(0);
        float[][][] output = (float[][][]) outputTensor.getValue();
        float[][] preds = output[0];
        int numPreds = preds[0].length;
        String[] classNames = {"rk","ra","rb","rr","rn","rc","rp","bk","ba","bb","br","bn","bc","bp"};
        Scalar[] colors = {
            new Scalar(0,0,255), new Scalar(0,100,255), new Scalar(0,200,200),
            new Scalar(0,255,0), new Scalar(100,200,100), new Scalar(200,200,0), new Scalar(50,50,255),
            new Scalar(255,0,0), new Scalar(255,100,0), new Scalar(200,200,0),
            new Scalar(255,0,255), new Scalar(200,100,200), new Scalar(0,200,200), new Scalar(255,50,50)
        };
        List<float[]> dets = new ArrayList<>();
        for (int i = 0; i < numPreds; i++) {
            float maxScore = 0; int classId = -1;
            for (int j = 0; j < 14; j++) {
                float score = preds[4 + j][i];
                if (score > maxScore) { maxScore = score; classId = j; }
            }
            if (maxScore < 0.25f) continue;
            float cx = preds[0][i], cy = preds[1][i], w = preds[2][i], h = preds[3][i];
            // map back to original image coords
            float x1 = (cx - w/2 - padW) / scale;
            float y1 = (cy - h/2 - padH) / scale;
            float x2 = (cx + w/2 - padW) / scale;
            float y2 = (cy + h/2 - padH) / scale;
            x1 = Math.max(0, Math.min(origW, x1));
            y1 = Math.max(0, Math.min(origH, y1));
            x2 = Math.max(0, Math.min(origW, x2));
            y2 = Math.max(0, Math.min(origH, y2));
            dets.add(new float[]{x1, y1, x2, y2, maxScore, classId});
        }
        // NMS
        dets.sort((a,b) -> Float.compare(b[4], a[4]));
        List<float[]> kept = new ArrayList<>();
        for (float[] d : dets) {
            boolean overlap = false;
            for (float[] k : kept) {
                float ix1 = Math.max(d[0], k[0]), iy1 = Math.max(d[1], k[1]);
                float ix2 = Math.min(d[2], k[2]), iy2 = Math.min(d[3], k[3]);
                float inter = Math.max(0, ix2-ix1) * Math.max(0, iy2-iy1);
                float areaD = (d[2]-d[0])*(d[3]-d[1]);
                float areaK = (k[2]-k[0])*(k[3]-k[1]);
                float iou = inter / (areaD + areaK - inter);
                if (iou > 0.65f) { overlap = true; break; }
            }
            if (!overlap) kept.add(d);
        }

        // compute grid first for score analysis
        Point[][] grid = BoardUtils.calibrateGrid(new LinkedHashMap<>(), boardRect);

        // print detailed scores for horse positions (bottom rank col 1 and 7)
        System.out.println("\n=== 详细类别得分（底线马位置：row=9, col=1 和 col=7） ===");
        for (int i = 0; i < numPreds; i++) {
            float cx = preds[0][i], cy = preds[1][i], w = preds[2][i], h = preds[3][i];
            float x1 = (cx - w/2 - padW) / scale;
            float y1 = (cy - h/2 - padH) / scale;
            float x2 = (cx + w/2 - padW) / scale;
            float y2 = (cy + h/2 - padH) / scale;
            x1 = Math.max(0, Math.min(origW, x1));
            y1 = Math.max(0, Math.min(origH, y1));
            x2 = Math.max(0, Math.min(origW, x2));
            y2 = Math.max(0, Math.min(origH, y2));
            float centerX = (x1 + x2) / 2;
            float centerY = (y1 + y2) / 2;

            // check if near horse grid positions
            boolean nearHorse1 = Math.abs(centerX - grid[9][1].x) < 50 && Math.abs(centerY - grid[9][1].y) < 50;
            boolean nearHorse2 = Math.abs(centerX - grid[9][7].x) < 50 && Math.abs(centerY - grid[9][7].y) < 50;
            if (nearHorse1 || nearHorse2) {
                String pos = nearHorse1 ? "马位置1(col=1)" : "马位置2(col=7)";
                System.out.println("  " + pos + " 附近检测框: center=(" + String.format("%.1f", centerX) + "," + String.format("%.1f", centerY) + ")");
                for (int j = 0; j < 14; j++) {
                    float score = preds[4 + j][i];
                    if (score > 0.05f) {
                        System.out.println("    " + classNames[j] + ": " + String.format("%.4f", score));
                    }
                }
            }
        }

        session.close();

        for (float[] d : kept) {
            int cls = (int) d[5];
            Scalar color = colors[cls];
            Imgproc.rectangle(src, new Point(d[0], d[1]), new Point(d[2], d[3]), color, 2);
            String label = classNames[cls] + String.format(" %.2f", d[4]);
            Imgproc.putText(src, label, new Point(d[0], d[1]-5), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, color, 2);
        }

         // draw grid
         for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Imgproc.circle(src, grid[r][c], 4, new Scalar(255, 0, 255), -1);
            }
        }
        // draw assigned pieces
        Scalar redText = new Scalar(0, 0, 255);
        Scalar blackText = new Scalar(255, 0, 0);
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                if (board[r][c] != null) {
                    Scalar tc = board[r][c].startsWith("r") ? redText : blackText;
                    Imgproc.putText(src, board[r][c], new Point(grid[r][c].x-15, grid[r][c].y-15),
                            Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, tc, 2);
                }
            }
        }

        String outPath = "demos/base-1-yolo-result.jpg";
        Imgcodecs.imwrite(outPath, src);
        System.out.println("Visualization saved to " + outPath);
    }

    @Test
    public void testYolo_base2_fullImage() throws Exception {
        String modelPath = "models/xiangqi_yolo_1280.onnx";
        String imagePath = "demos/base-2.jpg";
        int inputSize = 1280;

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(1);
        OrtSession session = env.createSession(modelPath, opts);

        Mat src = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_COLOR);
        int origW = src.width(), origH = src.height();
        float scale = Math.min((float) inputSize / origW, (float) inputSize / origH);
        int newW = Math.round(origW * scale), newH = Math.round(origH * scale);
        int padW = (inputSize - newW) / 2, padH = (inputSize - newH) / 2;
        int padWRest = (inputSize - newW) % 2, padHRest = (inputSize - newH) % 2;

        Mat resized = new Mat();
        Imgproc.resize(src, resized, new Size(newW, newH));
        Mat padded = new Mat();
        Core.copyMakeBorder(resized, padded, padH, padH + padHRest, padW, padW + padWRest,
                Core.BORDER_CONSTANT, new Scalar(114, 114, 114));

        Imgproc.cvtColor(padded, padded, Imgproc.COLOR_BGR2RGB);

        float[] inputData = new float[3 * inputSize * inputSize];
        for (int c = 0; c < 3; c++)
            for (int y = 0; y < inputSize; y++)
                for (int x = 0; x < inputSize; x++) {
                    double[] pixel = padded.get(y, x);
                    inputData[c * inputSize * inputSize + y * inputSize + x] = (float) (pixel[c] / 255.0);
                }

        OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(inputData), new long[]{1, 3, inputSize, inputSize});
        Map<String, OnnxTensor> inputs = Collections.singletonMap(
                session.getInputNames().iterator().next(), inputTensor);

        OrtSession.Result ortResults = session.run(inputs);
        OnnxTensor outputTensor = (OnnxTensor) ortResults.get(0);
        float[][][] output = (float[][][]) outputTensor.getValue();
        float[][] preds = output[0];

        int numPreds = preds[0].length;
        float confThresh = 0.8f;
        List<float[]> dets = new ArrayList<>();
        for (int i = 0; i < numPreds; i++) {
            float maxScore = 0;
            for (int j = 0; j < 14; j++)
                if (preds[4 + j][i] > maxScore) maxScore = preds[4 + j][i];
            if (maxScore < confThresh) continue;
            float cx = preds[0][i], cy = preds[1][i], w = preds[2][i], h = preds[3][i];
            dets.add(new float[]{cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, maxScore});
        }

        dets.sort((a, b) -> Float.compare(b[4], a[4]));
        List<float[]> kept = new ArrayList<>();
        for (float[] d : dets) {
            boolean overlap = false;
            for (float[] k : kept) {
                float ix1 = Math.max(d[0], k[0]), iy1 = Math.max(d[1], k[1]);
                float ix2 = Math.min(d[2], k[2]), iy2 = Math.min(d[3], k[3]);
                float inter = Math.max(0, ix2 - ix1) * Math.max(0, iy2 - iy1);
                float areaD = (d[2] - d[0]) * (d[3] - d[1]);
                float areaK = (k[2] - k[0]) * (k[3] - k[1]);
                float iou = inter / (areaD + areaK - inter);
                if (iou > 0.65f) { overlap = true; break; }
            }
            if (!overlap) kept.add(d);
        }

        session.close();
        System.out.println("Full image 1280 inference: " + kept.size() + " detections at conf>=0.8");

        org.junit.jupiter.api.Assertions.assertTrue(kept.size() >= 30,
                "Expected >=30 detections on full image at 1280, got " + kept.size());
    }
}
