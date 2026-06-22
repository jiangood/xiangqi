package io.github.jiangood.xq;

import ai.onnxruntime.*;
import cn.hutool.core.lang.Assert;
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
