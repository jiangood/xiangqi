package io.github.jiangood.xq;

import cn.hutool.system.SystemUtil;
import io.github.jiangood.xq.opencv.PieceRecognizer;
import io.github.jiangood.xq.opencv.TemplateMatchRecognizer;
import io.github.jiangood.xq.opencv.YoloPieceRecognizer;
import io.github.jiangood.xq.engine.EngineClient;
import io.github.jiangood.xq.util.FenUtil;
import io.github.jiangood.xq.util.NotationConverter;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BoardService {

    private static final Logger log = Logger.getLogger(BoardService.class.getName());

    private static final boolean USE_YOLO = true;

    EngineClient engineClient = new EngineClient();
    PieceRecognizer boardRecognizer;

    {
        if (USE_YOLO) {
            try {
                log.info("使用 YOLO 棋子识别");
                boardRecognizer = new YoloPieceRecognizer("models/xiangqi_yolo.onnx");
            } catch (Exception e) {
                throw new RuntimeException("YOLO 模型加载失败", e);
            }
        } else {
            log.info("使用模板匹配棋子识别");
            boardRecognizer = new TemplateMatchRecognizer();
        }
    }

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

    private static final Map<String, String> PIECE_CHINESE = new LinkedHashMap<>();

    static {
        // 红方
        PIECE_CHINESE.put("rk", "帅");
        PIECE_CHINESE.put("ra", "仕");
        PIECE_CHINESE.put("rb", "相");
        PIECE_CHINESE.put("rr", "車");
        PIECE_CHINESE.put("rn", "馬");
        PIECE_CHINESE.put("rc", "炮");
        PIECE_CHINESE.put("rp", "兵");
        // 黑方
        PIECE_CHINESE.put("bk", "将");
        PIECE_CHINESE.put("ba", "士");
        PIECE_CHINESE.put("bb", "象");
        PIECE_CHINESE.put("br", "车");
        PIECE_CHINESE.put("bn", "马");
        PIECE_CHINESE.put("bc", "炮");
        PIECE_CHINESE.put("bp", "卒");
    }

    public static void printBoard(String[][] board) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 9; j++) {
                String p = board[i][j];
                sb.append(p == null ? "＋" : PIECE_CHINESE.getOrDefault(p, p)).append(" ");
            }
            sb.append("\n");
        }
        sb.append("\n红方：帅 仕 相 車 馬 炮 兵\n");
        sb.append("黑方：将 士 象 车 马 炮 卒");
        System.out.println(sb);
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
