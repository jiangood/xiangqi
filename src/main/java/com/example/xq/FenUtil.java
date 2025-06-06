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
        if (colorChar == 'r') {
            typeChar = Character.toUpperCase(typeChar);
        }

        return String.valueOf(typeChar);
    }


}
