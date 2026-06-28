package io.github.jiangood.xq.engine

import android.content.Context
import android.util.Log
import io.github.jiangood.xq.util.AppLog
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class AndroidEngineClient(private val context: Context) {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var isReady = false

    companion object {
        private const val TAG = "AndroidEngineClient"
        private const val NNUE_NAME = "pikafish.nnue"
        private val ENGINE_NAMES = listOf("pikafish-armv8")
    }

    fun start(): Boolean {
        for (name in ENGINE_NAMES) {
            try {
                val file = findEngine(name) ?: continue
                file.setExecutable(true)
                if (!file.canExecute()) {
                    Runtime.getRuntime().exec(arrayOf("chmod", "700", file.absolutePath))
                        .waitFor()
                }
                if (!file.canExecute()) continue

                process = ProcessBuilder(file.absolutePath)
                    .directory(context.filesDir)
                    .redirectErrorStream(true)
                    .start()
                reader = BufferedReader(InputStreamReader(process!!.inputStream))
                writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))

                if (uciHandshake()) {
                    isReady = true
                    Log.i(TAG, "Engine started: ${file.name}")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start $name", e)
                process?.destroy()
                process = null
            }
        }
        return false
    }

    private fun findEngine(name: String): File? {
        // 1. nativeLibraryDir (extracted by PackageManager from jniLibs)
        val nativeFile = File(context.applicationInfo.nativeLibraryDir, "lib${name}.so")
        if (nativeFile.exists()) {
            Log.i(TAG, "Found engine in nativeLibraryDir: $nativeFile")
            return nativeFile
        }

        // 2. Extract from APK's lib/ directory (fallback)
        try {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
            val apkPath = appInfo.sourceDir
            val abiDir = if (nativeFile.parentFile?.name == "arm64") "arm64-v8a" else "arm64"
            val entryPath = "lib/${abiDir}/lib${name}.so"
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(entryPath) ?: return null
                val outFile = File(context.filesDir, name)
                if (!outFile.exists()) {
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (outFile.exists()) {
                    Log.i(TAG, "Extracted engine from APK zip: $outFile")
                    return outFile
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "APK zip extraction failed", e)
        }

        return null
    }

    private fun uciHandshake(): Boolean {
        AppLog.add("[引擎] UCI 握手开始")
        send("uci")
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15)
        var line: String?
        while (System.currentTimeMillis() < deadline) {
            line = reader?.readLine() ?: break
            AppLog.add("[引擎] << $line")
            if (line.startsWith("uciok")) {
                AppLog.add("[引擎] 收到 uciok")
                val nnueFile = File(context.filesDir, NNUE_NAME)
                if (nnueFile.exists()) {
                    AppLog.add("[引擎] 设置 NNUE: ${nnueFile.absolutePath}")
                    send("setoption name EvalFile value ${nnueFile.absolutePath}")
                }
                send("isready")
                val readyDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10)
                while (System.currentTimeMillis() < readyDeadline) {
                    line = reader?.readLine() ?: break
                    AppLog.add("[引擎] << $line")
                    if (line.startsWith("readyok")) {
                        AppLog.add("[引擎] UCI 握手成功")
                        return true
                    }
                }
                AppLog.add("[引擎] UCI 握手失败: readyok 超时")
                return false
            }
        }
        AppLog.add("[引擎] UCI 握手失败: uciok 超时")
        return false
    }

    private fun isProcessAlive(): Boolean {
        return try {
            process?.let { it.alive && it.exitValue() == 0 } ?: false
        } catch (_: IllegalThreadStateException) {
            true // process is still running
        }
    }

    fun getTopMoves(fen: String, multiPv: Int = 3, depth: Int = 10): List<String> {
        AppLog.add("[引擎] getTopMoves: isReady=$isReady, processAlive=${isProcessAlive()}")
        if (!isReady) {
            AppLog.add("[引擎] getTopMoves: 引擎未就绪，返回空列表")
            return emptyList()
        }
        if (!isProcessAlive()) {
            val exitVal = try { process?.exitValue() } catch (_: Exception) { -1 }
            AppLog.add("[引擎] getTopMoves: 引擎进程已退出, exitValue=$exitVal")
            isReady = false
            return emptyList()
        }

        return try {
            AppLog.add("[引擎] 发送棋局: position fen $fen")
            send("position fen $fen")
            AppLog.add("[引擎] 设置 MultiPV=$multiPv")
            send("setoption name MultiPV value $multiPv")
            AppLog.add("[引擎] 开始分析 depth=$depth")
            send("go depth $depth")

            val moves = mutableListOf<String>()
            var line: String?
            var lineCount = 0
            var lastLine = ""
            val deadline = System.currentTimeMillis() + 30000

            while (System.currentTimeMillis() < deadline) {
                line = reader?.readLine() ?: break
                lineCount++
                lastLine = line
                AppLog.add("[引擎] >> $line")
                if (line.startsWith("bestmove")) {
                    val best = line.split(" ").getOrNull(1)
                    if (best != null && best !in moves) {
                        moves.add(best)
                        AppLog.add("[引擎] 解析 bestmove: $best")
                    }
                    break
                }
                if (line.contains(" pv ")) {
                    val parts = line.split(" pv ")
                    if (parts.size > 1) {
                        val move = parts[1].trim().split(" ").first()
                        if (move !in moves) {
                            moves.add(move)
                            AppLog.add("[引擎] 解析 PV 走法: $move")
                        }
                    }
                }
            }

            if (moves.isEmpty()) {
                val timedOut = System.currentTimeMillis() >= deadline
                if (timedOut) {
                    AppLog.add("[引擎] getTopMoves: 超时! 读取了 $lineCount 行, 最后一行: $lastLine")
                } else {
                    AppLog.add("[引擎] getTopMoves: 无走法, 读取了 $lineCount 行, reader 已关闭")
                }
            }
            AppLog.add("[引擎] getTopMoves 完成: ${moves.size} 条走法")
            moves
        } catch (e: Exception) {
            AppLog.add("[引擎] getTopMoves 异常: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "getTopMoves failed", e)
            emptyList()
        }
    }

    private fun send(cmd: String) {
        try {
            if (writer == null) {
                AppLog.add("[引擎] send 失败: writer 为空")
                return
            }
            writer?.write("$cmd\n")
            writer?.flush()
        } catch (e: IOException) {
            AppLog.add("[引擎] send 失败: ${e.message}")
            Log.e(TAG, "send failed", e)
        }
    }

    fun close() {
        isReady = false
        try {
            val exitVal = try { process?.exitValue() } catch (_: Exception) { null }
            if (exitVal != null) {
                AppLog.add("[引擎] 关闭, 进程已退出: exitValue=$exitVal")
            } else {
                AppLog.add("[引擎] 关闭, 发送 quit 命令")
                send("quit")
                process?.waitFor(2, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {}
        try {
            process?.let {
                if (it.isAlive) {
                    it.destroyForcibly()
                }
            }
        } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
    }
}
