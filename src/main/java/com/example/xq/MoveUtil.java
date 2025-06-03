package com.example.xq;

/***

 进：棋子向对方方向（即棋盘上方）移动。
 退：棋子向己方方向（即棋盘下方）移动。
 平：棋子横向移动（左右移动，不改变纵线）。

 */
public class MoveUtil {

    // 列字母到数字的映射（a-i对应0-8）
    private static final String COLUMN_LETTERS = "abcdefghi";


    public static String convertToChineseNotation(String[][] board, String move) {
        if (move.length() != 4) {
            return "非法走法";
        }

        // 解析起始和目标位置
        int startCol = COLUMN_LETTERS.indexOf(move.charAt(0));
        int startRow = 10 - Character.getNumericValue(move.charAt(1)) - 1;

        int endCol = COLUMN_LETTERS.indexOf(move.charAt(2));
        int endRow = 10 - Character.getNumericValue(move.charAt(3)) - 1;

        if (startCol == -1 || endCol == -1 || startRow < 0 || startRow > 9 || endRow < 0 || endRow > 9) {
            return "非法走法";
        }

        // 获取棋子类型
        String piece = board[startRow][startCol];
        if (piece == null || piece.trim().isEmpty()) {
            return "起始位置无棋子";
        }

        char pieceType = piece.charAt(1); // 第二个字符是棋子类型
        String pieceName = getChinesePieceName(pieceType);

        // 生成中文描述
        int startPos = 9 - (startCol);
        int endPos = 9 - (endCol);

        // 平
        if (startRow == endRow) {
            return pieceName + startPos +  "平" + endPos;
        }

        String direction = endRow < startRow ? "进" : "退";
        int distance = Math.abs( endRow - startRow);
        return pieceName + startPos + direction + distance;
    }

    private static String getChinesePieceName(char pieceType) {
        return switch (pieceType) {
            case '车' -> "车";
            case '马' -> "马";
            case '炮' -> "炮";
            case '相', '象' -> "象";
            case '仕', '士' -> "士";
            case '帅', '将' -> "帅";
            case '兵', '卒' -> "兵";
            default -> "";
        };
    }



}
