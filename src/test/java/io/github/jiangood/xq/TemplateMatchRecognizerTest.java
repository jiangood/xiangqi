package io.github.jiangood.xq;

import cn.hutool.core.lang.Assert;
import io.github.jiangood.xq.config.AppConfig;
import io.github.jiangood.xq.opencv.PieceRecognizer;
import io.github.jiangood.xq.opencv.TemplateMatchRecognizer;
import io.github.jiangood.xq.util.FenUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TemplateMatchRecognizerTest {

    @BeforeAll
    public static void setup() {
        AppConfig.saveResultImage = true;
    }

    private void assertRecognizer(PieceRecognizer recognizer, String imagePath, String expectedFen) throws Exception {
        String[][] board = recognizer.parseBoard(imagePath);
        String fen = FenUtil.toFen(board);
        Assert.state(fen.equals(expectedFen), "Expected: " + expectedFen + " but got: " + fen);
    }

    @Test
    public void test_base2() throws Exception {
        assertRecognizer(new TemplateMatchRecognizer(), "demos/base-2.jpg", TestConstants.BASE_FEN);
    }

    @Test
    public void test_last2() throws Exception {
        assertRecognizer(new TemplateMatchRecognizer(), "demos/last-2.jpg", TestConstants.LAST_FEN);
    }

    @Test
    public void test_last2Changed() throws Exception {
        assertRecognizer(new TemplateMatchRecognizer(), "demos/last-2-changed.jpg", TestConstants.LAST_FEN);
    }

}
