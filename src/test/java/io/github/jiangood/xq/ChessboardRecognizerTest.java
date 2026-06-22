package io.github.jiangood.xq;

import cn.hutool.core.lang.Assert;
import io.github.jiangood.xq.opencv.PieceRecognizer;
import io.github.jiangood.xq.opencv.TemplateMatchRecognizer;
import io.github.jiangood.xq.util.FenUtil;
import org.junit.jupiter.api.Test;

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
}
