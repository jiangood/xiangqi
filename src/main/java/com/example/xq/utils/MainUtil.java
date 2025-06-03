package com.example.xq.utils;

import com.example.xq.utils.opencv.OpenCvUtil;
import com.example.xq.utils.engine.PikafishProcessHandler;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;

public class MainUtil {

    PikafishProcessHandler h = new PikafishProcessHandler();

    public MainUtil(){
        try {
            h.startEngine(new File("bin/Pikafish-20250110/pikafish-bmi2.exe").getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public String process(String imageFile) throws InterruptedException, IOException {
        String[][] boardArr = OpenCvUtil.parse(imageFile);

        // 判断是否标准的红上黑下，如果不是，则红黑转换
        if (!isBlackTop(boardArr)) {
            convertRedBlack(boardArr);
        }

        String board = FenUtil.convertToFEN(boardArr);


        String query = h.getBestMove(board,20);

        String action = CnUtil.convertToChineseNotation(boardArr, query);

        System.out.println(query);

        System.out.println(action);
        return action;
    }

    private static void convertRedBlack(String[][] boardArr) {
        for (int i = 0; i < boardArr.length; i++) {
            String[] row = boardArr[i];
            for (int j = 0; j < row.length; j++) {
                String cell = row[j];
                if (cell != null) {
                    char[] charArray = cell.toCharArray();
                    char color = charArray[0];
                    char type = charArray[1];
                    char newColor = color == '红' ? '黑' : '红';
                    boardArr[i][j] = newColor + "" + type;
                }
            }
        }
    }


    /**
     * 判断  黑将
     * @param boardArr
     * @return
     */
    private static boolean isBlackTop(String[][] boardArr) {
        for (int i = 0; i < 3; i++) {
            for (int j = 4; j < 4 + 3; j++) {
                String piece = boardArr[i][j];
                if (piece != null && piece.equals("黑将")) {
                    return true;
                }
            }
        }
        return false;
    }
}
