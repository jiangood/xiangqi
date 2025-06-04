package com.example.xq;

import java.util.ArrayList;
import java.util.List;

public class FenUtil {



    public static String convertToFEN(String[][] board) {
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

        // 2. 当前走子方（假设红方先行）
        String activeColor = "w"; // w表示红方，b表示黑方

        // 3. 组合成完整FEN（简化版，实际FEN可能包含更多信息）
        return boardFEN + " " + activeColor;
    }

    private static String convertPieceToFEN(String piece) {
        if (piece == null || piece.length() != 2) {
            return "";
        }

        char colorChar = piece.charAt(0); // 第一个字符表示颜色
        char typeChar = piece.charAt(1);  // 第二个字符表示棋子类型

        // 确定棋子字母大小写（红方大写，黑方小写）
        char fenChar = switch (typeChar) {
            case '车' -> 'r';
            case '马' -> 'n';
            case '炮' -> 'c';
            case '象', '相' -> 'b';
            case '士', '仕' -> 'a';
            case '将', '帅' -> 'k';
            case '兵', '卒' -> 'p';
            default -> ' ';
        };

        if (colorChar == '红') {
            fenChar = Character.toUpperCase(fenChar);
        }

        return String.valueOf(fenChar);
    }

    public static void main(String[] args) {
        // 示例棋盘（10行9列）
        String[][] board = {
            {"黑车", "黑马", "黑象", "黑士", "黑将", "黑士", "黑象", "黑马", "黑车"},
            {"  ", "  ", "  ", "  ", "  ", "  ", "  ", "  ", "  "},
            {"  ", "黑炮", "  ", "  ", "  ", "  ", "  ", "黑炮", "  "},
            {"黑卒", "  ", "黑卒", "  ", "黑卒", "  ", "黑卒", "  ", "黑卒"},
            {"  ", "  ", "  ", "  ", "  ", "  ", "  ", "  ", "  "},

            {"  ", "  ", "  ", "  ", "  ", "  ", "  ", "  ", "  "},
            {"红兵", "  ", "红兵", "  ", "红兵", "  ", "红兵", "  ", "红兵"},
            {"  ", "红炮", "  ", "  ", "  ", "  ", "  ", "红炮", "  "},
            {"  ", "  ", "  ", "  ", "  ", "  ", "  ", "  ", "  "},
            {"红车", "红马", "红相", "红仕", "红帅", "红仕", "红相", "红马", "红车"}
        };

        String fen = convertToFEN(board);
        System.out.println("FEN: " + fen);

        String action = "a3a4";

        String s = MoveUtil.convertToChineseNotation(board, action);
        System.out.println("Action:" + s);
    }
}
