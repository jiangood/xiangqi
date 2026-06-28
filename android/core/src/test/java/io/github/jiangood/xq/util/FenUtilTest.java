package io.github.jiangood.xq.util;

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

    @Test
    public void fenNoKing_fallback_w() {
        String[][] board = emptyBoard();
        board[9][0] = "rr";
        String fen = FenUtil.toFen(board);
        assertTrue("expected w fallback", fen.endsWith(" w"));
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
}
