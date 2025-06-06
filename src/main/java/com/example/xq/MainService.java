package com.example.xq;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.system.SystemUtil;
import com.example.xq.opencv.OpenCvUtil;
import com.example.xq.engine.PikafishProcessHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;

@Service
@Slf4j
public class MainService {

    PikafishProcessHandler h = new PikafishProcessHandler();
    OpenCvUtil cv = new OpenCvUtil();

    @PostConstruct
    public void init(){
        try {
            boolean win = SystemUtil.getOsInfo().isWindows();
            log.info("是否win {}", win);
            String path = win ? "bin/Pikafish-20250110": "bin/Pikafish-20250110/Linux";
            log.info("皮卡鱼 {}", path);




            h.startEngine(new File(path));

        } catch (IOException e) {
            log.info("初始化皮卡鱼失败", e);
        }
    }


    public String process(String imageFile) throws Exception {
        String[][] boardArr = cv.parseBoard(imageFile);
        log.info("解析棋盘结果：{}", boardArr.length);
        for (String[] row : boardArr) {
            for (String cell : row) {
                System.out.println(cell);
                //System.out.printf(StrUtil.emptyToDefault(cell, "空白"));
            }
        }

        // 判断是否标准的红上黑下，如果不是，则红黑转换
        if (!isBlackTop(boardArr)) {
            convertRedBlack(boardArr);
        }

        String board = FenUtil.convertToFEN(boardArr);


        String query = h.getBestMove(board,10);
        log.info("获取最佳走法:{}",query);

        String action = MoveUtil.convertToChineseNotation(boardArr, query);

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
                    char newColor = color == 'r' ? 'b' : 'r';
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
