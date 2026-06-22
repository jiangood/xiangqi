package io.github.jiangood.xq;

public class XqApplication {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java -jar app.jar <image-file-path>");
            System.exit(1);
        }
        MainService service = new MainService();
        service.init();
        try {
            String result = service.process(args[0]);
            System.out.println(result);
        } finally {
            service.shutdown();
        }
    }
}
