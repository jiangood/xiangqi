package com.example.xq.engine;

import java.io.*;

public class PikafishProcessHandler {
    private Process engineProcess;
    private BufferedReader reader;
    private BufferedWriter writer;

    public void startEngine(String enginePath) throws IOException {
        engineProcess = Runtime.getRuntime().exec(enginePath);
        reader = new BufferedReader(new InputStreamReader(engineProcess.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(engineProcess.getOutputStream()));

        // 发送初始化命令
        sendCommand("uci");
        waitForResponse("uciok");
    }

    public void sendCommand(String command) throws IOException {
        writer.write(command + "\n");
        writer.flush();
    }

    public String waitForResponse(String expected) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
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
