package com.example.xq.utils.engine;

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

    public static void main(String[] args) throws IOException {
        PikafishProcessHandler h = new PikafishProcessHandler();
        h.startEngine(new File("bin/Pikafish-20250110/pikafish-bmi2.exe").getAbsolutePath());
        String bestMove = h.getBestMove("r2ak1b1r/4a4/2n1b1nc1/p1p1p1p1p/2c6/6P2/P3P3P/N1CC2N2/9/1RBAKAB1R w", 20);
        System.out.println(bestMove);
    }
}
