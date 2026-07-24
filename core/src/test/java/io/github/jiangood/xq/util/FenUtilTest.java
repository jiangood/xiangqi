package io.github.jiangood.xq.util;

import io.github.jiangood.xq.opencv.BoardUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class FenUtilTest {

    private String[][] emptyBoard() {
        String[][] b = new String[10][9];
        for (int i = 0; i < 10; i++)
            for (int j = 0; j < 9; j++)
                b[i][j] = null;
        return b;
    }

    @Test
    public void fenRedKingAtBottom_shouldBe_w() {
        String[][] board = emptyBoard();
        board[9][4] = "rk";
        board[0][4] = "bk";
        String fen = FenUtil.toFen(board);
        assertTrue("expected w for red at bottom", fen.endsWith(" w"));
    }

    @Test
    public void fenBlackKingAtBottom_shouldBe_b() {
        String[][] board = emptyBoard();
        board[9][4] = "bk";
        board[0][4] = "rk";
        String fen = FenUtil.toFen(board);
        assertTrue("expected b for black at bottom", fen.endsWith(" b"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void fenNoKing_throws() {
        String[][] board = emptyBoard();
        board[9][0] = "rr";
        FenUtil.toFen(board);
    }

    @Test
    public void fenKingInRow9_takesPrecedence() {
        String[][] board = emptyBoard();
        board[9][4] = "bk";
        board[7][4] = "rk";
        String fen = FenUtil.toFen(board);
        assertTrue("expected b (bk in row 9)", fen.endsWith(" b"));
    }

    @Test
    public void fenKingInRow8_detected() {
        String[][] board = emptyBoard();
        board[8][4] = "rk";
        board[0][4] = "bk";
        String fen = FenUtil.toFen(board);
        assertTrue("expected w (rk in row 8)", fen.endsWith(" w"));
    }

    // ─── FEN board orientation ────────────────────────────────────

    @Test
    public void fenRedBottom_boardRowsForward() {
        // 红方在下: FEN 行序应与图像行序一致(board[0]=FEN rank 10, board[9]=FEN rank 1)
        String[][] board = emptyBoard();
        board[0][4] = "bk";  // 图像顶部=黑将
        board[9][4] = "rk";  // 图像底部=红帅
        String fen = FenUtil.toFen(board);
        // FEN rank 10(第一行)应该包含"k"(黑将小写), rank 1(最后一行)应该包含"K"(红帅大写)
        String[] ranks = fen.split(" ")[0].split("/");
        assertTrue("rank 10 should have black king (k)", ranks[0].contains("k"));
        assertTrue("rank 1 should have red king (K)", ranks[9].contains("K"));
    }

    @Test
    public void fenBlackBottom_boardRowsReversed() {
        // 黑方在下: FEN 行序应与图像行序相反(board[9]=FEN rank 10, board[0]=FEN rank 1)
        String[][] board = emptyBoard();
        board[0][4] = "rk";  // 图像顶部=红帅
        board[9][4] = "bk";  // 图像底部=黑将
        String fen = FenUtil.toFen(board);
        // FEN rank 10(第一行)应该包含"k"(黑将小写), rank 1(最后一行)应该包含"K"(红帅大写)
        String[] ranks = fen.split(" ")[0].split("/");
        assertTrue("rank 10 should have black king (k)", ranks[0].contains("k"));
        assertTrue("rank 1 should have red king (K)", ranks[9].contains("K"));
    }

    @Test
    public void fenBlackBottom_activeColorIsB() {
        String[][] board = emptyBoard();
        board[0][4] = "rk";
        board[9][4] = "bk";
        String fen = FenUtil.toFen(board);
        assertTrue("expected b for black at bottom", fen.endsWith(" b"));
    }

    @Test
    public void fenSymmetry_redVsBlackBottom() {
        // 对称验证: 同一盘面分别从红方和黑方视角, 棋盘部分应该互为镜像
        String[][] redBottom = emptyBoard();
        redBottom[0][4] = "bk"; redBottom[9][4] = "rk";    // 红在下
        redBottom[1][0] = "br"; redBottom[8][0] = "rr";    // 车对称

        String[][] blackBottom = emptyBoard();
        blackBottom[0][4] = "rk"; blackBottom[9][4] = "bk"; // 黑在下
        blackBottom[1][0] = "rr"; blackBottom[8][0] = "br"; // 车对称(翻转)

        String fenRed = FenUtil.toFen(redBottom);
        String fenBlack = FenUtil.toFen(blackBottom);

        // 棋盘部分应一致(去掉 active color 比较)
        String boardRed = fenRed.split(" ")[0];
        String boardBlack = fenBlack.split(" ")[0];
        assertEquals("board part should be identical", boardRed, boardBlack);
    }

    // ─── BoardUtils.isRedBottom ───────────────────────────────────

    @Test
    public void isRedBottom_redAtBottom() {
        String[][] board = emptyBoard();
        board[9][4] = "rk";
        board[0][4] = "bk";
        assertTrue("expected red bottom", BoardUtils.isRedBottom(board));
    }

    @Test
    public void isRedBottom_blackAtBottom() {
        String[][] board = emptyBoard();
        board[9][4] = "bk";
        board[0][4] = "rk";
        assertFalse("expected black bottom", BoardUtils.isRedBottom(board));
    }

    @Test(expected = IllegalArgumentException.class)
    public void isRedBottom_missingBothKings_throws() {
        String[][] board = emptyBoard();
        BoardUtils.isRedBottom(board);
    }

    @Test(expected = IllegalArgumentException.class)
    public void isRedBottom_missingOneKing_throws() {
        String[][] board = emptyBoard();
        board[9][4] = "rk";
        BoardUtils.isRedBottom(board);
    }

    @Test
    public void isRedBottom_kingsInPalaceOuterColumn() {
        String[][] board = emptyBoard();
        board[7][3] = "rk";
        board[2][5] = "bk";
        assertTrue("expected red bottom (rk=7 > bk=2)", BoardUtils.isRedBottom(board));
    }
}
