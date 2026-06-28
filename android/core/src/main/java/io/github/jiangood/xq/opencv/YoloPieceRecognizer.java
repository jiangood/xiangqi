package io.github.jiangood.xq.opencv;

import ai.onnxruntime.*;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.util.*;
import java.util.logging.Logger;

public class YoloPieceRecognizer implements PieceRecognizer {

    private static final Logger log = Logger.getLogger(YoloPieceRecognizer.class.getName());
    private static final int INPUT_SIZE = 640;
    private static final float CONFIDENCE_THRESHOLD = 0.25f;
    private static final float NMS_THRESHOLD = 0.65f;
    private static final int NUM_CLASSES = 14;

    private static final String[] CLASS_NAMES = {
        "rk", "ra", "rb", "rr", "rn", "rc", "rp",
        "bk", "ba", "bb", "br", "bn", "bc", "bp"
    };

    private final OrtEnvironment env;
    private final OrtSession session;

    public YoloPieceRecognizer(String modelPath) throws OrtException {
        log.info("初始化 YoloPieceRecognizer, 模型: " + modelPath);
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
        srcColor = BoardUtils.cropCenter(srcColor);
        Mat srcGrayMat = new Mat();
        Imgproc.cvtColor(srcColor, srcGrayMat, Imgproc.COLOR_BGR2GRAY);

        // Canny edges
        Mat srcCannyMat = new Mat();
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(srcGrayMat, blurred, new Size(5, 5), 0);
        Imgproc.Canny(blurred, srcCannyMat, 30, 100);

        // Dilated edges + contours
        Mat srcCannyDilated = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
        Imgproc.dilate(srcCannyMat, srcCannyDilated, kernel);
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(srcCannyDilated, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        // Board rect
        Rect boardRect = BoardUtils.locateBoard(srcGrayMat);

        // Crop board
        Mat boardCroppedMat = new Mat(srcColor, boardRect).clone();

        // Otsu binary
        Mat inferGray = new Mat();
        Imgproc.cvtColor(boardCroppedMat, inferGray, Imgproc.COLOR_BGR2GRAY);
        Imgproc.threshold(inferGray, inferGray, 0, 255,
                Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        Mat binaryBoard = inferGray.clone();

        // YOLO inference on board crop
        Imgproc.cvtColor(inferGray, boardCroppedMat, Imgproc.COLOR_GRAY2BGR);
        Map<Point, String> rawDets = runYoloInference(boardCroppedMat);

        // Color correction (clone raw detections to preserve originals)
        Map<Point, String> correctedDets = new LinkedHashMap<>();
        for (Map.Entry<Point, String> e : rawDets.entrySet()) {
            correctedDets.put(e.getKey().clone(), e.getValue());
        }
        correctColorsFromOriginal(correctedDets, srcColor, boardRect);

        // Grid calibration — detect lines + calibrate
        double cellSizeEst = binaryBoard.width() / 9.0;
        int[][] detectedLines = BoardUtils.detectGridLines(binaryBoard, cellSizeEst);
        int[] hLinePos = detectedLines[0];
        int[] vLinePos = detectedLines[1];

        double[] riverLine = null;
        if (hLinePos != null && hLinePos.length >= 6) {
            double hCenter = binaryBoard.height() / 2.0;
            riverLine = BoardUtils.detectRiver(hLinePos, cellSizeEst, hCenter);
        }

        Point[][] calibratedGrid = BoardUtils.calibrateGrid(correctedDets, boardRect, binaryBoard);

        // Populate intermediate result
        IntermediateResult ir = new IntermediateResult();
        ir.srcOriginal = srcColor;
        ir.srcGray = srcGrayMat;
        ir.srcCanny = srcCannyMat;
        ir.srcCannyDilated = srcCannyDilated;
        ir.contours = contours;
        ir.boardRect = boardRect;
        ir.boardCropped = boardCroppedMat;
        ir.boardBinary = binaryBoard;
        ir.hLinePositions = hLinePos;
        ir.vLinePositions = vLinePos;
        ir.riverLine = riverLine;
        ir.grid = calibratedGrid;
        ir.rawDetections = rawDets;
        ir.correctedDetections = correctedDets;
        this.lastIntermediate = ir;

        // Existing fields for backward compat
        this.lastSrc = srcColor;
        this.lastBoardRect = boardRect;
        this.lastDetections = correctedDets;
        this.lastGrid = calibratedGrid;

        return BoardUtils.assignPiecesToGrid(correctedDets, calibratedGrid, boardRect);
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

    private void correctColorsFromOriginal(Map<Point, String> detections, Mat srcColor, Rect boardRect) {
        for (Map.Entry<Point, String> entry : detections.entrySet()) {
            String cls = entry.getValue();
            boolean detectedRed = cls.startsWith("r");
            int imgX = (int) (entry.getKey().x + boardRect.x);
            int imgY = (int) (entry.getKey().y + boardRect.y);

            int halfWindow = 4;
            boolean actualRed = false;
            outer:
            for (int dy = -halfWindow; dy <= halfWindow; dy++) {
                for (int dx = -halfWindow; dx <= halfWindow; dx++) {
                    int px = imgX + dx;
                    int py = imgY + dy;
                    if (px >= 0 && px < srcColor.width() && py >= 0 && py < srcColor.height()) {
                        double[] pixel = srcColor.get(py, px);
                        if (pixel != null && pixel[2] - pixel[1] > 60) {
                            actualRed = true;
                            break outer;
                        }
                    }
                }
            }

            if (detectedRed != actualRed) {
                String corrected = cls.charAt(0) == 'r' ? "b" + cls.substring(1) : "r" + cls.substring(1);
                log.fine("颜色修正: " + cls + " -> " + corrected + " at (" + imgX + "," + imgY + ")");
                entry.setValue(corrected);
            }
        }
    }

    // Fields for Android preview generation
    public Mat lastSrc;
    public Rect lastBoardRect;
    public Map<Point, String> lastDetections;
    public Point[][] lastGrid;
    public IntermediateResult lastIntermediate;

    private static class Detection {
        float x1, y1, x2, y2, score;
        int classId;

        Detection(float x1, float y1, float x2, float y2, float score, int classId) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.score = score; this.classId = classId;
        }
    }
}
