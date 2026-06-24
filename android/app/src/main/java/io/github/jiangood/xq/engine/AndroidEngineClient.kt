package io.github.jiangood.xq.engine

import java.io.*

class AndroidEngineClient(private val engineDir: File) {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    fun start(): Boolean {
        val binaries = listOf("pikafish-armv8-dotprod", "pikafish-armv8")
        var started = false
        for (name in binaries) {
            val file = File(engineDir, name)
            if (file.exists()) {
                try {
                    file.setExecutable(true)
                    process = ProcessBuilder(file.absolutePath)
                        .directory(engineDir)
                        .start()
                    reader = BufferedReader(InputStreamReader(process!!.inputStream))
                    writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
                    send("uci")
                    waitFor("uciok")
                    started = true
                    break
                } catch (e: Exception) {
                    process?.destroy()
                    process = null
                }
            }
        }
        return started
    }

    fun getTopMoves(fen: String, multiPv: Int = 3, depth: Int = 10): List<String> {
        send("position fen $fen")
        Thread.sleep(100)
        send("setoption name MultiPV value $multiPv")
        send("go depth $depth")
        val moves = mutableListOf<String>()
        var line: String?
        while (true) {
            line = reader?.readLine() ?: break
            if (line.startsWith("bestmove")) {
                val best = line.split(" ").getOrNull(1)
                if (best != null && best !in moves) moves.add(best)
                break
            }
            if (line.contains(" pv ")) {
                val parts = line.split(" pv ")
                if (parts.size > 1) {
                    val move = parts[1].trim().split(" ").first()
                    if (move !in moves) moves.add(move)
                }
            }
        }
        return moves
    }

    private fun send(cmd: String) {
        writer?.write("$cmd\n")
        writer?.flush()
    }

    private fun waitFor(prefix: String): String? {
        var line: String?
        while (true) {
            line = reader?.readLine() ?: return null
            if (line.startsWith(prefix)) return line
        }
    }

    fun close() {
        try {
            send("quit")
            process?.waitFor()
        } catch (_: Exception) {}
        process?.destroy()
    }
}
