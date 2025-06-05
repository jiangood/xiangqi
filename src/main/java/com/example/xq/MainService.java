package com.example.xq;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.setting.dialect.PropsUtil;
import cn.hutool.system.SystemUtil;
import com.example.xq.cv.CvUtil;
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

    @PostConstruct
    public void init(){
        try {
            boolean win = SystemUtil.getOsInfo().isWindows();
            log.info("是否win {}", win);
            String path = win ? "bin/Pikafish-20250110/pikafish-bmi2.exe": "bin/Pikafish-20250110/Linux/pikafish-avx2";
            log.info("皮卡鱼 {}", path);
            File file = new File(path);
            String absolutePath = file.getAbsolutePath();
            log.info("绝对路径 {}, 是否存在{}", absolutePath, file.exists());
            Assert.state(file.exists(), "文件不存在 " + absolutePath);

            String help = RuntimeUtil.execForStr(absolutePath, "help");
            log.info("help: {}",help);

            h.startEngine(absolutePath);
        } catch (IOException e) {
            log.info("初始化皮卡鱼失败", e);
        }
    }


    public String process(String imageFile) throws InterruptedException, IOException {
        String[][] boardArr = CvUtil.parse(imageFile);

        // 判断是否标准的红上黑下，如果不是，则红黑转换
        if (!isBlackTop(boardArr)) {
            convertRedBlack(boardArr);
        }

        String board = FenUtil.convertToFEN(boardArr);


        String query = h.getBestMove(board,20);

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
