package com.example.xq;

import com.example.xq.cv.CvUtil;

public class Main {

    public static void main(String[] args) {
        String[][] boardArr = CvUtil.parse("demos/base.jpg");

        String board = ChineseChessFENConverter.convertToFEN(boardArr);

        String query = Chessdb.query(board);

        System.out.println(query);
    }
}
