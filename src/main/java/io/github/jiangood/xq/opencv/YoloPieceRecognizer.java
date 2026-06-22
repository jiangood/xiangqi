package io.github.jiangood.xq.opencv;

import ai.onnxruntime.*;
import cn.hutool.core.io.FileUtil;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.logging.Logger;

public class YoloPieceRecognizer implements PieceRecognizer {

    private static final Logger log = Logger.getLogger(YoloPieceRecognizer.class.getName());
    private static final int INPUT_SIZE = 1280;
    private static final float CONFIDENCE_THRESHOLD = 0.25f;
    private static final float NMS_THRESHOLD = 0.65f;
    private static final int NUM_CLASSES = 14;

    private static final String[] CLASS_NAMES = {
        "rk", "ra", "rb", "rr", "rn", "rc", "rp",
        "bk", "ba", "bb", "br", "bn", "bc", "bp"
    };

    static {
        try {
            System.load(new File("lib/opencv_java4110.dll").getCanonicalPath());
        } catch (IOException e) {
            throw new RuntimeException("无法加载 OpenCV 原生库", e);
        }
    }

    private final OrtEnvironment env;
    private final OrtSession session;

    public YoloPieceRecognizer(String modelPath) throws OrtException {
        log.info("初始化 YoloPieceRecognizer, 模型: " + modelPath);
        cn.hutool.core.lang.Assert.notNull(FileUtil.file(modelPath), "模型文件不存在: " + modelPath);
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(1);
        this.session = env.createSession(modelPath, opts);
        log.info("模型加载成功, 输入节点: " + session.getInputNames() + ", 输出节点: " + session.getOutputNames());
    }

    @Override
    public String[][] parseBoard(String imageFile) throws Exception {
        log.info("加载图像: " + imageFile);
        Mat srcColor = Imgcodecs.imread(imageFile, Imgcodecs.IMREAD_COLOR);
        Mat srcGray = new Mat();
        Imgproc.cvtColor(srcColor, srcGray, Imgproc.COLOR_BGR2GRAY);

        Rect boardRect = BoardUtils.locateBoard(srcGray);
        log.info("棋盘外边框: " + boardRect);

        // 全图 1280 推理，比裁切棋盘后推理更准确
        Map<Point, String> fullImageDetections = runYoloInference(srcColor);
        log.info("YOLO 检测到 " + fullImageDetections.size() + " 个棋子");

        // 将全图坐标转换为棋盘相对坐标
        Map<Point, String> detections = new LinkedHashMap<>();
        for (Map.Entry<Point, String> entry : fullImageDetections.entrySet()) {
            Point relPt = new Point(
                entry.getKey().x - boardRect.x,
                entry.getKey().y - boardRect.y
            );
            if (relPt.x >= 0 && relPt.x <= boardRect.width && relPt.y >= 0 && relPt.y <= boardRect.height) {
                detections.put(relPt, entry.getValue());
            }
        }
        log.info("棋盘区域内 " + detections.size() + " 个棋子");

        Point[][] calibratedGrid = BoardUtils.calibrateGrid(detections, boardRect);
        log.info("自校准网格完成");

        return BoardUtils.assignPiecesToGrid(detections, calibratedGrid, boardRect);
    }

    private Map<Point, String> runYoloInference(Mat boardRegion) throws OrtException {
        int origW = boardRegion.width();
        int origH = boardRegion.height();
        float scale = Math.min((float) INPUT_SIZE / origW, (float) INPUT_SIZE / origH);
        int newW = Math.round(origW * scale);
        int newH = Math.round(origH * scale);
        int padW = (INPUT_SIZE - newW) / 2;
        int padH = (INPUT_SIZE - newH) / 2;
        int padWRest = (INPUT_SIZE - newW) % 2;
        int padHRest = (INPUT_SIZE - newH) % 2;

        Mat resized = new Mat();
        Imgproc.resize(boardRegion, resized, new Size(newW, newH));

        Mat padded = new Mat();
        Core.copyMakeBorder(resized, padded, padH, padH + padHRest, padW, padW + padWRest,
                Core.BORDER_CONSTANT, new Scalar(114, 114, 114));

        Imgproc.cvtColor(padded, padded, Imgproc.COLOR_BGR2RGB);

        float[] inputData = new float[3 * INPUT_SIZE * INPUT_SIZE];
        for (int c = 0; c < 3; c++) {
            for (int y = 0; y < INPUT_SIZE; y++) {
                for (int x = 0; x < INPUT_SIZE; x++) {
                    double[] pixel = padded.get(y, x);
                    inputData[c * INPUT_SIZE * INPUT_SIZE + y * INPUT_SIZE + x] = (float) (pixel[c] / 255.0);
                }
            }
        }

        OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(inputData),
                new long[]{1, 3, INPUT_SIZE, INPUT_SIZE});

        Map<String, OnnxTensor> inputs = Collections.singletonMap(
                session.getInputNames().iterator().next(), inputTensor);

        try (OrtSession.Result results = session.run(inputs)) {
            OnnxTensor outputTensor = (OnnxTensor) results.get(0);
            float[][][] output = (float[][][]) outputTensor.getValue();
            return postProcess(output[0], origW, origH, scale, padW, padH);
        }
    }

    private Map<Point, String> postProcess(float[][] predictions, int origW, int origH,
                                            float scale, int padW, int padH) {
        int numPredictions = predictions[0].length;
        List<Detection> detections = new ArrayList<>();
        for (int i = 0; i < numPredictions; i++) {
            float cx = predictions[0][i];
            float cy = predictions[1][i];
            float w = predictions[2][i];
            float h = predictions[3][i];

            float maxScore = 0;
            int classId = -1;
            for (int j = 0; j < NUM_CLASSES; j++) {
                float score = predictions[4 + j][i];
                if (score > maxScore) {
                    maxScore = score;
                    classId = j;
                }
            }

            if (maxScore < CONFIDENCE_THRESHOLD) continue;

            float x1 = cx - w / 2;
            float y1 = cy - h / 2;
            float x2 = cx + w / 2;
            float y2 = cy + h / 2;

            detections.add(new Detection(x1, y1, x2, y2, maxScore, classId));
        }

        detections.sort((a, b) -> Float.compare(b.score, a.score));
        List<Detection> kept = new ArrayList<>();
        for (Detection d : detections) {
            boolean overlapping = false;
            for (Detection k : kept) {
                if (iou(d, k) > NMS_THRESHOLD) {
                    overlapping = true;
                    break;
                }
            }
            if (!overlapping) {
                kept.add(d);
            }
        }

        log.info("NMS 后剩余 " + kept.size() + " 个检测");

        Map<Point, String> result = new LinkedHashMap<>();
        for (Detection d : kept) {
            float centerX = (d.x1 + d.x2) / 2;
            float centerY = (d.y1 + d.y2) / 2;

            float origX = (centerX - padW) / scale;
            float origY = (centerY - padH) / scale;

            origX = Math.max(0, Math.min(origW, origX));
            origY = Math.max(0, Math.min(origH, origY));

            result.put(new Point(origX, origY), CLASS_NAMES[d.classId]);
        }

        return result;
    }

    private static float iou(Detection a, Detection b) {
        float interX1 = Math.max(a.x1, b.x1);
        float interY1 = Math.max(a.y1, b.y1);
        float interX2 = Math.min(a.x2, b.x2);
        float interY2 = Math.min(a.y2, b.y2);

        float interArea = Math.max(0, interX2 - interX1) * Math.max(0, interY2 - interY1);
        float areaA = (a.x2 - a.x1) * (a.y2 - a.y1);
        float areaB = (b.x2 - b.x1) * (b.y2 - b.y1);

        return interArea / (areaA + areaB - interArea);
    }

    private static class Detection {
        float x1, y1, x2, y2, score;
        int classId;

        Detection(float x1, float y1, float x2, float y2, float score, int classId) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.score = score; this.classId = classId;
        }
    }
}
