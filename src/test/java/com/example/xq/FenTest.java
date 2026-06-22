package com.example.xq;

import cn.hutool.core.lang.Assert;
import com.example.xq.opencv.OpenCvUtil;
import org.junit.jupiter.api.Test;

public class FenTest {

    public static final String BASE_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w";


    @Test
    public void processBase1() throws Exception {
        String[][] board = new  OpenCvUtil().parseBoard("demos/base-1.jpg");
        String fen = FenUtil.convertToFEN(board);
        Assert.state(fen.equals(BASE_FEN));
    }

    @Test
    public void processBase2() throws Exception {
        String[][] board = new  OpenCvUtil().parseBoard("demos/base-2.jpg");
        String fen = FenUtil.convertToFEN(board);
        System.out.println("解析FEN:" + fen);
        System.out.println("开局FEN:" + BASE_FEN);
        Assert.state(fen.equals(BASE_FEN));
    }
}
