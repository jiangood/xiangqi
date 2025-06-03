package com.example.xq;

import cn.hutool.core.lang.Assert;
import com.example.xq.utils.FenUtil;
import com.example.xq.utils.opencv.OpenCvUtil;
import org.junit.jupiter.api.Test;

public class FenTest {

    public static final String BASE_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w";


    @Test
    public void process() throws InterruptedException {
        String[][] board = OpenCvUtil.parse("demos/base.jpg");

        String fen = FenUtil.convertToFEN(board);
        System.out.printf(BASE_FEN);
        System.out.printf(fen);

        Assert.state(fen.equals(BASE_FEN));
        System.out.println(fen);
    }
}
