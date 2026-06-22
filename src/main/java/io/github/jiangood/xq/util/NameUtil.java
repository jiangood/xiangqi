package io.github.jiangood.xq.util;

import java.util.LinkedHashMap;
import java.util.Map;

/***

 进：棋子向对方方向（即棋盘上方）移动。
 退：棋子向己方方向（即棋盘下方）移动。
 平：棋子横向移动（左右移动，不改变纵线）。

 */
public class NameUtil {


    // 中文棋谱从右到左
    private static final String COLUMN_LETTERS_CN = "一二三四五六七八九";
    private static final Map<Character, Character> PIECE_MAP = Map.of(
            'r', '车', 'n', '马', 'c', '炮',
            'b', '象', 'a', '士', 'k', '帅', 'p', '兵'
    );


    public static String convertToChineseNotation(String[][] board, String move) {
        if (move.length() != 4) {
            return "非法走法" + move;
        }

        // 解析起始和目标位置
        int x1 = move.charAt(0) - 'a';
        int y1 = move.charAt(1) - '0';

        // 获取棋子类型
        String piece = board[9 - y1][x1];

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
     * <p>
     * 坐标
     *
     * @param move
     * @return
     */
    public static String convertToChineseNotation(String move) {
        if (move.length() != 4) {
            return "非法走法" + move;
        }

        // 解析起始和目标位置, 坐标原点为左下
        int x1 = move.charAt(0) - 'a';
        int y1 = move.charAt(1) - '0';
        int x2 = move.charAt(2) - 'a';
        int y2 = move.charAt(3) - '0';

        // 中文（中文坐标从右到左，从1开始）
        char x1cn = COLUMN_LETTERS_CN.charAt(COLUMN_LETTERS_CN.length() - x1 - 1);
        char x2cn = COLUMN_LETTERS_CN.charAt(COLUMN_LETTERS_CN.length() - x2 - 1);

        // 平
        if (y1 == y2) { // 平，      H2-E2  炮二平五
            return x1cn + "平" + x2cn;
        }


        String dir = y2 > y1 ? "进" : "退";
        int step = Math.abs(y2 - y1);
        char stepCn = COLUMN_LETTERS_CN.charAt(step - 1);

        // 进退， 判断字母相同
        if (x1 == x2) { //     map.put("E2-E6", "炮五进四");
            return x1cn + dir + stepCn;
        }

        // 如果是否斜着走，最后一个字为目标列号
        return x1cn + dir + x2cn;
    }

    public static char getChinesePieceName(char pieceType) {
        return PIECE_MAP.getOrDefault(pieceType, pieceType);
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
            String move = e.getKey().replace("-", "").toLowerCase();
            String result = convertToChineseNotation(move);
            System.out.println(e.getKey() + " " +e.getValue() + " > " + result);
        }


    }


}
