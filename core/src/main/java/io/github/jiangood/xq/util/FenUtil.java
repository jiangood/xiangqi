package io.github.jiangood.xq.util;

import io.github.jiangood.xq.opencv.BoardUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FenUtil {

    public static final Map<String, String> PIECE_CHINESE = Map.ofEntries(
            Map.entry("rk", "帅"), Map.entry("ra", "仕"), Map.entry("rb", "相"),
            Map.entry("rr", "車"), Map.entry("rn", "馬"), Map.entry("rc", "炮"), Map.entry("rp", "兵"),
            Map.entry("bk", "将"), Map.entry("ba", "士"), Map.entry("bb", "象"),
            Map.entry("br", "车"), Map.entry("bn", "马"), Map.entry("bc", "炮"), Map.entry("bp", "卒")
    );

    private static final Map<String, String> PIECE_NAMES = Map.ofEntries(
            Map.entry("rk", "红帅"), Map.entry("ra", "红仕"), Map.entry("rb", "红相"),
            Map.entry("rr", "红车"), Map.entry("rn", "红马"), Map.entry("rc", "红炮"), Map.entry("rp", "红兵"),
            Map.entry("bk", "黑将"), Map.entry("ba", "黑士"), Map.entry("bb", "黑象"),
            Map.entry("br", "黑车"), Map.entry("bn", "黑马"), Map.entry("bc", "黑炮"), Map.entry("bp", "黑卒")
    );

    public static boolean isValidPosition(String[][] board) {
        return validatePositionDetails(board).isEmpty();
    }

    public static List<String> validatePositionDetails(String[][] board) {
        List<String> errors = new ArrayList<>();
        if (board == null) {
            errors.add("棋盘为空");
            return errors;
        }
        if (board.length != 10 || board[0].length != 9) {
            errors.add("棋盘尺寸不正确: " + board.length + "×" + board[0].length + "，应为10×9");
            return errors;
        }

        boolean redBottom;
        try {
            redBottom = BoardUtils.isRedBottom(board);
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
            redBottom = true;
        }

        for (int row = 0; row < 10; row++) {
            for (int col = 0; col < 9; col++) {
                String piece = board[row][col];
                if (piece == null || piece.length() != 2) continue;
                char color = piece.charAt(0);
                char type = piece.charAt(1);
                String error = getPlacementError(color, type, row, col, redBottom);
                if (error != null) {
                    errors.add(error);
                }
            }
        }
        return errors;
    }

    private static String getPlacementError(char color, char type, int row, int col, boolean redBottom) {
        String pieceKey = color + "" + type;
        String pieceName = PIECE_NAMES.getOrDefault(pieceKey, "未知棋子(" + pieceKey + ")");
        String pos = "(" + row + "," + col + ")";
        boolean isRed = (color == 'r');
        int homeStart = redBottom ? 7 : 0;
        int homeEnd = redBottom ? 9 : 2;
        int halfStart = redBottom ? 5 : 0;
        int halfEnd = redBottom ? 9 : 4;
        if (!isRed) {
            homeStart = redBottom ? 0 : 7;
            homeEnd = redBottom ? 2 : 9;
            halfStart = redBottom ? 0 : 5;
            halfEnd = redBottom ? 4 : 9;
        }
        switch (type) {
            case 'k':
            case 'a':
                if (col < 3 || col > 5) return pieceName + "在" + pos + "位置异常：不在九宫范围内";
                if (row < homeStart || row > homeEnd) return pieceName + "在" + pos + "位置异常：不在九宫范围内";
                return null;
            case 'b':
                if (row < halfStart || row > halfEnd) return pieceName + "在" + pos + "位置异常：不在己方半场";
                return null;
            default:
                return null;
        }
    }

    public static String toFen(String[][] board) {
        if (board == null || board.length != 10 || board[0].length != 9) {
            throw new IllegalArgumentException("棋盘必须是10行9列的二维数组");
        }

        // 1. 处理棋盘部分
        List<String> fenRows = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            StringBuilder rowBuilder = new StringBuilder();
            int emptyCount = 0;

            for (int j = 0; j < 9; j++) {
                String piece = board[i][j];

                if (piece == null || piece.trim().isEmpty() || piece.equals("  ")) {
                    emptyCount++;
                } else {
                    if (emptyCount > 0) {
                        rowBuilder.append(emptyCount);
                        emptyCount = 0;
                    }
                    rowBuilder.append(convertPieceToFEN(piece));
                }
            }

            if (emptyCount > 0) {
                rowBuilder.append(emptyCount);
            }

            fenRows.add(rowBuilder.toString());
        }

        String boardFEN = String.join("/", fenRows);

        // 2. 根据将帅宫格位置确定走子方
        boolean redBottom = BoardUtils.isRedBottom(board);
        String activeColor = redBottom ? "w" : "b";

        return boardFEN + " " + activeColor;
    }

    public static int countPieces(String[][] board) {
        int count = 0;
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                if (board[r][c] != null) count++;
        return count;
    }

    private static String convertPieceToFEN(String piece) {
        if (piece == null || piece.length() != 2) {
            return "";
        }

        char colorChar = piece.charAt(0); // 第一个字符表示颜色
        char typeChar = piece.charAt(1);  // 第二个字符表示棋子类型

        // 确定棋子字母大小写（红方大写，黑方小写）
        if (colorChar == 'r') {
            typeChar = Character.toUpperCase(typeChar);
        }

        return String.valueOf(typeChar);
    }


}
