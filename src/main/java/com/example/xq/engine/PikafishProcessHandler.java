package com.example.xq.engine;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.system.SystemUtil;

import java.io.*;
import java.util.logging.Logger;

public class PikafishProcessHandler {

    private static final Logger log = Logger.getLogger(PikafishProcessHandler.class.getName());

    private Process engineProcess;
    private BufferedReader reader;
    private BufferedWriter writer;

    private static String[] FILE_NAMES = {"vnni512", "avx512", "avx512f", "avxvnni", "bmi2", "avx2", "sse41-popcnt", "ssse3"};

    public void startEngine(File dir) throws IOException {
        File file = findFile(dir);

        log.info("使用引擎" + file.getAbsolutePath());

        engineProcess = Runtime.getRuntime().exec(file.getAbsolutePath());
        reader = new BufferedReader(new InputStreamReader(engineProcess.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(engineProcess.getOutputStream()));

        sendCommand("uci");
        waitForResponse("uciok");
    }

    private File findFile(File dir) {
        boolean win = SystemUtil.getOsInfo().isWindows();

        for (String fileName : FILE_NAMES) {
            fileName = "pikafish-" + fileName;
            if (win) {
                fileName = fileName + ".exe";
            }
            File file = new File(dir, fileName);
            if (!file.exists()) {
                log.warning("软件不存在 " + file.getAbsolutePath());
                continue;
            }
            String help = RuntimeUtil.execForStr(file.getAbsolutePath(), "help");
            log.info("help: " + help);
            if (StrUtil.isNotEmpty(help) && StrUtil.count(help, "\n") > 2) {
                return file;
            }
            log.warning("当前引擎无法运行，继续判断 " + file);
        }

        return null;
    }

    public void sendCommand(String command) throws IOException {
        log.info("发送命令行: " + command);
        writer.write(command + "\n");
        writer.flush();
    }

    public String waitForResponse(String expected) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            log.info("命令行响应: " + line);
            if (line.startsWith(expected)) {
                return line;
            }
        }
        return null;
    }

    public String getBestMove(String fen, int depth) throws IOException {
        sendCommand("position fen " + fen);
        ThreadUtil.sleep(100);
        sendCommand("go depth " + depth);
        String bestMove = waitForResponse("bestmove");
        return bestMove != null ? bestMove.split(" ")[1] : null;
    }

    public void close() {
        try {
            sendCommand("quit");
            engineProcess.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
