package com.example.xq.engine;

import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.system.SystemUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.*;

@Slf4j
public class PikafishProcessHandler {
    private Process engineProcess;
    private BufferedReader reader;
    private BufferedWriter writer;

    // 大多数情况下，引擎速度：vnni512>avx512>avx512f>avxvnni>bmi2>avx2>sse41-popcnt>ssse3 棋友根据自己的CPU选择相应的引擎
    private static  String[] FILE_NAMES = {"vnni512","avx512","avx512f","avxvnni","bmi2","avx2","sse41-popcnt","ssse3"};

    public void startEngine(File dir) throws IOException {
        boolean win = SystemUtil.getOsInfo().isWindows();

        for (String fileName : FILE_NAMES) {
            fileName = "pikafish-" + fileName;
            if(win){
                fileName = fileName +".exe";
            }
            File file = new File(dir, fileName);
            if(!file.exists()){
                log.warn("软件不存在 {}", file.getAbsolutePath());
                continue;
            }
            String help = RuntimeUtil.execForStr(file.getAbsolutePath(), "help");
            log.info("help: {}",help);
            if(StrUtil.isEmpty(help)){
                log.warn("软件无法运行 {}", file);
                continue;
            }


            log.info("使用引擎" + file.getAbsolutePath());

            engineProcess = Runtime.getRuntime().exec(file.getAbsolutePath());
            reader = new BufferedReader(new InputStreamReader(engineProcess.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(engineProcess.getOutputStream()));

            // 发送初始化命令
            sendCommand("uci");
            waitForResponse("uciok");
        }
    }

    public void sendCommand(String command) throws IOException {
        log.info("发送命令行: {}",command);
        writer.write(command + "\n");
        writer.flush();
    }

    public String waitForResponse(String expected) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            log.info("命令行响应: {}",line);
            if (line.startsWith(expected)) {
                return line;
            }
        }
        return null;
    }

    public String getBestMove(String fen, int depth) throws IOException {
        sendCommand("position fen " + fen);
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
