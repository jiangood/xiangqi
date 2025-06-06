package com.example.xq;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/***

 进：棋子向对方方向（即棋盘上方）移动。
 退：棋子向己方方向（即棋盘下方）移动。
 平：棋子横向移动（左右移动，不改变纵线）。

 */
public class NameUtil {

    // 列字母到数字的映射（a-i对应0-8）
    private static final String COLUMN_LETTERS = "abcdefghi";
    private static final String COLUMN_LETTERS_CN = "九八七六五四三二一";

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

        String suffix = convertToChineseNotation(move);

        return pieceName + suffix;
    }

    /**
     * 参考图三
     * @param move
     * @return
     */
    public static String convertToChineseNotation(String move) {
        if (move.length() != 4) {
            return "非法走法" + move;
        }

        // 解析起始和目标位置, 坐标原点为左下
        char c1 = move.charAt(0);
        char c2 = move.charAt(1);
        char c3 = move.charAt(2);
        char c4 = move.charAt(3);




        int letterIndex = COLUMN_LETTERS.indexOf(c1);
        char letterCn = COLUMN_LETTERS_CN.charAt(letterIndex);


        int letterIndex2 = COLUMN_LETTERS.indexOf(c3);
        char letterCn2 = COLUMN_LETTERS_CN.charAt(letterIndex2);

        int startRow = 10 - Character.getNumericValue(c2) - 1;


        int endCol = COLUMN_LETTERS.indexOf(c3);

        int endRow = 10 - Character.getNumericValue(c4) - 1;




        int startPos = 9 - (letterIndex);
        int endPos = 9 - (endCol);

        // 平
        if(c2 == c4){ // 平，      H2-E2  炮二平五
            return letterCn + "平" + letterCn2;
        }
        // 进退， 判断字母相同
        if(c1 == c3){ //     map.put("E2-E6", "炮五进四");
            int step =c4-c2;
            String dir = step > 0 ? "进":"退";
            return letterCn +dir + letterCn2;
        }


        String direction = endRow > startRow ? "进" : "退";
        int distance = Math.abs(endRow - startRow);
        // 如果直线走，最后一个字为步数
        if (letterIndex == endCol) {
            return startPos + direction + distance;
        }

        // 如果是否斜着走，最后一个字为目标列号
        return startPos + direction + endPos;
    }

    public static char getChinesePieceName(char pieceType) {
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

    public static void main(String[] args) {
     //   convertToChineseNotation("e6e4");
        Map<String, String> map = new LinkedHashMap<>();
        map.put("E2-E6", "炮五进四");
        map.put("H2-E2", "炮二平五");
        map.put("H0-G2", "马二进三");
        map.put("B2-E2", "炮八平五");
        map.put("E6-E4", "前炮退二");

        for (Map.Entry<String, String> e : map.entrySet()) {
            String move = e.getKey().replace("-","").toLowerCase();
            String x = convertToChineseNotation(move);
            System.out.println(x);
        }



    }


}
