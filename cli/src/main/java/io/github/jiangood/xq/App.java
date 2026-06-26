package io.github.jiangood.xq;

import java.io.File;
import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class App {

    private static final Logger LOG = Logger.getLogger(App.class.getName());

    static {
        try {
            File logDir = new File("logs");
            logDir.mkdirs();

            File prev = new File(logDir, "app_prev.log");
            File curr = new File(logDir, "app.log");

            if (prev.exists()) {
                prev.delete();
            }
            if (curr.exists()) {
                curr.renameTo(prev);
            }

            FileHandler fh = new FileHandler("logs/app.log");
            fh.setFormatter(new SimpleFormatter());
            Logger.getLogger("").addHandler(fh);
        } catch (IOException e) {
            LOG.warning("无法初始化日志文件: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java -jar app.jar <image-file-path>");
            System.exit(1);
        }
        BoardService service = new BoardService();
        service.init();
        try {
            String result = service.process(args[0]);
            System.out.println(result);
        } finally {
            service.shutdown();
        }
    }
}
