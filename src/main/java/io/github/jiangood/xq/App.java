package io.github.jiangood.xq;

public class App {

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
