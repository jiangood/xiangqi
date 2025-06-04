package com.example.xq.engine;

import java.io.File;
import java.io.IOException;

public class Pikafish {

    public static void main(String[] args) throws IOException {
        PikafishProcessHandler h = new PikafishProcessHandler();
        h.startEngine(new File("bin/Pikafish-20250110/pikafish-bmi2.exe").getAbsolutePath());
        String bestMove = h.getBestMove("r2ak1b1r/4a4/2n1b1nc1/p1p1p1p1p/2c6/6P2/P3P3P/N1CC2N2/9/1RBAKAB1R w", 20);
        System.out.println(bestMove);
    }
}
