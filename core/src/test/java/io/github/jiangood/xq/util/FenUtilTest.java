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
