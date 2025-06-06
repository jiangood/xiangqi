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
            return "非法走法" + move;
        }

        // 解析起始和目标位置
        int startCol = COLUMN_LETTERS.indexOf(move.charAt(0));
        int startRow = 10 - Character.getNumericValue(move.charAt(1)) - 1;

        int endCol = COLUMN_LETTERS.indexOf(move.charAt(2));
        int endRow = 10 - Character.getNumericValue(move.charAt(3)) - 1;

        if (startCol == -1 || endCol == -1 || startRow < 0 || startRow > 9 || endRow < 0 || endRow > 9) {
            return "非法走法, 坐标错误" + startRow;
        }

        // 获取棋子类型
        String piece = board[startRow][startCol];
        if (piece == null || piece.trim().isEmpty()) {
            return "起始位置无棋子";
        }

        char pieceType = piece.charAt(1); // 第二个字符是棋子类型
        String pieceName = String.valueOf(getChinesePieceName(pieceType));

        // 生成中文描述
        int startPos = 9 - (startCol);
        int endPos = 9 - (endCol);

        // 平
        if (startRow == endRow) {
            return pieceName + startPos + "平" + endPos;
        }

        String direction = endRow < startRow ? "进" : "退";
        int distance = Math.abs(endRow - startRow);
        // 如果直线走，最后一个字为步数
        if (startCol == endCol) {
            return pieceName + startPos + direction + distance;
        }

        // 如果是否斜着走，最后一个字为目标列号
        return pieceName + startPos + direction + endCol;
    }

    private static char getChinesePieceName(char pieceType) {
        return switch (pieceType) {
            case 'r' -> '车';
            case 'n' -> '马';
            case 'c' -> '炮';
            case 'b' -> '象';
            case 'a' -> '士';
            case 'k' -> '帅';
            case 'p' -> '兵';
            default -> pieceType;
        };
    }


}
