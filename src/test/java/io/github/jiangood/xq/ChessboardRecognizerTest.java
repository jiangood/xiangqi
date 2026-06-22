package io.github.jiangood.xq;

import cn.hutool.core.lang.Assert;
import io.github.jiangood.xq.opencv.ChessboardRecognizer;
import io.github.jiangood.xq.util.FenUtil;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class ChessboardRecognizerTest {

    public static final String BASE_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w";


/*    @Test
    public void processBase1() throws Exception {
        String[][] board = new ChessboardRecognizer().parseBoard("demos/base-1.jpg");
        String fen = FenUtil.toFen(board);
        Assert.state(fen.equals(BASE_FEN));
    }*/

    @Test
    public void processBase2() throws Exception {
        String[][] board = new ChessboardRecognizer().parseBoard("demos/base-2.jpg");
        String fen = FenUtil.toFen(board);
        Assert.state(fen.equals(BASE_FEN));
    }

    @Test
    public void processLast2() throws Exception {
        String[][] board = new ChessboardRecognizer().parseBoard("demos/last-2.jpg");
        String fen = FenUtil.toFen(board);
        Assert.state(fen.equals("2ba1k3/4a4/4b4/pr7/9/9/P7P/4Br3/3KR4/9 w"));
    }
}
