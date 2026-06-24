package io.github.jiangood.xq;

import cn.hutool.core.lang.Assert;
import io.github.jiangood.xq.config.AppConfig;
import io.github.jiangood.xq.opencv.YoloPieceRecognizer;
import io.github.jiangood.xq.util.FenUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class YoloRecognizerTest {

    @BeforeAll
    public static void setup() {
        AppConfig.saveResultImage = true;
    }

    private void assertRecognizer(YoloPieceRecognizer recognizer, String imagePath, String expectedFen) throws Exception {
        String[][] board = recognizer.parseBoard(imagePath);
        String fen = FenUtil.toFen(board);
        if (!fen.equals(expectedFen)) {
            String[] actualRows = fen.split("/");
            String[] expectedRows = expectedFen.split("/");
            for (int i = 0; i < 10; i++) {
                String match = actualRows[i].equals(expectedRows[i]) ? " OK" : " <<<";
                System.out.println("  row " + i + ": actual=" + actualRows[i] + " expected=" + expectedRows[i] + match);
            }
        }
        Assert.state(fen.equals(expectedFen), "Expected: " + expectedFen + " but got: " + fen);
    }

    // base-1.jpg 是另一种风格截图，模板匹配无法识别；
    // YOLO 能定位所有棋子但红方底线有分类错误，需比对输出确认具体哪些棋子识别错误
    // TODO: 识别错误，待分析原因
    @Test
    public void testYolo_base1() throws Exception {
        assertRecognizer(new YoloPieceRecognizer("models/xiangqi_yolo.onnx"), "demos/base-1.jpg",
                TestConstants.BASE_FEN);
    }

    @Test
    public void testYolo_base2() throws Exception {
        assertRecognizer(new YoloPieceRecognizer("models/xiangqi_yolo.onnx"), "demos/base-2.jpg",
                TestConstants.BASE_FEN);
    }

    @Test
    public void testYolo_last2() throws Exception {
        assertRecognizer(new YoloPieceRecognizer("models/xiangqi_yolo.onnx"), "demos/last-2.jpg",
                TestConstants.LAST_FEN);
    }

    @Test
    public void testYolo_last2Changed() throws Exception {
        assertRecognizer(new YoloPieceRecognizer("models/xiangqi_yolo.onnx"), "demos/last-2-changed.jpg",
                TestConstants.LAST_FEN);
    }
}
