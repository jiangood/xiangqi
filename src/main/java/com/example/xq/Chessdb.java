package com.example.xq;

import cn.hutool.http.HttpUtil;

import java.util.HashMap;
import java.util.Map;

public class Chessdb {

    public static void main(String[] args) {
        String query = query("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w");
        System.out.println(query);
    }

    public static String query(String board) {
        String url = "http://www.chessdb.cn/chessdb.php?action=querybest&board";

        Map<String, Object> params = new HashMap<>();
        params.put("action", "querybest");
        params.put("board", board);

        String body = HttpUtil.get(url, params);

         body = body.substring(5,9);

        return body;
    }
}
