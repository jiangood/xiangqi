package io.github.jiangood.xq;

import cn.hutool.system.SystemUtil;
import io.github.jiangood.xq.opencv.ChessboardRecognizer;
import io.github.jiangood.xq.engine.EngineClient;
import io.github.jiangood.xq.util.FenUtil;
import io.github.jiangood.xq.util.NotationConverter;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BoardService {

    private static final Logger log = Logger.getLogger(BoardService.class.getName());

    EngineClient engineClient = new EngineClient();
    ChessboardRecognizer boardRecognizer = new ChessboardRecognizer();

    public void init() {
        try {
            boolean win = SystemUtil.getOsInfo().isWindows();
            log.info("是否win " + win);
            String path = "bin/Pikafish-20250110";
            log.info("皮卡鱼 " + path);
            engineClient.startEngine(new File(path));
        } catch (IOException e) {
            log.log(Level.SEVERE, "初始化皮卡鱼失败", e);
        }
    }

    public void shutdown() {
        engineClient.close();
    }

    public String process(String imageFile) throws Exception {
        long time = System.currentTimeMillis();
        String[][] board = boardRecognizer.parseBoard(imageFile);
        log.info("解析棋盘，耗时：" + (System.currentTimeMillis() - time));
        StringBuilder sb = new StringBuilder();
        for (String[] row : board) {
            for (String c : row) {
                sb.append(c == null ? "+" : c);
            }
            sb.append("\n");
        }
        log.info("棋盘:\n" + sb);

        // 判断是否标准的红上黑下，如果不是，则红黑转换
        if (!isBlackTop(board)) {
            convertRedBlack(board);
        }

        String fen = FenUtil.toFen(board);

        String query = engineClient.getBestMove(fen, 10);
        log.info("获取最佳走法:" + query);
        log.info("耗时:" + (System.currentTimeMillis() - time));

        String action = NotationConverter.convertToChineseNotation(board, query);

        log.info("引擎输出: " + query);
        log.info("中文棋谱: " + action);
        return action;
    }

    private static void convertRedBlack(String[][] board) {
        for (int i = 0; i < board.length; i++) {
            String[] row = board[i];
            for (int j = 0; j < row.length; j++) {
                String cell = row[j];
                if (cell != null) {
                    char[] charArray = cell.toCharArray();
                    char color = charArray[0];
                    char type = charArray[1];
                    char newColor = color == 'r' ? 'b' : 'r';
                    board[i][j] = newColor + "" + type;
                }
            }
        }
    }

    private static boolean isBlackTop(String[][] board) {
        for (int i = 0; i < 3; i++) {
            for (int j = 3; j < 6; j++) {
                String piece = board[i][j];
                if (piece != null && piece.equals("bk")) {
                    return true;
                }
            }
        }
        return false;
    }
}
